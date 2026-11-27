package com.veyrion.character.awakening;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One pillar of the altar: a fixed group of 13 display entities, spawned once. Rising and lowering only change
 * transformations of existing entities (the client interpolates between keyframes), so nothing is spawned per
 * tick. Main thread only.
 *
 * <p>Geometry is in a local frame whose +Z points at the altar centre, so every pillar faces inward.
 * From the ground up: wide base, stepped plinth, chiseled collar, a shaft of stacked blocks with two glowing
 * accent bands, a chiseled capital and a floating gem. The race rune hangs on the inward face of the shaft.
 *
 * <p>Shaft segments: a segment with nothing to show is scaled to zero on every axis and parked at its own slot
 * (see {@link PillarMath#hidden}). Squashing only the height left a full-width plate floating in the air.
 */
final class StonePillar {

    static final String TAG = DisplayKit.TAG;

    /** Light level of the accents: dormant (pillar short), lit (pillar standing), ritual (lit + gem outline). */
    static final int DORMANT = 0;
    static final int LIT = 1;
    static final int RITUAL = 2;

    private static final double BASE_H = 0.25;
    private static final double BASE_W = 2.1;
    private static final double STEP_H = 0.30;
    private static final double STEP_W = 1.6;
    private static final double COLLAR_H = 0.30;
    private static final double COLLAR_W = 1.3;
    static final double PLINTH_H = BASE_H + STEP_H + COLLAR_H;
    private static final double SHAFT_W = 1.0;
    static final double SEG_H = 1.3;
    private static final double BAND_W = 1.16;
    private static final double[] BAND_AT = {0.28, 0.80};
    private static final double CAP_W = 1.5;
    private static final double CAP_H = 0.32;
    private static final double MIN_TEXT = 0.001;

    private static final Display.Brightness DORMANT_LIGHT = new Display.Brightness(7, 7);
    private static final Display.Brightness LIT_LIGHT = new Display.Brightness(15, 15);

    private final AltarVisualSettings.Stone stone;
    private final Location base;
    private final Quaternionf yaw;
    private final double stump;
    private final double tall;
    private final List<Display> all = new ArrayList<>();
    private final BlockDisplay[] segments;
    private final double[] lastVisible;
    private final BlockDisplay[] bands = new BlockDisplay[BAND_AT.length];
    private BlockDisplay cap;
    private BlockDisplay gem;
    private TextDisplay rune;
    private final Color dust;
    private double lastP;
    private double gemBoost = 1.0;
    private int level = DORMANT;

    private StonePillar(AltarVisualSettings.Stone stone, Location base, double facingX, double facingZ,
                        AltarVisualSettings cfg) {
        this.stone = stone;
        this.base = base;
        this.yaw = new Quaternionf().rotationY((float) Math.atan2(facingX, facingZ));
        this.stump = cfg.stumpHeight();
        this.tall = cfg.pillarHeight();
        this.segments = new BlockDisplay[PillarMath.segmentCount(tall, SEG_H)];
        this.lastVisible = new double[segments.length];
        java.util.Arrays.fill(lastVisible, -1.0);
        this.dust = Color.fromRGB(stone.color().value());
    }

    /**
     * Spawns the pillar at rest. If anything fails, the entities already spawned are removed again.
     *
     * @param base   centre of the pillar's bottom
     * @param facing horizontal direction from the pillar towards the altar centre (does not need to be unit)
     */
    static StonePillar spawn(AltarVisualSettings.Stone stone, Location base, double facingX, double facingZ,
                             AltarVisualSettings cfg) {
        StonePillar pillar = new StonePillar(stone, base, facingX, facingZ, cfg);
        try {
            pillar.build(cfg.viewRange());
        } catch (RuntimeException e) {
            pillar.remove();
            throw e;
        }
        return pillar;
    }

    private void build(float viewRange) {
        World world = base.getWorld();
        // Fixed parts: their transformation never changes, so it is the spawn transformation.
        part(world, Material.STONE_BRICKS, box(0, 0, 0, BASE_W, BASE_H, BASE_W), viewRange, null);
        part(world, Material.DEEPSLATE_BRICKS, box(0, BASE_H, 0, STEP_W, STEP_H, STEP_W), viewRange, null);
        part(world, Material.CHISELED_STONE_BRICKS,
                box(0, BASE_H + STEP_H, 0, COLLAR_W, COLLAR_H, COLLAR_W), viewRange, null);
        // Moving parts start hidden (or at rest); apply(0, 0) below puts them where they belong.
        for (int k = 0; k < segments.length; k++) {
            segments[k] = part(world, Material.STONE_BRICKS, hiddenSlot(k), viewRange, null);
        }
        for (int i = 0; i < bands.length; i++) {
            bands[i] = part(world, stone.accent(), DisplayKit.hidden(0, PLINTH_H, 0), viewRange, DORMANT_LIGHT);
        }
        cap = part(world, Material.CHISELED_STONE_BRICKS, DisplayKit.hidden(0, PLINTH_H, 0), viewRange, null);
        gem = part(world, stone.gem(), DisplayKit.hidden(0, PLINTH_H, 0), viewRange, DORMANT_LIGHT);
        rune = world.spawn(base, TextDisplay.class, d -> {
            DisplayKit.prepare(d, viewRange, DORMANT_LIGHT);
            d.text(Component.text(stone.rune(), stone.color()));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
        });
        all.add(rune);
        apply(0.0, 0);
    }

    private BlockDisplay part(World world, Material material, Transformation initial, float viewRange,
                              Display.Brightness light) {
        BlockDisplay display = DisplayKit.block(world, base, material, initial, viewRange, light);
        all.add(display);
        return display;
    }

    // ----------------------------------------------------------------------------------------------- geometry

    private double height(double p) {
        return stump + (tall - stump) * p;
    }

    /** Box with its bottom centre at (ox, oy, oz) in the local frame, turned to face the altar. */
    private Transformation box(double ox, double oy, double oz, double sx, double sy, double sz) {
        Vector3f c = new Vector3f((float) ox, (float) (oy + sy / 2), (float) oz).rotate(yaw);
        return DisplayKit.centered(c.x, c.y, c.z, yaw, sx, sy, sz);
    }

    /** A hidden segment waits as a zero-size point at the bottom of its own slot. */
    private static Transformation hiddenSlot(int k) {
        return DisplayKit.hidden(0, PLINTH_H + k * SEG_H, 0);
    }

    /**
     * Moves every dynamic part to progress {@code p} (0 = stump, 1 = full pillar); the client interpolates
     * over {@code ticks}. Segments whose height did not change are skipped.
     */
    void apply(double p, int ticks) {
        lastP = p;
        double h = height(p);
        for (int k = 0; k < segments.length; k++) {
            double visible = PillarMath.visible(h, k, SEG_H);
            if (Math.abs(visible - lastVisible[k]) < 1e-4) {
                continue;
            }
            lastVisible[k] = visible;
            Transformation target = PillarMath.hidden(visible)
                    ? hiddenSlot(k)
                    : box(0, PLINTH_H + k * SEG_H, 0, SHAFT_W, visible, SHAFT_W);
            DisplayKit.set(segments[k], target, ticks);
        }
        double bandH = 0.10 + 0.06 * p;
        for (int i = 0; i < bands.length; i++) {
            double y = PLINTH_H + h * BAND_AT[i] - bandH / 2;
            DisplayKit.set(bands[i], box(0, y, 0, BAND_W, bandH, BAND_W), ticks);
        }
        DisplayKit.set(cap, box(0, PLINTH_H + h, 0, CAP_W, CAP_H, CAP_W), ticks);
        DisplayKit.set(gem, gemTransform(p, h), ticks);
        DisplayKit.set(rune, runeTransform(p, h), ticks);
    }

    /** A cube standing on a corner above the capital; it turns once while the pillar rises. */
    private Transformation gemTransform(double p, double h) {
        double size = (0.2 + 0.36 * p) * gemBoost;
        Quaternionf rotation = new Quaternionf(yaw)
                .rotateY((float) (p * Math.PI * 2.0))
                .rotateX(0.6155f)
                .rotateZ((float) (Math.PI / 4.0));
        double y = PLINTH_H + h + CAP_H + 0.30 + 0.35 * p;
        return DisplayKit.centered(0, y, 0, rotation, size, size, size);
    }

    /** The race rune hangs on the inward side of the shaft and grows in during the second part of the rise. */
    private Transformation runeTransform(double p, double h) {
        float scale = (float) Math.max(MIN_TEXT, 2.2 * Math.max(0.0, Math.min(1.0, (p - 0.3) / 0.7)));
        Vector3f translation = new Vector3f(0f, (float) (PLINTH_H + h * 0.54), 0.62f).rotate(yaw);
        return new Transformation(translation, new Quaternionf(), new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    // --------------------------------------------------------------------------------------- ritual reaction

    /** Dormant, lit (standing) or ritual (lit and the gem gets an outline in the race colour). */
    void setLevel(int newLevel) {
        if (newLevel == level) {
            return;
        }
        level = newLevel;
        Display.Brightness light = newLevel == DORMANT ? DORMANT_LIGHT : LIT_LIGHT;
        for (BlockDisplay band : bands) {
            band.setBrightness(light);
        }
        gem.setBrightness(light);
        rune.setBrightness(light);
        boolean outline = newLevel >= RITUAL;
        gem.setGlowing(outline);
        if (outline) {
            gem.setGlowColorOverride(dust);
        }
    }

    /** Scales the gem (1 = normal); the client interpolates over {@code ticks}. */
    void setGemBoost(double boost, int ticks) {
        if (Math.abs(boost - gemBoost) < 1e-3) {
            return;
        }
        gemBoost = boost;
        DisplayKit.set(gem, gemTransform(lastP, height(lastP)), ticks);
    }

    /** Where the energy of this pillar leaves it (world coordinates, centre of the gem when standing). */
    Vector3f gemPoint() {
        return new Vector3f((float) base.getX(), (float) (base.getY() + PLINTH_H + tall + CAP_H + 0.65),
                (float) base.getZ());
    }

    AltarVisualSettings.Stone stone() {
        return stone;
    }

    // ------------------------------------------------------------------------------------------------ effects

    /** Stone dust at the foot of the pillar. */
    void burst(int count) {
        if (count <= 0) {
            return;
        }
        base.getWorld().spawnParticle(Particle.BLOCK, base.clone().add(0, 0.35, 0), count, 0.85, 0.12, 0.85, 0.05,
                Material.STONE_BRICKS.createBlockData());
    }

    /** One ambient pulse of an active pillar: a glint at the gem and a few motes along the shaft. */
    void pulse(int count) {
        if (count <= 0) {
            return;
        }
        World world = base.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector3f top = gemPoint();
        Location at = new Location(world, top.x, top.y, top.z);
        world.spawnParticle(Particle.DUST, at, count, 0.25, 0.3, 0.25, 0.0, new Particle.DustOptions(dust, 1.2f));
        world.spawnParticle(Particle.END_ROD, at, 1, 0.15, 0.2, 0.15, 0.01);
        for (int i = 0; i < Math.min(count, 2); i++) {
            Location mote = base.clone().add(random.nextDouble(-0.7, 0.7), random.nextDouble(0.3, tall),
                    random.nextDouble(-0.7, 0.7));
            world.spawnParticle(Particle.ENCHANT, mote, 1, 0.1, 0.1, 0.1, 0.3);
        }
    }

    /** A flash of the race colour at the gem when the pillar finishes rising. */
    void flare() {
        Vector3f top = gemPoint();
        Location at = new Location(base.getWorld(), top.x, top.y, top.z);
        base.getWorld().spawnParticle(Particle.DUST, at, 18, 0.35, 0.35, 0.35, 0.0,
                new Particle.DustOptions(dust, 1.6f));
        base.getWorld().spawnParticle(Particle.END_ROD, at, 6, 0.3, 0.3, 0.3, 0.05);
    }

    // ------------------------------------------------------------------------------------------------ lifetime

    boolean valid() {
        return !all.isEmpty() && DisplayKit.allValid(all);
    }

    void remove() {
        DisplayKit.removeAll(all);
        all.clear();
    }
}
