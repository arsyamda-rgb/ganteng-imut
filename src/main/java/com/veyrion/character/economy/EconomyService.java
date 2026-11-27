package com.veyrion.character.economy;

import com.veyrion.character.api.MoneyResult;
import com.veyrion.character.config.PluginSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

/**
 * VeyrionCharacter's view of the server economy. Every balance operation is forwarded to the Vault economy
 * provider through {@link EconomyBridge}; nothing is stored here except a short-lived read cache of online
 * players' balances. Call from the main thread (the placeholder methods are the documented exception).
 */
public final class EconomyService {

    public enum State { ACTIVE, DISABLED_BY_CONFIG, NO_VAULT, NO_PROVIDER }

    private final Logger logger;
    private final Supplier<PluginSettings.Economy> settings;
    private final EconomyBridge bridge;
    private final BalanceCache cache = new BalanceCache();
    /** Main thread only. */
    private final Map<UUID, Long> lastPay = new HashMap<>();
    private volatile boolean errorLogged;
    /** Makes "check the balance, then take the money" one step, so two callers cannot spend the same money. */
    private final Object withdrawLock = new Object();

    public EconomyService(Logger logger, Supplier<PluginSettings.Economy> settings, EconomyBridge bridge) {
        this.logger = logger;
        this.settings = settings;
        this.bridge = bridge;
    }

    public PluginSettings.Economy settings() {
        return settings.get();
    }

    // ------------------------------------------------------------ availability

    public State state() {
        if (!settings.get().enabled()) {
            return State.DISABLED_BY_CONFIG;
        }
        try {
            return switch (bridge.status()) {
                case AVAILABLE -> State.ACTIVE;
                case NO_VAULT -> State.NO_VAULT;
                case NO_PROVIDER -> State.NO_PROVIDER;
            };
        } catch (RuntimeException | LinkageError e) {
            logProblem("Could not query the Vault economy provider", e);
            return State.NO_PROVIDER;
        }
    }

    public boolean isActive() {
        return state() == State.ACTIVE;
    }

    /** @return the language key explaining why the economy is unusable, or null if it is usable */
    public @Nullable String unavailableKey() {
        return switch (state()) {
            case ACTIVE -> null;
            case DISABLED_BY_CONFIG -> "economy.unavailable-config";
            case NO_VAULT -> "economy.unavailable-vault";
            case NO_PROVIDER -> "economy.unavailable-provider";
        };
    }

    /** One-line description for the console. */
    public String describe() {
        return switch (state()) {
            case ACTIVE -> "active (Vault provider: " + providerName() + ")";
            case DISABLED_BY_CONFIG -> "disabled by economy.enabled=false in config.yml";
            case NO_VAULT -> "disabled: Vault is not installed";
            case NO_PROVIDER -> "disabled: Vault is installed but no economy provider is registered";
        };
    }

