package com.veyrion.character.command;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.economy.BalTop;
import com.veyrion.character.economy.EconomyService;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** {@code /topuang}: the 10 richest players, from a cached, time-sliced read of the economy provider. */
public final class BalTopCommand implements TabExecutor {

    private static final String PERMISSION = "veyrion.economy.baltop";

    private final VeyrionCharacterPlugin plugin;
    private final EconomyService economy;
    private final BalTop balTop;

    public BalTopCommand(VeyrionCharacterPlugin plugin, EconomyService economy, BalTop balTop) {
        this.plugin = plugin;
        this.economy = economy;
        this.balTop = balTop;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var messages = plugin.messages();
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (!economy.settings().baltopCommand()) {
            messages.send(sender, "economy.command-disabled");
            return true;
        }
        String unavailable = economy.unavailableKey();
        if (unavailable != null) {
            messages.send(sender, unavailable);
            return true;
        }
        if (!balTop.isFresh()) {
            messages.send(sender, "economy.baltop-loading");
        }
        balTop.request(result -> {
            var current = plugin.messages();
            if (!result.ok()) {
                current.send(sender, "economy.baltop-error");
                return;
            }
            if (result.entries().isEmpty()) {
                current.send(sender, "economy.baltop-empty");
                return;
            }
            sender.sendMessage(current.get("economy.baltop-header",
                    Placeholder.unparsed("currency", economy.settings().currencyName())));
            int rank = 1;
            for (BalTop.Entry entry : result.entries()) {
                sender.sendMessage(current.get("economy.baltop-line",
                        Placeholder.unparsed("rank", Integer.toString(rank++)),
                        Placeholder.unparsed("player", entry.name()),
                        Placeholder.unparsed("amount", economy.format(entry.balance()))));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
