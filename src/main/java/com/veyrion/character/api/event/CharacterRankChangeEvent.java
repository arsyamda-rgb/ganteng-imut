package com.veyrion.character.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired on the main thread after a gain of EXP changed the Adventure Rank (already stored). Not cancellable. */
public final class CharacterRankChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String oldRank;
    private final String newRank;

    public CharacterRankChangeEvent(UUID player, String oldRank, String newRank) {
        this.player = player;
        this.oldRank = oldRank;
        this.newRank = newRank;
    }

    public UUID player() {
        return player;
    }

    public String oldRank() {
        return oldRank;
    }

    public String newRank() {
        return newRank;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
