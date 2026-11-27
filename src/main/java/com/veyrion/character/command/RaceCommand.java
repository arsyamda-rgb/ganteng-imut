package com.veyrion.character.command;

import com.veyrion.character.character.CharacterData;
import com.veyrion.character.config.Messages;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.race.RaceDefinition;
import com.veyrion.character.race.RaceService;
import com.veyrion.character.race.RollResult;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /race [info]} and {@code /race roll}. */
public final class RaceCommand implements TabExecutor {

    private static final String PERMISSION = "veyrion.race.use";

    private final VeyrionCharacterPlugin plugin;
    private final RaceService races;

    public RaceCommand(VeyrionCharacterPlugin plugin, RaceService races) {
        this.plugin = plugin;
        this.races = races;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "race.players-only");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            messages.send(player, "command.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(player);
            case "roll" -> roll(player);
            case "skip" -> plugin.awakening().skip(player);
            default -> messages.send(player, "race.usage", Placeholder.unparsed("label", label));
        }
        return true;
    }

    private void info(Player player) {
        races.character(player.getUniqueId()).whenComplete((result, error) -> {
            Messages messages = plugin.messages();
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to read character for " + player.getName(), error);
                messages.send(player, "command.data-error");
                return;
            }
            if (result.isEmpty()) {
                messages.send(player, "race.no-character");
                return;
            }
            sendInfo(player, result.get());
        });
    }

    private void sendInfo(Player player, CharacterData data) {
        Messages messages = plugin.messages();
        var settings = races.registry().settings();
        player.sendMessage(messages.get("race.info-header"));
        Component raceName = data.hasRace() ? settings.display(data.race()) : messages.get("value.none");
        player.sendMessage(messages.get("race.info-race", Placeholder.component("value", raceName)));
        if (data.hasRace()) {
            RaceDefinition definition = settings.find(data.race());
            if (definition != null && !definition.description().isEmpty()) {
                player.sendMessage(messages.get("race.info-description",
                        Placeholder.unparsed("description", definition.description())));
            }
        }
        player.sendMessage(messages.get("race.info-gacha", Placeholder.component("value",
                messages.get(data.hasCompletedGacha() ? "value.affirmative" : "value.negative"))));
        if (!data.hasCompletedGacha()) {
            player.sendMessage(messages.get("race.info-hint"));
        }
    }

    private void roll(Player player) {
        if (plugin.awakening() != null && !plugin.awakening().commandRollAllowed()) {
            plugin.awakening().send(player, "roll-disabled");
            return;
        }
        races.roll(player.getUniqueId()).whenComplete((result, error) -> {
            Messages messages = plugin.messages();
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to roll race for " + player.getName(), error);
                messages.send(player, "command.data-error");
                return;
            }
            switch (result.status()) {
                case ASSIGNED -> messages.send(player, "race.roll-success",
                        Placeholder.component("race", races.registry().settings().display(result.race())),
                        Placeholder.unparsed("chance", String.format(Locale.ROOT, "%.1f", result.chance() * 100)));
                case ALREADY_ROLLED -> messages.send(player, "race.roll-already");
                case NO_CHARACTER -> messages.send(player, "race.no-character");
                case NO_RACES -> messages.send(player, "race.no-races");
                case IN_PROGRESS -> messages.send(player, "race.roll-in-progress");
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission(PERMISSION)) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("info", "roll", "skip").stream().filter(option -> option.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
