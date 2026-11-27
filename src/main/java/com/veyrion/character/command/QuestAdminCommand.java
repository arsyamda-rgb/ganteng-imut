package com.veyrion.character.command;

import com.veyrion.character.config.Messages;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.quest.PlayerQuests;
import com.veyrion.character.quest.Quest;
import com.veyrion.character.quest.QuestAvailability;
import com.veyrion.character.quest.QuestObjective;
import com.veyrion.character.quest.QuestService;
import com.veyrion.character.util.Durations;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /questadmin reload | give | reset | complete | list | info | progress}. */
public final class QuestAdminCommand implements TabExecutor {

    private static final String PERMISSION = "veyrion.quest.admin";
    private static final List<String> SUBCOMMANDS =
            List.of("reload", "give", "reset", "complete", "list", "info", "progress");

    private final VeyrionCharacterPlugin plugin;
    private final QuestService service;

    public QuestAdminCommand(VeyrionCharacterPlugin plugin, QuestService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> messages.send(sender, "admin.reloaded",
                    Placeholder.unparsed("count", Integer.toString(service.registry().reload())));
            case "list" -> list(sender);
            case "info" -> info(sender, label, args);
            case "give" -> give(sender, label, args);
            case "reset" -> reset(sender, label, args);
            case "complete" -> complete(sender, label, args);
            case "progress" -> progress(sender, label, args);
            default -> messages.send(sender, "admin.usage", Placeholder.unparsed("label", label));
        }
        return true;
    }

    private void list(CommandSender sender) {
        Messages messages = plugin.messages();
        sender.sendMessage(messages.get("admin.list-header",
                Placeholder.unparsed("count", Integer.toString(service.registry().all().size()))));
        for (Quest quest : service.registry().all()) {
            sender.sendMessage(messages.get("admin.list-line",
                    Placeholder.unparsed("id", quest.id()),
                    Placeholder.unparsed("type", quest.type().name()),
                    Placeholder.parsed("name", quest.name())));
        }
    }

    private void info(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length < 2) {
            messages.send(sender, "admin.usage", Placeholder.unparsed("label", label));
            return;
        }
        Quest quest = service.registry().get(args[1]);
        if (quest == null) {
            messages.send(sender, "quest.unknown", Placeholder.unparsed("id", args[1]));
            return;
        }
        sender.sendMessage(messages.get("admin.info-header",
                Placeholder.unparsed("id", quest.id()), Placeholder.unparsed("type", quest.type().name())));
        infoLine(sender, "Name", quest.name(), true);
        infoLine(sender, "Description", quest.description(), true);
        infoLine(sender, "Cooldown", Durations.format(quest.cooldownSeconds()), false);
        infoLine(sender, "Required rank", quest.requiredRank() == null ? "-" : quest.requiredRank(), false);
        infoLine(sender, "Prerequisites", quest.prerequisites().isEmpty() ? "-" : String.join(", ", quest.prerequisites()), false);
        infoLine(sender, "Rewards", quest.expReward() + " EXP, " + quest.vyrReward() + " VYR", false);
        for (QuestObjective objective : quest.objectives()) {
            sender.sendMessage(messages.get("admin.info-objective",
                    Placeholder.unparsed("id", objective.id()),
                    Placeholder.unparsed("type", objective.type().name()),
                    Placeholder.unparsed("amount", Integer.toString(objective.amount())),
                    Placeholder.parsed("description", objective.description())));
        }
    }

    private void infoLine(CommandSender sender, String label, String value, boolean parsed) {
        TagResolver valueResolver = parsed ? Placeholder.parsed("value", value) : Placeholder.unparsed("value", value);
        sender.sendMessage(plugin.messages().get("admin.info-line", Placeholder.unparsed("label", label), valueResolver));
    }

    private void give(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        Player target = onlineTarget(sender, label, args, 3);
        Quest quest = target == null ? null : questArg(sender, args[2]);
        if (target == null || quest == null) {
            return;
        }
        service.accept(target, quest, true, result -> {
            service.sendAcceptResult(target, quest, result);
            messages.send(sender, result == QuestAvailability.AVAILABLE ? "admin.given" : "admin.give-failed",
                    Placeholder.unparsed("id", quest.id()), Placeholder.unparsed("player", target.getName()));
        });
    }

    private void complete(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        Player target = onlineTarget(sender, label, args, 3);
        Quest quest = target == null ? null : questArg(sender, args[2]);
        if (target == null || quest == null) {
            return;
        }
        service.complete(target, quest, ok -> messages.send(sender, ok ? "admin.completed" : "admin.not-active",
                Placeholder.unparsed("id", quest.id()), Placeholder.unparsed("player", target.getName())));
    }

    private void reset(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length < 3) {
            messages.send(sender, "admin.usage", Placeholder.unparsed("label", label));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            messages.send(sender, "command.player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        Quest quest = questArg(sender, args[2]);
        if (quest == null) {
            return;
        }
        UUID uuid = target.getUniqueId();
        service.reset(uuid, quest.id(), ok -> messages.send(sender, ok ? "admin.reset" : "quest.error",
                Placeholder.unparsed("id", quest.id()), Placeholder.unparsed("player", args[1])));
    }

    private void progress(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        Player target = onlineTarget(sender, label, args, 4);
        Quest quest = target == null ? null : questArg(sender, args[2]);
        if (target == null || quest == null) {
            return;
        }
        String objectiveId = args[3];
        if (quest.objectives().stream().noneMatch(objective -> objective.id().equals(objectiveId))) {
            messages.send(sender, "admin.unknown-objective",
                    Placeholder.unparsed("id", quest.id()), Placeholder.unparsed("objective", objectiveId));
            return;
        }
        PlayerQuests state = service.state(target.getUniqueId());
        if (state == null || !state.isActive(quest.id())) {
            messages.send(sender, "admin.not-active",
                    Placeholder.unparsed("id", quest.id()), Placeholder.unparsed("player", target.getName()));
            return;
        }
        int amount = 1;
        if (args.length >= 5) {
            try {
                amount = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount <= 0) {
                messages.send(sender, "command.invalid-amount");
                return;
            }
        }
        service.progress(target, objective -> objective.id().equals(objectiveId), amount);
        messages.send(sender, "admin.progressed", Placeholder.unparsed("id", quest.id()),
                Placeholder.unparsed("objective", objectiveId), Placeholder.unparsed("player", target.getName()));
    }

    /** Resolves args[1] to an online player, or sends usage/offline messages and returns null. */
    private Player onlineTarget(CommandSender sender, String label, String[] args, int minArgs) {
        Messages messages = plugin.messages();
        if (args.length < minArgs) {
            messages.send(sender, "admin.usage", Placeholder.unparsed("label", label));
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "admin.player-offline", Placeholder.unparsed("player", args[1]));
        }
        return target;
    }

    private Quest questArg(CommandSender sender, String id) {
        Quest quest = service.registry().get(id);
        if (quest == null) {
            plugin.messages().send(sender, "quest.unknown", Placeholder.unparsed("id", id));
        }
        return quest;
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
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean playerFirst = List.of("give", "reset", "complete", "progress").contains(sub);
        if (args.length == 2 && playerFirst) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        boolean questAt = (args.length == 2 && sub.equals("info")) || (args.length == 3 && playerFirst);
        if (questAt) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return service.registry().all().stream().map(Quest::id).filter(id -> id.startsWith(prefix)).toList();
        }
        if (args.length == 4 && sub.equals("progress")) {
            Quest quest = service.registry().get(args[2]);
            return quest == null ? List.of() : quest.objectives().stream().map(QuestObjective::id)
                    .filter(id -> id.startsWith(args[3].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
