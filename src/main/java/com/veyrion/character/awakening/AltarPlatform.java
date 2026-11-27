package com.veyrion.character.awakening;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

/**
 * The static base of the altar: one pad under each pillar, spawned once and never touched again. There is no
 * ring, no corner stone and no decoration anywhere else on the floor (the octagonal trim of earlier versions
 * was removed); the middle belongs to the floating {@link AncientCore}. The pads reach {@link #SINK} blocks
 * below the floor so uneven ground does not leave gaps. Main thread only.
 */
final class AltarPlatform {

    /** Height of the pad top above the floor; pillars stand on it. */
    static final double PAD_TOP = 0.18;

    private static final double SINK = 0.5;
    private static final double PAD_W = 3.0;
    /** Blocks between a pillar's centre and the far edge of its pad (the pad is {@link #PAD_W} wide). */
    private static final double MARGIN = PAD_W / 2.0 + 0.1;

    private final List<BlockDisplay> all = new ArrayList<>();

    private AltarPlatform() {
    }

    /** Distance from the altar centre to the outer edge of the pads (used for chunk footprint and clean-up). */
    static double radius(double stoneDistance) {
        return stoneDistance + MARGIN;
    }

    /**
     * @param floor          middle of the altar's top surface
     * @param stoneDistance  distance from the centre to each pillar
     * @param angleOffsetDeg angle of the first pillar
     * @param pillars        number of pillars (a pad is placed under each)
     */
    static AltarPlatform spawn(Location floor, double stoneDistance, double angleOffsetDeg, int pillars,
                               float viewRange) {
        AltarPlatform platform = new AltarPlatform();
        try {
            platform.build(floor, stoneDistance, angleOffsetDeg, pillars, viewRange);
        } catch (RuntimeException e) {
            platform.remove();
            throw e;
        }
        return platform;
    }

    private void build(Location floor, double stoneDistance, double angleOffsetDeg, int pillars,
                       float viewRange) {
        World world = floor.getWorld();
        double padH = SINK + PAD_TOP;
        for (int i = 0; i < pillars; i++) {
            double angle = Math.toRadians(angleOffsetDeg + 360.0 * i / pillars);
            double dx = Math.cos(angle) * stoneDistance;
            double dz = Math.sin(angle) * stoneDistance;
            Quaternionf facing = new Quaternionf().rotationY((float) Math.atan2(-dx, -dz));
            Transformation t = DisplayKit.centered(dx, -SINK + padH / 2, dz, facing, PAD_W, padH, PAD_W);
            all.add(DisplayKit.block(world, floor, Material.POLISHED_DEEPSLATE, t, viewRange, null));
        }
    }

    boolean valid() {
        return !all.isEmpty() && DisplayKit.allValid(all);
    }

    void remove() {
        DisplayKit.removeAll(all);
        all.clear();
    }
}
