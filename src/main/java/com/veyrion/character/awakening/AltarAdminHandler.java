package com.veyrion.character.awakening;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /raceadmin altar set | remove | info | reload | preview [race]}. The caller (RaceAdminCommand) has already checked the
 * {@code veyrion.race.admin} permission.
 */
public final class AltarAdminHandler {

    private static final List<String> SUBCOMMANDS = List.of("set", "remove", "info", "reload", "preview");

    private final AwakeningService service;

    public AltarAdminHandler(AwakeningService service) {
        this.service = service;
    }

    /** @param args the arguments after "altar" */
    public void handle(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> set(sender);
            case "remove" -> remove(sender);
            case "info" -> info(sender);
            case "preview" -> preview(sender, label, args);
            case "reload" -> {
                service.reload();
                service.send(sender, "admin.reloaded");
            }
            default -> service.send(sender, "admin.usage", Placeholder.unparsed("label", label));
        }
    }

    private void set(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            service.send(sender, "admin.players-only");
            return;
        }
        // The altar is the block under the admin's feet (or the block they stand inside if it is not passable,
        // such as a slab).
        Block feet = player.getLocation().getBlock();
        Block altar = feet.isPassable() ? feet.getRelative(BlockFace.DOWN) : feet;
        if (!altar.getType().isSolid()) {
            service.send(sender, "admin.set-no-block");
            return;
        }
        if (!service.altars().set(altar)) {
            service.send(sender, "admin.save-failed");
            return;
        }
        service.visual().rebuild();
        service.send(sender, "admin.set", location(altar.getWorld().getName(), altar.getX(), altar.getY(), altar.getZ()));
    }

    /** {@code altar preview [race]}: plays the cutscene for the admin without rolling or saving anything. */
    private void preview(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            service.send(sender, "admin.players-only");
            return;
        }
        String race = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "dragon";
        String failure = service.preview(player, race);
        if (failure != null) {
            service.send(sender, failure, Placeholder.unparsed("label", label), Placeholder.unparsed("race_name", race));
            return;
        }
        service.send(sender, "admin.preview-started", Placeholder.unparsed("race_name", race));
    }

    private void remove(CommandSender sender) {
        if (service.altars().get() == null) {
            service.send(sender, "admin.none");
            return;
        }
        if (service.altars().remove()) {
            service.visual().rebuild();
            service.send(sender, "admin.removed");
        } else {
            service.send(sender, "admin.save-failed");
        }
    }

    private void info(CommandSender sender) {
        AltarStore.Altar altar = service.altars().get();
        if (altar == null) {
            service.send(sender, "admin.none");
            return;
        }
        if (altar.world() == null) {
            service.send(sender, "admin.info-missing-world", Placeholder.unparsed("world", altar.worldName()));
            return;
        }
        service.send(sender, "admin.info",
                Placeholder.unparsed("world", altar.worldName()),
                Placeholder.unparsed("x", Integer.toString(altar.x())),
                Placeholder.unparsed("y", Integer.toString(altar.y())),
                Placeholder.unparsed("z", Integer.toString(altar.z())),
                Placeholder.unparsed("sessions", Integer.toString(service.runningRituals())));
        sender.sendMessage(net.kyori.adventure.text.Component.text("Pillars: " + service.visual().status(),
                net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }

    private static TagResolver location(String world, int x, int y, int z) {
        return TagResolver.resolver(
                Placeholder.unparsed("world", world),
                Placeholder.unparsed("x", Integer.toString(x)),
                Placeholder.unparsed("y", Integer.toString(y)),
                Placeholder.unparsed("z", Integer.toString(z)));
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("preview")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return service.raceIds().stream().filter(id -> id.startsWith(prefix)).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
