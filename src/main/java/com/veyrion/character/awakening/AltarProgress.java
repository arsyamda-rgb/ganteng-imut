package com.veyrion.character.awakening;

/**
 * Pure animation clock of the altar pillars (no Bukkit). Progress 0 = short stumps, 1 = tall pillars.
 *
 * <p>Motion is a smoothstep from {@code from} to {@code to} over {@code duration} ticks. A new target always
 * starts from the value at that moment, and its duration is proportional to the distance left, so a player
 * entering or leaving mid-animation reverses the motion without any jump. Time is passed in by the caller
 * (ticks as a double), which keeps the class deterministic and testable.
 */
final class AltarProgress {

    enum Phase { IDLE, RISING, ACTIVE, LOWERING }

    private double from;
    private double to;
    private double since;
    private double duration;

    /** Smoothstep easing, 0..1 to 0..1. */
    static double smooth(double u) {
        double c = Math.max(0.0, Math.min(1.0, u));
        return c * c * (3.0 - 2.0 * c);
    }

    /** Current progress at time {@code now}. */
    double valueAt(double now) {
        if (duration <= 0.0 || now >= since + duration) {
            return to;
        }
        double u = (now - since) / duration;
        return from + (to - from) * smooth(u);
    }

    boolean movingAt(double now) {
        return duration > 0.0 && now < since + duration;
    }

    Phase phaseAt(double now) {
        if (movingAt(now)) {
            return to > from ? Phase.RISING : Phase.LOWERING;
        }
        return to >= 0.5 ? Phase.ACTIVE : Phase.IDLE;
    }

    /** True if the last target was "up" (rising or already up), whether or not it has arrived. */
    boolean targetsUp() {
        return to >= 0.5;
    }

    double target() {
        return to;
    }

    /**
     * Starts moving to {@code target} (0 or 1).
     *
     * @param fullTicks ticks a full 0-to-1 (or 1-to-0) motion takes
     * @return ticks the new motion lasts; 0 if already there
     */
    double retarget(double target, double now, double fullTicks) {
        double current = valueAt(now);
        from = current;
        to = target;
        since = now;
        duration = Math.abs(target - current) * Math.max(1.0, fullTicks);
        return duration;
    }

    /** Progress this motion has after {@code fraction} (0..1) of its duration, for sampling keyframes. */
    double keyframe(double fraction) {
        return from + (to - from) * smooth(fraction);
    }

    double duration() {
        return duration;
    }

    /** Back to idle without animation (entities were removed or rebuilt). */
    void reset() {
        from = 0.0;
        to = 0.0;
        since = 0.0;
        duration = 0.0;
    }
}
