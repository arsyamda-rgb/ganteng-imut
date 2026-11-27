package com.veyrion.character.economy;

import com.veyrion.character.character.CharacterRepository;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Top balances without a heavy query per command. Vault has no leaderboard, so balances of the known characters are
 * read from the provider in small time-boxed slices spread over several ticks, kept for a configurable time and
 * shared by every request that arrives meanwhile. Main thread only.
 *
 * <p>Consistency: the list is an approximate snapshot. Balances are read over several ticks and the result may be up
 * to {@code economy.baltop-cache-seconds} old, so a payment made meanwhile can be missing or counted at either end.
 * Offline players are read from the provider like online ones; whatever the provider reports for them is shown.
 */
public final class BalTop {

    public record Entry(String name, double balance) {}

    /** @param ok false if the economy was unavailable or the character list could not be read */
    public record Result(boolean ok, List<Entry> entries) {}

    private static final int SIZE = 10;
    private static final long SLICE_NANOS = 2_000_000L;

    private final VeyrionCharacterPlugin plugin;
    private final EconomyService economy;
    private final Supplier<CompletableFuture<CharacterRepository.Identities>> characters;
    private final List<Consumer<Result>> waiters = new ArrayList<>();
    private @Nullable List<Entry> cached;
    private long cachedAtMillis;
    private boolean computing;

    /** @param characters source of known players, normally {@code CharacterRepository::identities} */
    public BalTop(VeyrionCharacterPlugin plugin, EconomyService economy,
                  Supplier<CompletableFuture<CharacterRepository.Identities>> characters) {
        this.plugin = plugin;
        this.economy = economy;
        this.characters = characters;
    }

    public boolean isFresh() {
        return cached != null && System.currentTimeMillis() - cachedAtMillis < ttlMillis();
    }

    /** The callback runs on the main thread, immediately if the cached list is still fresh. */
    public void request(Consumer<Result> callback) {
        if (isFresh()) {
            callback.accept(new Result(true, cached));
            return;
        }
        waiters.add(callback);
        if (computing) {
            return;
        }
        computing = true;
        characters.get().whenComplete((all, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Could not list characters for /topuang", error);
                    finish(null);
                } else {
                    if (all.invalid() > 0) {
                        plugin.getLogger().warning("/topuang skipped " + all.invalid() + " character row(s) with a "
                                + "malformed, nil or duplicate uuid. Check the characters table.");
                    }
                    step(all.valid(), 0, new ArrayList<>());
                }
            });
        });
    }

    private void step(List<CharacterRepository.Identity> all, int from, List<Entry> found) {
        if (!economy.isActive()) {
            finish(null);
            return;
        }
        long deadline = System.nanoTime() + SLICE_NANOS;
        int index = from;
        do {
            if (index >= all.size()) {
                break;
            }
            CharacterRepository.Identity identity = all.get(index++);
            Double balance = economy.balance(identity.uuid());
            if (balance != null && balance > 0) {
                Player online = Bukkit.getPlayer(identity.uuid());
                // The stored name is only refreshed on join, so an online player's live name is the accurate one.
                found.add(new Entry(online != null ? online.getName() : identity.name(), balance));
            }
        } while (System.nanoTime() < deadline);

        if (index < all.size()) {
            int next = index;
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> step(all, next, found));
            }
            return;
        }
        found.sort(Comparator.comparingDouble(Entry::balance).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        finish(List.copyOf(found.subList(0, Math.min(SIZE, found.size()))));
    }

    private void finish(@Nullable List<Entry> result) {
        computing = false;
        if (result != null) {
            cached = result;
            cachedAtMillis = System.currentTimeMillis();
        }
        List<Consumer<Result>> ready = List.copyOf(waiters);
        waiters.clear();
        Result answer = result == null ? new Result(false, List.of()) : new Result(true, result);
        for (Consumer<Result> waiter : ready) {
            waiter.accept(answer);
        }
    }

    private long ttlMillis() {
        return economy.settings().baltopCacheSeconds() * 1000L;
    }
}
