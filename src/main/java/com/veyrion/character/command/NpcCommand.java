package com.veyrion.character.command;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.npc.NpcData;
import com.veyrion.character.npc.NpcEditor;
import com.veyrion.character.npc.NpcManager;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /vnpc create|edit|tp|remove|list|reload}. Everything else about an NPC is done in the editor GUI. */
public final class NpcCommand implements TabExecutor {

    private static final String PERMISSION = NpcManager.ADMIN_PERMISSION;
    private static final List<String> SUBCOMMANDS = List.of("create", "edit", "tp", "remove", "list", "reload");

    private final VeyrionCharacterPlugin plugin;
    private final NpcManager npcs;
    private final NpcEditor editor;

    public NpcCommand(VeyrionCharacterPlugin plugin, NpcManager npcs, NpcEditor editor) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.editor = editor;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            plugin.messages().send(sender, "command.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(sender, label, args);
            case "edit" -> withPlayerAndNpc(sender, label, args, (player, data) -> editor.open(player, data.id()));
            case "tp" -> withPlayerAndNpc(sender, label, args, (player, data) -> {
                org.bukkit.World world = plugin.getServer().getWorld(data.world());
                if (world == null) {
                    plugin.messages().send(player, "npc.world-missing", Placeholder.unparsed("world", data.world()));
                    return;
                }
                player.teleport(new Location(world, data.x(), data.y(), data.z(), data.yaw(), data.pitch()));
                plugin.messages().send(player, "npc.teleported", Placeholder.unparsed("id", data.id()));
            });
            case "remove" -> remove(sender, label, args);
            case "list" -> list(sender);
            case "reload" -> {
                npcs.reload();
                plugin.messages().send(sender, "npc.reloaded", Placeholder.unparsed("count",
                        Integer.toString(npcs.all().size())));
            }
            default -> plugin.messages().send(sender, "npc.usage", Placeholder.unparsed("label", label));
        }
        return true;
    }

    private void create(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "quest.players-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "npc.usage", Placeholder.unparsed("label", label));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        String name = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : args[1];
        if (name.length() > NpcData.MAX_NAME_LENGTH) {
            plugin.messages().send(sender, "npc.too-long", Placeholder.unparsed("max",
                    Integer.toString(NpcData.MAX_NAME_LENGTH)));
            return;
        }
        NpcManager.CreateResult result = npcs.create(id, name, player.getLocation());
        switch (result) {
            case CREATED -> {
                plugin.messages().send(sender, "npc.created", Placeholder.unparsed("id", id));
                if (!npcs.bodiesAvailable()) {
                    plugin.messages().send(sender, "npc.no-protocollib");
                }
            }
            case INVALID_ID -> plugin.messages().send(sender, "npc.invalid-id");
            case EXISTS -> plugin.messages().send(sender, "npc.exists", Placeholder.unparsed("id", id));
            case TOO_MANY -> plugin.messages().send(sender, "npc.too-many", Placeholder.unparsed("max",
                    Integer.toString(NpcManager.MAX_NPCS)));
            case UNAVAILABLE -> plugin.messages().send(sender, "npc.storage-error");
        }
    }

    private interface PlayerAction {
        void run(Player player, NpcData data);
    }

    private void withPlayerAndNpc(CommandSender sender, String label, String[] args, PlayerAction action) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "quest.players-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "npc.usage", Placeholder.unparsed("label", label));
            return;
        }
        NpcData data = npcs.get(args[1]);
        if (data == null) {
            plugin.messages().send(sender, "npc.unknown", Placeholder.unparsed("id", args[1]));
            return;
        }
        action.run(player, data);
    }

    private void remove(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "npc.usage", Placeholder.unparsed("label", label));
            return;
        }
        if (npcs.delete(args[1])) {
            plugin.messages().send(sender, "npc.deleted", Placeholder.unparsed("id", args[1].toLowerCase(Locale.ROOT)));
        } else {
            plugin.messages().send(sender, "npc.unknown", Placeholder.unparsed("id", args[1]));
        }
    }

    private void list(CommandSender sender) {
        List<NpcData> all = npcs.all();
        if (all.isEmpty()) {
            plugin.messages().send(sender, "npc.list-empty");
            return;
        }
        plugin.messages().send(sender, "npc.list-header", Placeholder.unparsed("count", Integer.toString(all.size())));
        for (NpcData data : all) {
            sender.sendMessage(plugin.messages().get("npc.list-line",
                    Placeholder.unparsed("id", data.id()),
                    Placeholder.unparsed("world", data.world()),
                    Placeholder.unparsed("x", Long.toString(Math.round(data.x()))),
                    Placeholder.unparsed("y", Long.toString(Math.round(data.y()))),
                    Placeholder.unparsed("z", Long.toString(Math.round(data.z()))),
                    Placeholder.unparsed("quest", data.questId() == null ? "-" : data.questId()),
                    Placeholder.unparsed("state", data.visible() ? "visible" : "hidden")));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && List.of("edit", "tp", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            return npcs.ids().stream().filter(id -> id.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
