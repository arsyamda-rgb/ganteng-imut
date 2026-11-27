package com.veyrion.character.command;

import com.veyrion.character.config.Messages;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.quest.PlayerQuests;
import com.veyrion.character.quest.PlayerQuests.ActiveQuest;
import com.veyrion.character.quest.Quest;
import com.veyrion.character.quest.QuestObjective;
import com.veyrion.character.quest.QuestService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /quest [active | abandon [quest_id]]}. */
public final class QuestCommand implements TabExecutor {

    private static final String PERMISSION = "veyrion.quest.use";

    private final VeyrionCharacterPlugin plugin;
    private final QuestService service;

    public QuestCommand(VeyrionCharacterPlugin plugin, QuestService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "quest.players-only");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            messages.send(player, "command.no-permission");
            return true;
        }

        String sub = args.length == 0 ? "active" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "active" -> showActive(player);
            case "abandon" -> abandon(player, label, args);
            default -> messages.send(player, "quest.usage", Placeholder.unparsed("label", label));
        }
        return true;
    }

    private void showActive(Player player) {
        Messages messages = plugin.messages();
        PlayerQuests state = service.state(player.getUniqueId());
        if (state == null) {
            messages.send(player, "quest.state-loading");
            return;
        }
        if (state.active().isEmpty()) {
            messages.send(player, "quest.none-active");
            return;
        }
        player.sendMessage(messages.get("quest.header"));
        for (ActiveQuest active : state.active()) {
            Quest quest = service.registry().get(active.questId());
            if (quest == null) {
                player.sendMessage(messages.get("quest.removed-line", Placeholder.unparsed("id", active.questId())));
                continue;
            }
            player.sendMessage(messages.get("quest.active-line",
                    Placeholder.parsed("name", quest.name()), Placeholder.unparsed("id", quest.id())));
            for (QuestObjective objective : quest.objectives()) {
                player.sendMessage(messages.get("quest.objective-line",
                        Placeholder.parsed("description", objective.description()),
                        Placeholder.unparsed("progress", Integer.toString(active.progress(objective.id()))),
                        Placeholder.unparsed("amount", Integer.toString(objective.amount()))));
            }
        }
    }

    private void abandon(Player player, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!plugin.configManager().settings().quests().allowAbandon()) {
            messages.send(player, "quest.abandon-disabled");
            return;
        }
        PlayerQuests state = service.state(player.getUniqueId());
        if (state == null) {
            messages.send(player, "quest.state-loading");
            return;
        }

        String questId;
        if (args.length >= 2) {
            questId = args[1].toLowerCase(Locale.ROOT);
        } else if (state.active().size() == 1) {
            questId = state.active().iterator().next().questId();
        } else {
            messages.send(player, "quest.abandon-usage", Placeholder.unparsed("label", label));
            return;
        }
        if (!state.isActive(questId)) {
            messages.send(player, "quest.not-active");
            return;
        }
        service.abandon(player, questId, ok -> {
            if (ok) {
                plugin.messages().send(player, "quest.abandoned", Placeholder.unparsed("id", questId));
            } else {
                plugin.messages().send(player, "quest.error");
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("active", "abandon").stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("abandon")) {
            PlayerQuests state = service.state(player.getUniqueId());
            if (state == null) {
                return List.of();
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return state.active().stream().map(ActiveQuest::questId).filter(id -> id.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
