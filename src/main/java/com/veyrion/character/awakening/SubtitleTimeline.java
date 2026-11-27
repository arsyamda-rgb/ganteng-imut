package com.veyrion.character.awakening;

/**
 * Fade of one subtitle line as a function of the tick since it was shown. Pure (see
 * dev-checks/SubtitleTimelineCheck.java). Phases: fade in, stay, fade out; afterwards it is gone.
 */
final class SubtitleTimeline {

    /** Vanilla draws text with an opacity below 4 as fully opaque, so a visible fade never goes below this. */
    static final int MIN_VISIBLE = 5;

    private SubtitleTimeline() {
    }

    static int total(int fadeIn, int stay, int fadeOut) {
        return Math.max(0, fadeIn) + Math.max(0, stay) + Math.max(0, fadeOut);
    }

    /**
     * Fraction (0..1) of full visibility {@code tick} ticks after the line started; 0 before the start and after
     * the end.
     */
    static double visibility(int tick, int fadeIn, int stay, int fadeOut) {
        int in = Math.max(0, fadeIn);
        int hold = Math.max(0, stay);
        int out = Math.max(0, fadeOut);
        if (tick < 0 || tick >= in + hold + out) {
            return 0.0;
        }
        if (tick < in) {
            return (tick + 1.0) / (in + 1.0);
        }
        if (tick < in + hold) {
            return 1.0;
        }
        return 1.0 - (tick - in - hold + 1.0) / (out + 1.0);
    }

    /** Text opacity 0..255 for a visibility; 0 means "draw nothing", otherwise at least {@link #MIN_VISIBLE}. */
    static int textOpacity(double visibility) {
        if (visibility <= 0.0) {
            return 0;
        }
        return Math.max(MIN_VISIBLE, (int) Math.round(255.0 * Math.min(1.0, visibility)));
    }

    /** Background alpha for a visibility and the configured maximum (0..255); 0 when invisible. */
    static int backgroundAlpha(double visibility, int max) {
        if (visibility <= 0.0) {
            return 0;
        }
        return (int) Math.round(Math.max(0, Math.min(255, max)) * Math.min(1.0, visibility));
    }

    /** Alpha (0..255) for a background opacity in percent (60-70% is the film look). */
    static int alphaForPercent(int percent) {
        return (int) Math.round(255.0 * Math.max(0, Math.min(100, percent)) / 100.0);
    }
}
