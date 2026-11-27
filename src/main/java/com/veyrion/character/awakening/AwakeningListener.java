package com.veyrion.character.awakening;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Bukkit events for the Rift Awakening. All logic lives in {@link AwakeningService}. */
public final class AwakeningListener implements Listener {

    private final VeyrionCharacterPlugin plugin;
    private final AwakeningService service;

    public AwakeningListener(VeyrionCharacterPlugin plugin, AwakeningService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * The crystal is the altar: a right click on its invisible click box opens the awakening menu. Runs even if
     * another plugin (a claim, for example) cancelled the click, so it also works inside protected spawn areas.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCrystalClick(PlayerInteractEntityEvent event) {
        if (!service.visual().isCrystalHitbox(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        // One click arrives as interact-at and as interact; only the plain one counts.
        if (event instanceof PlayerInteractAtEntityEvent || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        service.onAltarClick(event.getPlayer());
    }

    /** Hitting the click box does nothing. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCrystalHit(EntityDamageByEntityEvent event) {
        if (service.visual().isCrystalHitbox(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Fallback only: while the crystal does not exist (altar visuals or the core switched off, chunk not loaded),
     * the configured altar block itself still works as before. With a crystal the floor is NOT clickable.
     * Runs even if another plugin denied the click.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || service.visual().hasCrystal()) {
            return;
        }
        Block block = event.getClickedBlock();
        AltarStore.Altar altar = service.altars().get();
        if (block == null || altar == null || !altar.matches(block)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() == EquipmentSlot.HAND) {
            service.onAltarClick(event.getPlayer());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!AwakeningMenu.isMenu(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !AwakeningMenu.isMenu(event.getClickedInventory())) {
            return;
        }
        int slot = event.getSlot();
        if (slot == AwakeningMenu.SLOT_BEGIN) {
            // Next tick: never close or replace an inventory from inside its own click event.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                service.begin(player);
            });
        } else if (slot == AwakeningMenu.SLOT_CANCEL) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                service.send(player, "cancelled");
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (AwakeningMenu.isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        RitualSession session = service.session(event.getPlayer().getUniqueId());
        if (session == null || !session.locksMovement()) {
            return;
        }
        // Keep the head free: restore only the position.
        Location from = event.getFrom();
        Location to = event.getTo();
        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        RitualSession session = service.session(player.getUniqueId());
        if (session != null && session.protectsFromDamage()) {
            event.setCancelled(true);
        }
    }

    /** Shift while the cutscene camera runs: the camera stays, and the cutscene is skipped (after a short grace). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        if (service.onStopSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        service.onDeath(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.onQuit(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        service.onWorldUnload(event.getWorld());
    }

    // ------------------------------------------------------------------------------------ altar pillars

    /** Final position after other handlers (ritual lock, claims) had their say. Only block changes matter. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPillarMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock()) {
            service.visual().track(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPillarTeleport(PlayerTeleportEvent event) {
        service.visual().track(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPillarWorldChange(PlayerChangedWorldEvent event) {
        service.visual().track(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPillarRespawn(PlayerRespawnEvent event) {
        // The player still counts as dead inside this event, so look again on the next tick.
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                service.visual().track(player, player.getLocation());
            }
        });
    }

    /** Players do not fire move events while riding, so the vehicle's move stands in for them. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPillarVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle().isEmpty()
                || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ())) {
            return;
        }
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                service.visual().track(player, event.getTo());
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        service.visual().onChunkLoad(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        service.visual().onChunkUnload(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }
}
