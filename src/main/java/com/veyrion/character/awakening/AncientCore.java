package com.veyrion.character.awakening;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Ancient Core: the centrepiece of the altar. A large floating golden crystal above the middle platform
 * (BlockDisplays only: translucent amber body, glowing heart, two gold tips and a ring of orbiting gold shards),
 * a few small runes orbiting it, and thin streams of energy flowing in from the gem of every pillar.
 *
 * <p>The crystal is also the altar's button: one transparent {@link Interaction} entity is the click box. It never
 * draws anything; it is moved together with the crystal so players click the crystal itself, not the floor.
 *
 * <p>Displays: body + heart + two tips (4) + shards + runes + motes ({@code pillars x flowMotes}), plus the click
 * box. With the defaults that is 4 + 6 + 4 + 8 = 22 displays and 1 interaction, spawned once and never replaced. Cost control, same as
 * the pillars: the displays never move on their own; every {@value #PERIOD} ticks one new transformation per
 * display is sent and the client interpolates in between. The update runs only while the pillars stand (the
 * altar drives {@link #tick}); a dormant core is completely static and costs nothing.
 *
 * <p>Awakeness {@code p} (0..1) follows the pillars: at 0 the crystal is small, low and quiet; at 1 it is full
 * size at its hover height, the runes orbit and the energy flows. During a ritual the core yields to the ritual
 * orb ({@link #setYielded}) and returns afterwards. Purely visual. Main thread only.
 */
final class AncientCore {

    /** Ticks between two transformation updates. */
    static final int PERIOD = 4;

    private static final Display.Brightness FULL = new Display.Brightness(15, 15);
    private static final double SIDE = 0.8;
    private static final double STRETCH = 1.6;
    private static final double TIP_SIDE = 0.26;
    private static final double HEART = 0.55;
    private static final double SHARD_SIDE = 0.2;
    private static final double RUNE_SCALE = 0.9;
    private static final double TRAVEL_TICKS = 30.0;
    /** Below this awakeness the runes and the energy are hidden. */
    private static final double QUIET = 0.2;

    private final World world;
    private final Location origin;
    private final AltarVisualSettings.CoreSettings cfg;
    private final List<AltarVisualSettings.Stone> stones;
    private final List<Display> all = new ArrayList<>();
    private final BlockDisplay body;
    private final BlockDisplay heart;
    private final BlockDisplay tipTop;
    private final BlockDisplay tipBottom;
    private final BlockDisplay[] shards;
    /** Invisible click box that follows the crystal. */
    private final Interaction hitbox;
    private double hitboxY = Double.NaN;
    private float hitboxW = Float.NaN;
    private float hitboxH = Float.NaN;
    private final TextDisplay[] runes;
    private final BlockDisplay[] motes;
    private final double[] moteFrac;
    private final boolean[] moteHidden;
    /** Pillar gem positions relative to {@link #origin}. */
    private final Vector3f[] sources;
    private final Color[] dust;

    private double awake;
    private double time;
    private double spin;
    private int acc;
    private boolean yielded;
    private boolean closed;

    /**
     * @param origin   middle of the altar's top surface
     * @param pillars  the pillars (their gem points are the sources of the energy)
     */
    private AncientCore(Location origin, List<StonePillar> pillars, AltarVisualSettings settings) {
        this.world = origin.getWorld();
        this.origin = origin.clone();
        this.cfg = settings.core();
        this.stones = settings.stones();
        this.sources = new Vector3f[pillars.size()];
        this.dust = new Color[pillars.size()];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = pillars.get(i).gemPoint().sub((float) origin.getX(), (float) origin.getY(),
                    (float) origin.getZ());
            dust[i] = Color.fromRGB(pillars.get(i).stone().color().value());
        }
        int moteCount = cfg.flowMotes() * pillars.size();
        this.runes = new TextDisplay[Math.max(0, cfg.runes())];
        this.motes = new BlockDisplay[moteCount];
        this.moteFrac = new double[moteCount];
        this.moteHidden = new boolean[moteCount];
        java.util.Arrays.fill(moteHidden, true);

        float view = Math.max(0.5f, settings.viewRange());
        Transformation none = DisplayKit.hidden(0, cfg.dormantHeight(), 0);
        body = block(cfg.body(), none, view);
        heart = block(cfg.inner(), none, view);
        tipTop = block(cfg.tip(), none, view);
        tipBottom = block(cfg.tip(), none, view);
        shards = new BlockDisplay[Math.max(0, cfg.shards())];
        for (int i = 0; i < shards.length; i++) {
            shards[i] = block(cfg.shard(), none, view);
        }
        if (cfg.glow()) {
            body.setGlowing(true);
            body.setGlowColorOverride(Color.fromRGB(cfg.glowColor().value()));
        }
        double dormant = cfg.dormantHeight();
        TextColor fallback = cfg.glowColor();
        for (int i = 0; i < runes.length; i++) {
            AltarVisualSettings.Stone stone = stones.isEmpty() ? null : stones.get(i % stones.size());
            String glyph = stone == null ? "*" : stone.rune();
            TextColor color = stone == null ? fallback : stone.color();
            runes[i] = world.spawn(origin, TextDisplay.class, d -> {
                DisplayKit.prepare(d, view, FULL);
                d.text(Component.text(glyph, color));
                d.setBillboard(Display.Billboard.CENTER);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(false);
                d.setSeeThrough(false);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setTransformation(DisplayKit.hidden(0, dormant, 0));
            });
            all.add(runes[i]);
        }
        for (int i = 0; i < motes.length; i++) {
            int pillar = i / cfg.flowMotes();
            AltarVisualSettings.Stone stone = pillars.get(pillar).stone();
            motes[i] = block(stone.gem(), DisplayKit.hidden(sources[pillar].x, sources[pillar].y, sources[pillar].z),
                    view);
        }
        float boxW = (float) settings.core().hitboxWidth();
        float boxH = (float) settings.core().hitboxHeight();
        hitbox = world.spawn(origin.clone().add(0, settings.core().dormantHeight(), 0), Interaction.class, h -> {
            h.setPersistent(false);
            h.addScoreboardTag(DisplayKit.TAG);
            h.setInvulnerable(true);
            h.setGravity(false);
            h.setResponsive(true);
            h.setInteractionWidth(boxW);
            h.setInteractionHeight(boxH);
        });
    }

    /**
     * Spawns the core at rest. If anything fails the entities already spawned are removed and the exception is
     * rethrown.
     */
    static AncientCore spawn(Location origin, List<StonePillar> pillars, AltarVisualSettings settings) {
        AncientCore core = null;
        try {
            core = new AncientCore(origin, pillars, settings);
            core.place(0);
            return core;
        } catch (RuntimeException e) {
            if (core != null) {
                core.remove();
            }
            throw e;
        }
    }

    private BlockDisplay block(org.bukkit.Material material, Transformation initial, float view) {
        BlockDisplay display = DisplayKit.block(world, origin, material, initial, view, FULL);
        all.add(display);
        return display;
    }

    // ------------------------------------------------------------------------------------------------ driving

    /** Moves the core to awakeness {@code p}; the client interpolates over {@code ticks}. Follows the pillars. */
    void apply(double p, int ticks) {
        awake = PillarMath.clamp01(p);
        if (!yielded && !closed) {
            place(ticks);
        }
    }

    /** One step of the altar's update task: advances the clock and sends one transformation per display. */
    void tick(int stepTicks) {
        if (closed || yielded || awake <= 0.0) {
            return;
        }
        acc += stepTicks;
        if (acc < PERIOD) {
            return;
        }
        int dt = acc;
        acc = 0;
        time += dt;
        spin += 0.045 * cfg.orbitSpeed() * dt;
        place(dt + 1);
        trail();
    }

    /** Hides the core (true) while the ritual orb occupies its place, and brings it back (false). Idempotent. */
    void setYielded(boolean yield) {
        if (yield == yielded || closed) {
            return;
        }
        yielded = yield;
        if (yield) {
            DisplayKit.set(body, DisplayKit.hidden(0, cfg.hoverHeight(), 0), 10);
            DisplayKit.set(heart, DisplayKit.hidden(0, cfg.hoverHeight(), 0), 10);
            for (BlockDisplay shard : shards) {
                DisplayKit.set(shard, DisplayKit.hidden(0, cfg.hoverHeight(), 0), 10);
            }
            DisplayKit.set(tipTop, DisplayKit.hidden(0, cfg.hoverHeight(), 0), 10);
            DisplayKit.set(tipBottom, DisplayKit.hidden(0, cfg.hoverHeight(), 0), 10);
            for (TextDisplay rune : runes) {
                DisplayKit.set(rune, DisplayKit.hidden(0, cfg.hoverHeight(), 0), 10);
            }
            for (int i = 0; i < motes.length; i++) {
                Vector3f source = sources[i / cfg.flowMotes()];
                DisplayKit.set(motes[i], DisplayKit.hidden(source.x, source.y, source.z), 0);
                moteHidden[i] = true;
            }
        } else {
            place(20);
        }
    }

    // ----------------------------------------------------------------------------------------------- placement

    private static double smooth(double u) {
        return PillarMath.ease(PillarMath.clamp01(u));
    }

    private void place(int ticks) {
        if (!DisplayKit.allValid(all) || !hitbox.isValid()) {
            close();
            return;
        }
        double wake = smooth(awake);
        double scale = cfg.size() * PillarMath.lerp(0.55, 1.0, wake);
        double height = PillarMath.lerp(cfg.dormantHeight(), cfg.hoverHeight(), wake)
                + CoreMath.bob(time, 90.0, 0.07 * wake * cfg.size());
        DisplayKit.set(body, DisplayKit.crystal(0, height, 0, spin * 0.35, SIDE * scale, STRETCH), ticks);
        // The glowing heart sits inside the translucent body and turns the other way.
        DisplayKit.set(heart, DisplayKit.crystal(0, height, 0, -spin * 0.6, SIDE * scale * HEART, STRETCH), ticks);
        moveHitbox(height, wake);

        // Two small gems turning against the crystal, above and below it.
        double reach = SIDE * scale * STRETCH * 0.87 + 0.4 * cfg.size();
        double tipSize = TIP_SIDE * scale * wake;
        double tipYaw = -spin * 1.2;
        DisplayKit.set(tipTop, tip(height + reach, tipYaw, tipSize), ticks);
        DisplayKit.set(tipBottom, tip(height - reach, tipYaw, tipSize), ticks);

        boolean live = wake > QUIET;
        for (int i = 0; i < shards.length; i++) {
            if (!live) {
                DisplayKit.set(shards[i], DisplayKit.hidden(0, height, 0), ticks);
                continue;
            }
            // A ring of small gold shards, tilted the other way than the runes and turning faster.
            double[] at = CoreMath.orbit(i, shards.length, -spin * 1.4, 0.95 * scale + 0.2, -0.5);
            double bob = CoreMath.bob(time + i * 23.0, 80.0, 0.08);
            double size = SHARD_SIDE * scale * wake;
            DisplayKit.set(shards[i], size < 0.01 ? DisplayKit.hidden(at[0], height + at[1], at[2])
                    : DisplayKit.crystal(at[0], height + at[1] + bob, at[2], spin * 2.0 + i, size, STRETCH), ticks);
        }
        for (int i = 0; i < runes.length; i++) {
            if (!live) {
                DisplayKit.set(runes[i], DisplayKit.hidden(0, height, 0), ticks);
                continue;
            }
            double[] at = CoreMath.orbit(i, runes.length, spin, cfg.orbitRadius() * cfg.size(), 0.35);
            double bob = CoreMath.bob(time + i * 17.0, 70.0, 0.12);
            float s = (float) (RUNE_SCALE * wake);
            Transformation t = new Transformation(
                    new Vector3f((float) at[0], (float) (height + at[1] + bob - 0.12 * s), (float) at[2]),
                    new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
            DisplayKit.set(runes[i], t, ticks);
        }
        flow(height, wake, live, ticks);
    }

    /**
     * Keeps the click box on the crystal: centred on it, a little smaller while the crystal is small. Only moves or
     * resizes when something changed noticeably, so a still crystal costs nothing.
     */
    private void moveHitbox(double height, double wake) {
        double factor = cfg.size() * PillarMath.lerp(0.8, 1.0, wake);
        float w = (float) (cfg.hitboxWidth() * factor);
        float h = (float) (cfg.hitboxHeight() * factor);
        if (Float.isNaN(hitboxW) || Math.abs(w - hitboxW) > 0.02f) {
            hitbox.setInteractionWidth(w);
            hitboxW = w;
        }
        if (Float.isNaN(hitboxH) || Math.abs(h - hitboxH) > 0.02f) {
            hitbox.setInteractionHeight(h);
            hitboxH = h;
        }
        // An Interaction's position is the middle of its bottom face.
        double y = origin.getY() + height - h / 2.0;
        if (Double.isNaN(hitboxY) || Math.abs(y - hitboxY) > 0.02) {
            hitbox.teleport(new Location(world, origin.getX(), y, origin.getZ()));
            hitboxY = y;
        }
    }

    /** True if {@code entity} is this crystal's click box. */
    boolean isHitbox(Entity entity) {
        return entity.getUniqueId().equals(hitbox.getUniqueId());
    }

    private Transformation tip(double y, double yaw, double size) {
        return size < 0.01 ? DisplayKit.hidden(0, y, 0) : DisplayKit.crystal(0, y, 0, yaw, size, 1.0);
    }

    /** Position of mote {@code index} (relative to {@link #origin}), also used by {@link #trail}. */
    private double[] motePoint(int index, double height, double f) {
        Vector3f source = sources[index / cfg.flowMotes()];
        double[] p = CoreMath.flow(source.x, source.y - height, source.z, f, 0.9, 0.45);
        return new double[] {p[0], p[1] + height, p[2]};
    }

    private double moteF(int index) {
        int per = cfg.flowMotes();
        int pillar = index / per;
        int j = index % per;
        return PillarMath.wrap(time / TRAVEL_TICKS + j / (double) per + pillar * 0.13);
    }

    private void flow(double height, double wake, boolean live, int ticks) {
        for (int i = 0; i < motes.length; i++) {
            Vector3f source = sources[i / cfg.flowMotes()];
            if (!live) {
                if (!moteHidden[i]) {
                    moteHidden[i] = true;
                    DisplayKit.set(motes[i], DisplayKit.hidden(source.x, source.y, source.z), ticks);
                }
                continue;
            }
            moteHidden[i] = false;
            double f = moteF(i);
            boolean snap = PillarMath.wrapped(moteFrac[i], f);
            moteFrac[i] = f;
            double[] at = motePoint(i, height, f);
            double size = 0.22 * cfg.size() * CoreMath.flowSize(f) * wake;
            Transformation t = size < 0.01
                    ? DisplayKit.hidden(at[0], at[1], at[2])
                    : DisplayKit.crystal(at[0], at[1], at[2], f * CoreMath.TAU, size, 1.0);
            DisplayKit.set(motes[i], t, snap ? 0 : ticks);
        }
    }

    /** A faint dust speck on every mote, so the streams read as light and not as floating cubes. */
    private void trail() {
        if (!cfg.trail() || motes.length == 0 || awake < 0.95) {
            return;
        }
        double height = cfg.hoverHeight();
        for (int i = 0; i < motes.length; i++) {
            double f = moteF(i);
            if (f < 0.05 || f > 0.95) {
                continue;
            }
            double[] at = motePoint(i, height, f);
            world.spawnParticle(Particle.DUST, origin.getX() + at[0], origin.getY() + at[1], origin.getZ() + at[2], 1,
                    0.04, 0.04, 0.04, 0.0, new Particle.DustOptions(dust[i / cfg.flowMotes()], 0.7f));
        }
    }

    // ------------------------------------------------------------------------------------------------ lifetime

    boolean valid() {
        return !closed && !all.isEmpty() && DisplayKit.allValid(all) && hitbox.isValid();
    }

    private void close() {
        closed = true;
    }

    /** Removes every display. Idempotent. */
    void remove() {
        closed = true;
        DisplayKit.removeAll(all);
        all.clear();
        hitbox.remove();
    }
}
