package com.veyrion.character.api;

import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * A visual-only copy of a player (see {@link CharacterPreviewFactory}). It is not an entity of the server: it
 * exists only as packets on the clients it was shown to, so it has no collision, cannot be hit or clicked, never
 * appears in {@code Bukkit.getOnlinePlayers()}, and never touches the subject's inventory, data or attributes.
 * Main thread only. Every method is safe to call more than once.
 */
public interface CharacterPreview {

    /** The player this preview copies. */
    UUID subject();

    /** False once {@link #remove()} was called (or the subject went offline during {@link #refreshSkin()}). */
    boolean isActive();

    /** Shows the preview to {@code viewer} only. Does nothing if it is already shown to them or not active. */
    void show(Player viewer);

    /**
     * Re-reads the subject's current skin and shows the preview again with it, for every viewer. Call it after the
     * subject's skin changed. If the subject is offline the preview is removed instead.
     */
    void refreshSkin();

    /** Turns the preview (body and head) to face {@code yaw}/{@code pitch}, in degrees, like a Location. */
    void look(float yaw, float pitch);

    /** Removes the preview from every viewer and releases everything it holds. */
    void remove();
}