    private String providerName() {
        try {
            return bridge.providerName();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------ formatting

    /** "2,500 VYR" (symbol from config). */
    public String format(double amount) {
        return Money.format(amount) + " " + settings.get().currencySymbol();
    }

    // ------------------------------------------------------------ balance

    /** @return the provider's balance, or null if the economy is unavailable or the provider failed */
    public @Nullable Double balance(UUID uuid) {
        if (!isActive()) {
            return null;
        }
        try {
            double value = bridge.balance(uuid);
            if (!Double.isFinite(value)) {
                return null;
            }
            remember(uuid, value);
            return value;
        } catch (RuntimeException | LinkageError e) {
            logProblem("Could not read a balance from the economy provider", e);
            return null;
        }
    }

    /**
     * For PlaceholderAPI. On the main thread the provider is read (at most once per second per player); on any
     * other thread only the cached value is returned, never the provider.
     */
    public @Nullable Double balanceForPlaceholder(UUID uuid) {
        if (!isActive()) {
            return null;
        }
        BalanceCache.Sample sample = cache.get(uuid);
        if (Bukkit.isPrimaryThread()) {
            if (sample != null && System.nanoTime() - sample.atNanos() < 1_000_000_000L) {
                return sample.value();
            }
            return balance(uuid);
        }
        return sample == null ? null : sample.value();
    }

    /** Re-reads the balance of every online player into the cache. Main thread; called by a timer. */
    public void refreshOnline() {
        if (!isActive()) {
            cache.clear();
            return;
        }
        for (var player : Bukkit.getOnlinePlayers()) {
            balance(player.getUniqueId());
        }
    }

    public void forget(UUID uuid) {
        cache.remove(uuid);
        lastPay.remove(uuid);
    }

    private void remember(UUID uuid, double value) {
        if (Bukkit.getPlayer(uuid) != null) {
            cache.put(uuid, value);
        }
    }

    // ------------------------------------------------------------ transactions

    public EconomyBridge.Result deposit(UUID uuid, double amount) {
        return run(uuid, amount, true);
    }

    public EconomyBridge.Result withdraw(UUID uuid, double amount) {
        return run(uuid, amount, false);
    }

    private EconomyBridge.Result run(UUID uuid, double amount, boolean deposit) {
        if (!Double.isFinite(amount) || amount <= 0) {
            return EconomyBridge.Result.fail("invalid amount");
        }
        if (!isActive()) {
            return EconomyBridge.Result.fail("economy unavailable");
        }
        try {
            EconomyBridge.Result result = deposit ? bridge.deposit(uuid, amount) : bridge.withdraw(uuid, amount);
            if (result.success()) {
                balance(uuid); // refresh the cached copy
            }
            return result;
        } catch (RuntimeException | LinkageError e) {
            logProblem("The economy provider threw during a transaction", e);
            return EconomyBridge.Result.fail("provider error");
        }
    }

    /** The smallest amount that can be paid: two decimals, like every balance here. */
    private static boolean validAmount(double amount) {
        return Double.isFinite(amount) && amount <= Money.MAX && Money.round(amount) > 0;
    }

    /** @return true if the provider says the player has at least {@code amount}; false if unavailable or invalid */
    public boolean hasFunds(UUID uuid, double amount) {
        if (!validAmount(amount) || !isActive()) {
            return false;
        }
        try {
            double balance = bridge.balance(uuid);
            return Double.isFinite(balance) && Money.round(balance) >= Money.round(amount);
        } catch (RuntimeException | LinkageError e) {
            logProblem("Could not read a balance from the economy provider", e);
            return false;
        }
    }

    /**
     * The safe withdraw used by the public API: validates the amount, checks the balance and takes the money
     * under one lock, and never takes money when the balance is too low. Unlike {@link #withdraw} it tells the
     * caller why it failed.
     */
    public MoneyResult withdrawChecked(UUID uuid, double amount) {
        if (!validAmount(amount)) {
            return MoneyResult.INVALID_AMOUNT;
        }
        if (!isActive()) {
            return MoneyResult.UNAVAILABLE;
        }
        double rounded = Money.round(amount);
        MoneyResult result;
        synchronized (withdrawLock) {
            try {
                double before = bridge.balance(uuid);
                if (!Double.isFinite(before)) {
                    return MoneyResult.ERROR;
                }
                if (Money.round(before) < rounded) {
                    return MoneyResult.INSUFFICIENT_FUNDS;
                }
                result = bridge.withdraw(uuid, rounded).success() ? MoneyResult.SUCCESS : MoneyResult.ERROR;
            } catch (RuntimeException | LinkageError e) {
                logProblem("The economy provider threw during a withdraw", e);
                return MoneyResult.ERROR;
            }
        }
        if (result.success()) {
            balance(uuid); // refresh the cached copy, outside the lock
        }
        return result;
    }

    /** Sets a balance by depositing or withdrawing the difference (Vault has no "set"). Admin use. */
    public EconomyBridge.Result setBalance(UUID uuid, double target) {
        Double current = balance(uuid);
        if (current == null) {
            return EconomyBridge.Result.fail("economy unavailable");
        }
        double difference = Money.round(target - current);
        if (difference == 0) {
            return EconomyBridge.Result.ok();
        }
        return difference > 0 ? deposit(uuid, difference) : withdraw(uuid, -difference);
    }

    public Transfers.Outcome transfer(UUID from, UUID to, double amount) {
        if (!isActive()) {
            return Transfers.Outcome.ERROR;
        }
        Transfers.Outcome outcome = Transfers.transfer(bridge, from, to, amount, logger::severe);
        balance(from);
        balance(to);
        return outcome;
    }

    /** Spam guard: false if this player paid (or tried to pay) less than the configured cooldown ago. Main thread. */
    public boolean tryAcquirePay(UUID uuid) {
        long now = System.nanoTime();
        Long last = lastPay.get(uuid);
        if (last != null && now - last < settings.get().payCooldownMs() * 1_000_000L) {
            return false;
        }
        lastPay.put(uuid, now);
        return true;
    }

    private void logProblem(String message, Throwable error) {
        if (!errorLogged) {
            errorLogged = true; // once; repeating it for every placeholder request would flood the console
            logger.log(Level.WARNING, message + " (further errors of this kind are not logged)", error);
        }
    }
}
