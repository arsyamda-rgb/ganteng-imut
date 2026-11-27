package com.veyrion.character.command;

import com.veyrion.character.character.CharacterData;
import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.character.ExpService;
import com.veyrion.character.config.Messages;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /veyrioncharacter info [player]} and {@code /veyrioncharacter reload}. */
public final class CharacterCommand implements TabExecutor {

    private static final String PERMISSION_INFO = "veyrion.character.info";
    private static final String PERMISSION_ADMIN = "veyrion.character.admin";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final VeyrionCharacterPlugin plugin;
    private final CharacterRepository repository;
    private final ExpService expService;

    public CharacterCommand(VeyrionCharacterPlugin plugin, CharacterRepository repository, ExpService expService) {
        this.plugin = plugin;
        this.repository = repository;
        this.expService = expService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length == 0) {
            messages.send(sender, "command.usage", Placeholder.unparsed("label", label));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "info" -> info(sender, label, args);
            case "addexp" -> addExp(sender, label, args);
            default -> messages.send(sender, "command.usage", Placeholder.unparsed("label", label));
        }
        return true;
    }

    private void reload(CommandSender sender) {
        Messages messages = plugin.messages();
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            messages.send(sender, "command.no-permission");
            return;
        }
        var databaseBefore = plugin.configManager().settings().database();
        plugin.reloadSettings();
        messages = plugin.messages();
        messages.send(sender, "command.reload-success");
        if (!databaseBefore.equals(plugin.configManager().settings().database())) {
            messages.send(sender, "command.reload-database-note");
        }
    }

    private void info(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!sender.hasPermission(PERMISSION_INFO)) {
            messages.send(sender, "command.no-permission");
            return;
        }

        UUID uuid;
        String name;
        if (args.length >= 2) {
            // Looking up someone else (or naming yourself explicitly): admin only, cached names only, no blocking lookup.
            if (!sender.hasPermission(PERMISSION_ADMIN)) {
                messages.send(sender, "command.no-permission");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                messages.send(sender, "command.player-not-found", Placeholder.unparsed("player", args[1]));
                return;
            }
            uuid = target.getUniqueId();
            name = target.getName() != null ? target.getName() : args[1];
        } else if (sender instanceof Player player) {
            uuid = player.getUniqueId();
            name = player.getName();
        } else {
            messages.send(sender, "command.console-needs-player", Placeholder.unparsed("label", label));
            return;
        }

        String displayName = name;
        repository.find(uuid).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            // Hop back to the main thread before using the Bukkit API.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to read character for " + displayName, error);
                    plugin.messages().send(sender, "command.data-error");
                } else if (result.isEmpty()) {
                    plugin.messages().send(sender, "command.no-character",
                            Placeholder.unparsed("player", displayName));
                } else {
                    sendInfo(sender, result.get());
                }
            });
        });
    }

    private void addExp(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            messages.send(sender, "command.no-permission");
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "command.addexp-usage", Placeholder.unparsed("label", label));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            messages.send(sender, "command.player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        long parsed;
        try {
            parsed = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            parsed = -1;
        }
        if (parsed <= 0) {
            messages.send(sender, "command.invalid-amount");
            return;
        }

        UUID uuid = target.getUniqueId();
        long amount = parsed;
        String targetName = args[1];
        expService.grant(uuid, amount).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to add EXP for " + targetName, error);
                    plugin.messages().send(sender, "command.data-error");
                    return;
                }
                plugin.announceExp(uuid, result, amount);
                plugin.messages().send(sender, "command.addexp-success",
                        Placeholder.unparsed("amount", Long.toString(amount)),
                        Placeholder.unparsed("player", targetName),
                        Placeholder.unparsed("rank", result.newRank()));
            });
        });
    }

    private void sendInfo(CommandSender sender, CharacterData data) {
        Messages messages = plugin.messages();
        sender.sendMessage(messages.get("info.header", Placeholder.unparsed("player", data.playerName())));
        sender.sendMessage(messages.get("info.race", Placeholder.component("value",
                data.hasRace() ? plugin.races().settings().display(data.race()) : messages.get("value.none"))));
        sender.sendMessage(messages.get("info.exp", Placeholder.unparsed("value", Long.toString(data.adventureExp()))));
        sender.sendMessage(messages.get("info.rank", Placeholder.unparsed("value", data.adventureRank())));
        sender.sendMessage(messages.get("info.gacha", Placeholder.component("value",
                messages.get(data.hasCompletedGacha() ? "value.affirmative" : "value.negative"))));
        sender.sendMessage(messages.get("info.created",
                Placeholder.unparsed("value", DATE_FORMAT.format(data.createdAt()))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("info", "addexp", "reload").stream()
                    .filter(option -> option.equals("info")
                            ? sender.hasPermission(PERMISSION_INFO)
                            : sender.hasPermission(PERMISSION_ADMIN))
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("addexp"))
                && sender.hasPermission(PERMISSION_ADMIN)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(playerName -> playerName.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
