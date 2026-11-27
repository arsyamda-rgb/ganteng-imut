package com.veyrion.character.command;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.economy.EconomyBridge;
import com.veyrion.character.economy.EconomyService;
import com.veyrion.character.economy.Money;
import com.veyrion.character.economy.Targets;
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

/** {@code /veconomy give|take|set <player> <amount>}, {@code pending} (ticket reconciliation) and {@code reload}; all balance changes go through Vault. */
public final class EconomyAdminCommand implements TabExecutor {

    private static final String PERMISSION = "veyrion.economy.admin";
    private static final List<String> SUBCOMMANDS = List.of("give", "take", "set", "pending", "reload");

    private final VeyrionCharacterPlugin plugin;
    private final EconomyService economy;

    public EconomyAdminCommand(VeyrionCharacterPlugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var messages = plugin.messages();
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadEconomy();
                messages.send(sender, "economy.admin-reloaded", Placeholder.unparsed("state", economy.describe()));
            }
            case "give", "take", "set" -> modify(sender, label, args, sub);
            case "pending" -> pending(sender, label, args);
            default -> messages.send(sender, "economy.admin-usage", Placeholder.unparsed("label", label));
        }
        return true;
    }

    /**
     * {@code pending} lists tickets stuck in PAYING; {@code pending paid <id>} marks one as already paid (nothing is
     * paid); {@code pending retry <id> confirm} pays it again. Retry needs the word "confirm" because paying a ticket
     * whose first payout actually succeeded would duplicate the money.
     */
    private void pending(CommandSender sender, String label, String[] args) {
        var messages = plugin.messages();
        var payouts = plugin.vyrPayouts();
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            payouts.review((rows, waiting) -> {
                var current = plugin.messages();
                if (rows == null) {
                    current.send(sender, "economy.pending-error");
                    return;
                }
                sender.sendMessage(current.get("economy.pending-header",
                        Placeholder.unparsed("count", Integer.toString(rows.size())),
                        Placeholder.unparsed("waiting", Integer.toString(waiting))));
                int shown = 0;
                for (var row : rows) {
                    if (shown++ >= 15) {
                        sender.sendMessage(current.get("economy.pending-more",
                                Placeholder.unparsed("count", Integer.toString(rows.size() - 15))));
                        break;
                    }
                    OfflinePlayer owner = Bukkit.getOfflinePlayer(row.uuid());
                    sender.sendMessage(current.get("economy.pending-line",
                            Placeholder.unparsed("id", Long.toString(row.id())),
                            Placeholder.unparsed("player", owner.getName() != null ? owner.getName() : row.uuid().toString()),
                            Placeholder.unparsed("quest", row.questId()),
                            Placeholder.unparsed("amount", economy.format(row.amount()))));
                }
                if (!rows.isEmpty()) {
                    sender.sendMessage(current.get("economy.pending-help", Placeholder.unparsed("label", label)));
                }
            });
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!(action.equals("paid") || action.equals("retry")) || args.length < 3) {
            messages.send(sender, "economy.pending-usage", Placeholder.unparsed("label", label));
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id <= 0) {
            messages.send(sender, "economy.pending-usage", Placeholder.unparsed("label", label));
            return;
        }
        if (action.equals("retry") && !(args.length == 4 && args[3].equalsIgnoreCase("confirm"))) {
            messages.send(sender, "economy.pending-retry-warning", Placeholder.unparsed("label", label),
                    Placeholder.unparsed("id", Long.toString(id)));
            return;
        }
        long ticket = id;
        java.util.function.Consumer<com.veyrion.character.economy.VyrPayouts.Resolution> done = outcome ->
                plugin.messages().send(sender, "economy.pending-" + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        Placeholder.unparsed("id", Long.toString(ticket)));
        if (action.equals("paid")) {
            payouts.settle(id, sender.getName(), done);
        } else {
            payouts.reopen(id, sender.getName(), done);
        }
    }

    private void modify(CommandSender sender, String label, String[] args, String sub) {
        var messages = plugin.messages();
        String unavailable = economy.unavailableKey();
        if (unavailable != null) {
            messages.send(sender, unavailable);
            return;
        }
        if (args.length != 3) {
            messages.send(sender, "economy.admin-usage", Placeholder.unparsed("label", label));
            return;
        }
        OfflinePlayer target = Targets.resolve(args[1]);
        if (target == null) {
            messages.send(sender, "economy.target-unknown", Placeholder.unparsed("player", args[1]));
            return;
        }
        OptionalDouble parsed = Money.parse(args[2]);
        boolean zeroAllowed = sub.equals("set");
        if (parsed.isEmpty() || parsed.getAsDouble() < 0 || (!zeroAllowed && parsed.getAsDouble() == 0)) {
            messages.send(sender, "economy.invalid-amount");
            return;
        }
        double amount = parsed.getAsDouble();
        String targetName = target.getName() != null ? target.getName() : args[1];

        EconomyBridge.Result result;
        switch (sub) {
            case "give" -> result = economy.deposit(target.getUniqueId(), amount);
            case "take" -> {
                Double balance = economy.balance(target.getUniqueId());
                if (balance != null && balance < amount) {
                    messages.send(sender, "economy.admin-take-insufficient",
                            Placeholder.unparsed("player", targetName),
                            Placeholder.unparsed("balance", economy.format(balance)));
                    return;
                }
                result = economy.withdraw(target.getUniqueId(), amount);
            }
            default -> result = economy.setBalance(target.getUniqueId(), amount);
        }
        if (!result.success()) {
            messages.send(sender, "economy.admin-failed", Placeholder.unparsed("error",
                    result.error() == null ? "unknown" : result.error()));
            return;
        }
        Double balance = economy.balance(target.getUniqueId());
        messages.send(sender, "economy.admin-" + sub, Placeholder.unparsed("player", targetName),
                Placeholder.unparsed("amount", economy.format(amount)),
                Placeholder.unparsed("balance", balance == null ? "?" : economy.format(balance)));
        plugin.getLogger().info(sender.getName() + " ran veconomy " + sub + " " + Money.plain(amount) + " VYR on "
                + targetName + " (balance now " + (balance == null ? "?" : Money.plain(balance)) + ")");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pending")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("list", "paid", "retry").stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pending") && args[1].equalsIgnoreCase("retry")) {
            return List.of("confirm");
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("pending") && !args[0].equalsIgnoreCase("reload")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }
}
