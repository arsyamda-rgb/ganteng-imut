package com.veyrion.character.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread after Adventure EXP was added and stored (command, quest reward, API). Not cancellable:
 * the EXP is already saved.
 */
public final class CharacterExpGainEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final long gained;
    private final long oldExp;
    private final long newExp;

    public CharacterExpGainEvent(UUID player, long gained, long oldExp, long newExp) {
        this.player = player;
        this.gained = gained;
        this.oldExp = oldExp;
        this.newExp = newExp;
    }

    public UUID player() {
        return player;
    }

    public long gained() {
        return gained;
    }

    public long oldExp() {
        return oldExp;
    }

    public long newExp() {
        return newExp;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
