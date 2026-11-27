package com.veyrion.character.awakening;

/**
 * Pure geometry of the Ancient Core: where the orbiting runes are and how an energy mote travels from a pillar to
 * the crystal. No Bukkit, so it can be checked without a server (see dev-checks/CoreMathCheck.java).
 * Every point is relative to the centre of the crystal; results are {@code {x, y, z}}.
 */
final class CoreMath {

    static final double TAU = Math.PI * 2.0;

    private CoreMath() {
    }

    /**
     * Position of rune {@code index} of {@code count} on a tilted circle around the crystal.
     *
     * @param angle  current turn of the whole ring (radians); the runes keep an even spacing
     * @param radius ring radius in blocks
     * @param tilt   ring tilt around the X axis (radians)
     */
    static double[] orbit(int index, int count, double angle, double radius, double tilt) {
        double a = angle + TAU * index / Math.max(1, count);
        double x = Math.cos(a) * radius;
        double z = Math.sin(a) * radius;
        // Rotate (x, 0, z) around X by 'tilt'.
        double y = -z * Math.sin(tilt);
        double z2 = z * Math.cos(tilt);
        return new double[] {x, y, z2};
    }

    /**
     * A point on the way from a pillar to the crystal. {@code f} = 0 is the source, 1 is the crystal.
     * The path eases in (slow at the pillar, fast at the end), lifts into a small arc and curls a little
     * around the vertical axis (only in between) so the streams look like flowing energy and not like straight rods.
     *
     * @param sx source x relative to the crystal; sy, sz likewise
     * @param arc extra height (blocks) in the middle of the trip
     * @param curl radians of sideways turn in the middle of the trip (0 at the pillar and at the crystal, so a stream
     *             always leaves exactly from the gem)
     */
    static double[] flow(double sx, double sy, double sz, double f, double arc, double curl) {
        double ff = PillarMath.clamp01(f);
        double k = Math.pow(ff, 1.6);
        double rest = 1.0 - k;
        double turn = curl * Math.sin(Math.PI * ff);
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        double x = (sx * cos - sz * sin) * rest;
        double z = (sx * sin + sz * cos) * rest;
        double y = sy * rest + Math.sin(Math.PI * ff) * arc;
        return new double[] {x, y, z};
    }

    /** Size factor of a mote along its trip: small at the start, fullest in the middle, tiny at the crystal. */
    static double flowSize(double f) {
        double ff = PillarMath.clamp01(f);
        return Math.sin(Math.PI * ff) * 0.85 + 0.15 * (1.0 - ff);
    }

    /** Vertical bob of the crystal; {@code amplitude} blocks, one cycle every {@code period} ticks. */
    static double bob(double time, double period, double amplitude) {
        return Math.sin(time * TAU / Math.max(1.0, period)) * amplitude;
    }
}
