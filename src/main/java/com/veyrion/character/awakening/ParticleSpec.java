package com.veyrion.character.awakening;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Particle;
import org.jetbrains.annotations.Nullable;

/**
 * One vanilla particle configuration.
 *
 * @param type   particle; only particles without extra data, plus DUST (coloured), are supported
 * @param count  particles per drawn point
 * @param spread random offset per point, in blocks
 * @param speed  particle "extra" value (speed for most particles)
 * @param color  DUST colour; {@code null} means "use the race colour"
 * @param size   DUST size
 */
public record ParticleSpec(Particle type, int count, double spread, double speed, @Nullable TextColor color,
                           float size) {

    public static final ParticleSpec NONE = new ParticleSpec(Particle.END_ROD, 0, 0, 0, null, 1f);

    public boolean disabled() {
        return count <= 0;
    }

    public boolean isDust() {
        return type == Particle.DUST;
    }

    /** True for particle types this plugin can spawn without extra data. */
    public static boolean supported(Particle particle) {
        Class<?> data = particle.getDataType();
        return data == Void.class || particle == Particle.DUST;
    }
}
