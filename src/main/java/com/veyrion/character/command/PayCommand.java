package com.veyrion.character.command;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.economy.EconomyService;
import com.veyrion.character.economy.Money;
import com.veyrion.character.economy.Targets;
import com.veyrion.character.economy.Transfers;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /bayar <target> <jumlah>}. Runs entirely on the main thread, so two payments can never interleave; a short
 * per-player cooldown additionally stops command spam. Money moves only through the Vault economy provider.
 */
public final class PayCommand implements TabExecutor {

    private static final String PERMISSION = "veyrion.economy.pay";

    private final VeyrionCharacterPlugin plugin;
    private final EconomyService economy;

    public PayCommand(VeyrionCharacterPlugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var messages = plugin.messages();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "economy.players-only");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            messages.send(player, "command.no-permission");
            return true;
        }
        var settings = economy.settings();
        if (!settings.payCommand()) {
            messages.send(player, "economy.command-disabled");
            return true;
        }
        String unavailable = economy.unavailableKey();
        if (unavailable != null) {
            messages.send(player, unavailable);
            return true;
        }
        if (args.length != 2) {
            messages.send(player, "economy.pay-usage", Placeholder.unparsed("label", label));
            return true;
        }

        OfflinePlayer target = Targets.resolve(args[0]);
        if (target == null) {
            messages.send(player, "economy.target-unknown", Placeholder.unparsed("player", args[0]));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.send(player, "economy.pay-self");
            return true;
        }
        OptionalDouble parsed = Money.parse(args[1]);
        if (parsed.isEmpty() || parsed.getAsDouble() <= 0) {
            messages.send(player, "economy.invalid-amount");
            return true;
        }
        double amount = parsed.getAsDouble();
        if (amount < settings.minimumPayment()) {
            messages.send(player, "economy.pay-too-low",
                    Placeholder.unparsed("minimum", economy.format(settings.minimumPayment())));
            return true;
        }
        if (!economy.tryAcquirePay(player.getUniqueId())) {
            messages.send(player, "economy.pay-cooldown");
            return true;
        }

        String targetName = target.getName() != null ? target.getName() : args[0];
        Transfers.Outcome outcome = economy.transfer(player.getUniqueId(), target.getUniqueId(), amount);
        switch (outcome) {
            case OK -> {
                // Sent without the plugin prefix so the text is exactly the required sentence.
                player.sendMessage(messages.get("economy.pay-success", Placeholder.unparsed("target", targetName)));
                Double balance = economy.balance(player.getUniqueId());
                messages.send(player, "economy.pay-detail", Placeholder.unparsed("amount", economy.format(amount)),
                        Placeholder.unparsed("balance", balance == null ? "?" : economy.format(balance)));
                Player online = target.getPlayer();
                if (online != null) {
                    messages.send(online, "economy.pay-received", Placeholder.unparsed("amount", economy.format(amount)),
                            Placeholder.unparsed("player", player.getName()));
                }
                plugin.getLogger().info(player.getName() + " paid " + Money.plain(amount) + " VYR to " + targetName);
            }
            case INSUFFICIENT -> messages.send(player, "economy.pay-insufficient");
            case WITHDRAW_FAILED, ERROR -> messages.send(player, "economy.pay-failed");
            case DEPOSIT_FAILED_REFUNDED -> messages.send(player, "economy.pay-refunded");
            case DEPOSIT_FAILED_REFUND_FAILED -> messages.send(player, "economy.pay-failed-contact-admin");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission(PERMISSION)) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .filter(player -> !player.equals(sender))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
