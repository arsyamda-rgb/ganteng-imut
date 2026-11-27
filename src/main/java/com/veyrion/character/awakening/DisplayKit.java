package com.veyrion.character.awakening;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Small helpers shared by the altar display classes. Main thread only. */
final class DisplayKit {

    /** Scoreboard tag on every display of the altar; used to clean up leftovers of a crashed reload. */
    static final String TAG = "veyrion_altar_visual";

    private DisplayKit() {
    }

    /**
     * Spawns a non-persistent block display. The initial transformation is set before the entity is added to
     * the world, so nothing is ever shown for a tick as a full-size cube.
     *
     * @param brightness light override, or null to use the world light
     */
    static BlockDisplay block(World world, Location at, Material material, Transformation initial,
                              float viewRange, Display.Brightness brightness) {
        return world.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(material.createBlockData());
            prepare(d, viewRange, brightness);
            d.setTransformation(initial);
        });
    }

    static void prepare(Display display, float viewRange, Display.Brightness brightness) {
        display.setPersistent(false);
        display.addScoreboardTag(TAG);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setViewRange(viewRange);
        display.setShadowRadius(0f);
        display.setShadowStrength(0f);
        display.setBillboard(Display.Billboard.FIXED);
        display.setInterpolationDuration(0);
        display.setInterpolationDelay(0);
        if (brightness != null) {
            display.setBrightness(brightness);
        }
    }

    /** Applies a transformation; the client interpolates to it over {@code ticks} (0 = jump). */
    static void set(Display display, Transformation transformation, int ticks) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(0, ticks));
        display.setTransformation(transformation);
    }

    /** A box of size (sx, sy, sz) whose CENTRE is at (cx, cy, cz), turned by the unit quaternion {@code rot}. */
    static Transformation centered(double cx, double cy, double cz, Quaternionf rot,
                                   double sx, double sy, double sz) {
        Vector3f half = new Vector3f((float) (sx / 2), (float) (sy / 2), (float) (sz / 2)).rotate(rot);
        Vector3f translation = new Vector3f((float) cx, (float) cy, (float) cz).sub(half);
        return new Transformation(translation, new Quaternionf(rot),
                new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf());
    }

    /**
     * A cube standing on a corner and stretched along the vertical, centred at (cx, cy, cz): a crystal. The
     * stretch is applied between the tilt and the yaw so it stays vertical however the crystal turns.
     *
     * @param yaw     turn around the vertical axis (radians)
     * @param side    edge of the cube before stretching
     * @param stretch vertical stretch (1 = plain cube on its corner)
     */
    static Transformation crystal(double cx, double cy, double cz, double yaw, double side, double stretch) {
        Quaternionf tilt = new Quaternionf().rotateX(0.6155f).rotateZ((float) (Math.PI / 4.0));
        Quaternionf turn = new Quaternionf().rotationY((float) yaw);
        Vector3f scale = new Vector3f((float) side, (float) (side * stretch), (float) side);
        Vector3f half = new Vector3f(0.5f, 0.5f, 0.5f).rotate(tilt).mul(scale).rotate(turn);
        Vector3f translation = new Vector3f((float) cx, (float) cy, (float) cz).sub(half);
        // Bukkit order: v' = translation + left * (scale * (right * v)).
        return new Transformation(translation, turn, scale, tilt);
    }

    /**
     * Invisible: scale ZERO ON EVERY AXIS, parked at a point. Never use a thin box for "nothing": a flattened
     * block is still a visible plate.
     */
    static Transformation hidden(double x, double y, double z) {
        return new Transformation(new Vector3f((float) x, (float) y, (float) z), new Quaternionf(),
                new Vector3f(0f, 0f, 0f), new Quaternionf());
    }

    static void removeAll(Iterable<? extends Display> displays) {
        for (Display display : displays) {
            display.remove();
        }
    }

    static boolean allValid(Iterable<? extends Display> displays) {
        for (Display display : displays) {
            if (!display.isValid()) {
                return false;
            }
        }
        return true;
    }
}
