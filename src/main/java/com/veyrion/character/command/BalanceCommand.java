package com.veyrion.character.command;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.economy.EconomyService;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /veyra}: shows the player's Veyra balance, read from the Vault economy provider. */
public final class BalanceCommand implements TabExecutor {

    private static final String PERMISSION = "veyrion.economy.balance";

    private final VeyrionCharacterPlugin plugin;
    private final EconomyService economy;

    public BalanceCommand(VeyrionCharacterPlugin plugin, EconomyService economy) {
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
        if (!economy.settings().balanceCommand()) {
            messages.send(player, "economy.command-disabled");
            return true;
        }
        String unavailable = economy.unavailableKey();
        if (unavailable != null) {
            messages.send(player, unavailable);
            return true;
        }
        Double balance = economy.balance(player.getUniqueId());
        if (balance == null) {
            messages.send(player, "economy.balance-error");
            return true;
        }
        messages.send(player, "economy.balance", Placeholder.unparsed("amount", economy.format(balance)),
                Placeholder.unparsed("currency", economy.settings().currencyName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
