package com.veyrion.character.api.event;

import com.veyrion.character.api.NpcInfo;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread when a player right-clicks a built-in NPC (not for the admin editor, which opens on
 * Shift + Right Click). Cancel it to stop the NPC's own reaction (dialogue, quest offer, TALK_TO_NPC progress).
 */
public final class NpcInteractEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final NpcInfo npc;
    private boolean cancelled;

    public NpcInteractEvent(Player player, NpcInfo npc) {
        this.player = player;
        this.npc = npc;
    }

    public Player player() {
        return player;
    }

    public NpcInfo npc() {
        return npc;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
