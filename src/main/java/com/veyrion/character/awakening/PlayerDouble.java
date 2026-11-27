package com.veyrion.character.awakening;

import org.bukkit.entity.Player;

/**
 * A fake copy of a player (same skin, same gear) that exists only as packets on the clients it was shown to.
 * The cinematic needs it because the real player is a spectator attached to the camera and so is invisible.
 * No ProtocolLib types here, so the awakening package never links to ProtocolLib. Main thread only.
 */
public interface PlayerDouble {

    /** Shows the double to {@code viewer}. Idempotent. */
    void show(Player viewer);

    /** Turns the double (body and head) to this facing, in degrees. Does nothing for viewers it is not shown to. */
    default void look(float yaw, float pitch) {
    }

    /** Removes the double from every viewer and releases everything it holds. Idempotent. */
    void remove();
}
