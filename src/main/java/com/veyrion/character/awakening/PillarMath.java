package com.veyrion.character.awakening;

/**
 * Pure maths of the altar visuals (no Bukkit, no JOML), so it can be checked without a server.
 *
 * <p>The "floating plates" bug came from {@link #hidden}: a shaft segment with no visible height used to be
 * drawn with a tiny height but a full width, which is a paper-thin plate hanging in the air. Hidden segments are
 * now scaled to zero on every axis (see {@code StonePillar}).
 */
final class PillarMath {

    static final double HIDE_EPS = 1e-4;

    private PillarMath() {
    }

    /** How many stacked shaft segments cover a pillar of height {@code tall}. */
    static int segmentCount(double tall, double segH) {
        return Math.max(1, (int) Math.ceil(tall / segH - 1e-9));
    }

    /** Visible height (0..segH) of segment {@code k} when the shaft is {@code height} tall. */
    static double visible(double height, int k, double segH) {
        return Math.max(0.0, Math.min(segH, height - k * segH));
    }

    /** True when a segment has nothing to show and must be scaled to zero, not flattened. */
    static boolean hidden(double visible) {
        return visible <= HIDE_EPS;
    }

    static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Smoothstep. */
    static double ease(double u) {
        double c = clamp01(u);
        return c * c * (3.0 - 2.0 * c);
    }

    static double easeOut(double u) {
        double c = clamp01(u);
        return 1.0 - (1.0 - c) * (1.0 - c) * (1.0 - c);
    }

    /** Fractional part, 0 <= result < 1. */
    static double wrap(double v) {
        return v - Math.floor(v);
    }

    /** A looping 0..1 value went back to the start: the display must jump, not fly back. */
    static boolean wrapped(double previous, double next) {
        return next < previous;
    }

    /** Width of one plate so that {@code sides} plates at {@code radius} form a closed ring. */
    static double plateWidth(double radius, int sides) {
        return 2.0 * radius * Math.tan(Math.PI / sides) * 1.06 + 0.08;
    }
}
