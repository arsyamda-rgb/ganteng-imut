package com.veyrion.character.awakening;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * A vanilla sound addressed by its resource key (for example {@code block.beacon.activate}). The key is sent to
 * the client as a string, so no Sound enum is needed and unknown keys are simply silent.
 */
public record SoundSpec(String key, float volume, float pitch) {

    public static final SoundSpec NONE = new SoundSpec("", 0f, 1f);

    public boolean silent() {
        return key.isBlank() || volume <= 0f;
    }

    /** Plays only for {@code player}. {@code pitchFactor} multiplies the configured pitch (clamped to 0.5-2). */
    public void play(Player player, Location at, float pitchFactor) {
        if (silent()) {
            return;
        }
        float finalPitch = Math.max(0.5f, Math.min(2.0f, pitch * pitchFactor));
        player.playSound(at, key, SoundCategory.MASTER, volume, finalPitch);
    }

    public void play(Player player, Location at) {
        play(player, at, 1.0f);
    }

    /** Plays for everyone near {@code at} (block category), for effects all players should see and hear. */
    public void broadcast(Location at, float pitchFactor) {
        if (silent() || at.getWorld() == null) {
            return;
        }
        float finalPitch = Math.max(0.5f, Math.min(2.0f, pitch * pitchFactor));
        at.getWorld().playSound(at, key, SoundCategory.BLOCKS, volume, finalPitch);
    }
}
