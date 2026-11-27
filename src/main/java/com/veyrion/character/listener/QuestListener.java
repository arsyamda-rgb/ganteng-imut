package com.veyrion.character.listener;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.quest.ObjectiveType;
import com.veyrion.character.quest.QuestService;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Feeds Bukkit events into the quest system. */
public final class QuestListener implements Listener {

    private final VeyrionCharacterPlugin plugin;
    private final QuestService quests;

    public QuestListener(VeyrionCharacterPlugin plugin, QuestService quests) {
        this.plugin = plugin;
        this.quests = quests;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        quests.load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        quests.unload(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        EntityType type = event.getEntity().getType();
        quests.progress(killer, objective -> objective.type() == ObjectiveType.KILL_MOB
                && objective.entity() == type, 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            refreshNextTick(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        refreshNextTick(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refreshNextTick(player);
        }
    }

    /** The inventory is updated after the event, so recount on the next tick. */
    private void refreshNextTick(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> quests.refreshCollect(player));
    }
}
