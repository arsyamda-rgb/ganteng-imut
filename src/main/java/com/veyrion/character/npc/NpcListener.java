package com.veyrion.character.npc;

import com.veyrion.character.api.event.NpcInteractEvent;
import com.veyrion.character.api.impl.ApiImpl;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.quest.ObjectiveType;
import com.veyrion.character.quest.Quest;
import com.veyrion.character.quest.QuestService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clicks on NPCs. Right Click = talk (dialogue, quest offer, TALK_TO_NPC progress); Shift + Right Click by an admin
 * = the editor. Also tells the manager when a player's client lost the NPC bodies (join, respawn, world change,
 * long teleport) so they are sent again at the next visibility pass (within one second).
 */
public final class NpcListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** One click can arrive twice (interact + interact-at); anything within this window is the same click. */
    private static final long DEBOUNCE_MS = 350;

    private final VeyrionCharacterPlugin plugin;
    private final NpcManager npcs;
    private final NpcEditor editor;
    private final QuestService quests;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public NpcListener(VeyrionCharacterPlugin plugin, NpcManager npcs, NpcEditor editor, QuestService quests) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.editor = editor;
        this.quests = quests;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        NpcData npc = npcs.byEntity(event.getRightClicked());
        if (npc == null) {
            return;
        }
        event.setCancelled(true);
        if (event instanceof PlayerInteractAtEntityEvent || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        boolean admin = NpcManager.isAdmin(player);
        if (!npc.visible() && !admin) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastClick.put(player.getUniqueId(), now);
        if (last != null && now - last < DEBOUNCE_MS) {
            return;
        }
        if (player.isSneaking() && admin) {
            editor.open(player, npc.id());
        } else {
            talk(player, npc);
        }
    }

    /** Left click on an NPC does nothing (the click box would otherwise be "damaged"). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (npcs.byEntity(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    private void talk(Player player, NpcData npc) {
        NpcInteractEvent event = new NpcInteractEvent(player, ApiImpl.info(npc));
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        // Progress first: a quest accepted by this very click must not count this click as its talk objective.
        quests.progress(player, objective -> objective.type() == ObjectiveType.TALK_TO_NPC
                && npc.id().equalsIgnoreCase(objective.npcId()), 1);

        Component name = MINI.deserialize(npc.displayName());
        for (String line : npc.dialogue()) {
            player.sendMessage(Component.text("[", NamedTextColor.GRAY).append(name)
                    .append(Component.text("] ", NamedTextColor.GRAY)).append(MINI.deserialize(line)));
        }
        if (npc.questId() == null) {
            return;
        }
        Quest quest = quests.registry().get(npc.questId());
        if (quest == null) {
            if (NpcManager.isAdmin(player)) {
                plugin.messages().send(player, "npc.quest-missing", Placeholder.unparsed("id", npc.id()),
                        Placeholder.unparsed("quest", npc.questId()));
            }
            return;
        }
        quests.accept(player, quest, false, result -> {
            if (player.isOnline()) {
                quests.sendAcceptResult(player, quest, result);
            }
        });
    }

    // ------------------------------------------------------------------------------- client lost the bodies

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        npcs.forget(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        npcs.forget(event.getPlayer());
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        npcs.forget(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        npcs.forget(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld() || from.distanceSquared(to) > 32.0 * 32.0) {
            npcs.forget(event.getPlayer());
        }
    }
}
