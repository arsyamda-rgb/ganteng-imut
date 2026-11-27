package com.veyrion.character.awakening;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The floating part of the summoning ritual: an energy orb, a glowing shell, eight orbiting rune stones and a
 * few energy motes that fly from the pillars to the orb. 18 displays for a four-pillar altar, spawned when a
 * ritual starts and removed when it ends. All of them are ordinary entities, so every player near the altar
 * sees them, and nothing here is per player.
 *
 * <p>Purely presentational. The race is already stored when a stage is opened; the stage only reads the race
 * id to colour the reveal and never writes anything. The resonance cycle is a fixed visual list.
 *
 * <p>Cost control: the displays never move on their own. Every {@value #PERIOD} ticks the stage sends one new
 * transformation per display and the client interpolates in between. No task of its own: the ritual session
 * drives {@link #update}. Main thread only.
 */
final class AltarStage {

    /** Height of the orb above the altar's top surface. */
    private static final double LIFT = 2.4;
    /** Ticks between two transformation updates. */
    private static final int PERIOD = 4;
    private static final int HALO = 8;
    private static final double TAU = Math.PI * 2.0;
    private static final Material SHELL = Material.PURPLE_STAINED_GLASS;
    private static final Display.Brightness FULL = new Display.Brightness(15, 15);
    /** Height of the shockwave ring relative to the orb: just above the platform. */
    private static final double WAVE_Y = -(LIFT - 0.15);

    private final World world;
    private final Location orb;
    private final List<StonePillar> pillars;
    private final List<AltarVisualSettings.Stone> stones;
    private final AltarVisualSettings.Stone neutral;
    private final AltarVisualSettings.Stone result;
    private final int winner;
    private final AwakeningSettings.RevealStyle style;
    private final Color resultColor;
    private final double multiplier;
    private final Vector3f[] tops;

    private final List<BlockDisplay> all = new ArrayList<>();
    private final BlockDisplay core;
    private final BlockDisplay shell;
    private final BlockDisplay[] halo = new BlockDisplay[HALO];
    private final BlockDisplay[] motes;
    private final double[] haloFrac = new double[HALO];
    private final double[] moteFrac;
    private final boolean[] moteHidden;

    private List<AltarVisualSettings.Stone> cycle = List.of();
    private int[] cyclePillar = new int[0];
    private Material coreMaterial;
    private Material haloMaterial;
    private double spinA;
    private double spinB;
    private double time;
    private double waveFrac;
    private int acc;
    private boolean force = true;
    private boolean haloPrimed;
    private boolean closed;

    /**
     * @param orbCentre  the altar's top surface (middle); the orb floats above it
     * @param raceId     the race the server already granted; only used to pick colours for the reveal
     */
    AltarStage(World world, Location orbCentre, List<StonePillar> pillars, AltarVisualSettings cfg,
               String raceId, AwakeningSettings.RevealStyle style, TextColor raceColor, double multiplier) {
        this.world = world;
        this.orb = orbCentre.clone().add(0, LIFT, 0);
        this.pillars = pillars;
        this.stones = cfg.stones();
        this.neutral = new AltarVisualSettings.Stone("rift", TextColor.color(0xB388FF), Material.AMETHYST_BLOCK,
                Material.AMETHYST_BLOCK, "*");
        this.winner = indexOf(raceId);
        this.result = winner >= 0 ? stones.get(winner) : neutral;
        this.style = style;
        this.resultColor = Color.fromRGB(raceColor.value());
        this.multiplier = Math.max(0.0, multiplier);
        this.coreMaterial = neutral.gem();
        this.haloMaterial = neutral.accent();

        this.tops = new Vector3f[pillars.size()];
        for (int i = 0; i < tops.length; i++) {
            tops[i] = pillars.get(i).gemPoint().sub((float) orb.getX(), (float) orb.getY(), (float) orb.getZ());
        }
        this.motes = new BlockDisplay[pillars.size() * 2];
        this.moteFrac = new double[motes.length];
        this.moteHidden = new boolean[motes.length];
        java.util.Arrays.fill(moteHidden, true);
        float view = Math.max(0.5f, cfg.viewRange());
        try {
            Transformation none = DisplayKit.hidden(0, 0, 0);
            core = spawn(coreMaterial, none, view);
            shell = spawn(SHELL, none, view);
            for (int i = 0; i < HALO; i++) {
                halo[i] = spawn(haloMaterial, none, view);
            }
            for (int i = 0; i < motes.length; i++) {
                AltarVisualSettings.Stone stone = pillars.get(i / 2).stone();
                motes[i] = spawn(stone.gem(), DisplayKit.hidden(tops[i / 2].x, tops[i / 2].y, tops[i / 2].z), view);
            }
        } catch (RuntimeException e) {
            DisplayKit.removeAll(all);
            all.clear();
            throw e;
        }
    }

    private BlockDisplay spawn(Material material, Transformation initial, float view) {
        BlockDisplay display = DisplayKit.block(world, orb, material, initial, view, FULL);
        all.add(display);
        return display;
    }

    private int indexOf(String raceId) {
        for (int i = 0; i < stones.size(); i++) {
            if (stones.get(i).raceId().equalsIgnoreCase(raceId)) {
                return i;
            }
        }
        return -1;
    }

    /** The races shown one after another during resonance. Visual only; the order has no meaning. */
    void setCycle(List<String> raceIds) {
        List<AltarVisualSettings.Stone> list = new ArrayList<>();
        int[] pillarOf = new int[raceIds.size()];
        for (int i = 0; i < raceIds.size(); i++) {
            int index = indexOf(raceIds.get(i));
            pillarOf[i] = index;
            list.add(index >= 0 ? stones.get(index) : neutral);
        }
        this.cycle = List.copyOf(list);
        this.cyclePillar = pillarOf;
    }

    boolean closed() {
        return closed;
    }

    /** Removes every display and calms the pillars. Idempotent. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        DisplayKit.removeAll(all);
        all.clear();
        for (StonePillar pillar : pillars) {
            if (pillar.valid()) {
                pillar.setGemBoost(1.0, 10);
            }
        }
    }

    // ----------------------------------------------------------------------------------------- phase changes

    void enter(RitualSession.Phase phase) {
        if (closed) {
            return;
        }
        force = true;
        switch (phase) {
            case ACTIVATION -> {
                for (StonePillar pillar : pillars) {
                    pillar.setLevel(StonePillar.RITUAL);
                    pillar.setGemBoost(1.5, 12);
                }
            }
            case RESONANCE -> { }
            case ACCUMULATION -> {
                setCore(neutral.gem());
                setHalo(neutral.accent());
            }
            case COLLAPSE -> {
                setCore(Material.WHITE_CONCRETE);
                setHalo(Material.WHITE_CONCRETE);
                for (StonePillar pillar : pillars) {
                    pillar.setGemBoost(1.0, 20);
                }
            }
            case REVEAL -> {
                setCore(result.gem());
                setHalo(result.accent());
                haloPrimed = false;
                for (int i = 0; i < pillars.size(); i++) {
                    pillars.get(i).setGemBoost(i == winner ? 3.2 : 1.0, 10);
                }
                world.spawnParticle(Particle.END_ROD, orb, scaled(32), 0.2, 0.2, 0.2, 0.4);
                world.spawnParticle(Particle.DUST, orb, scaled(28), 0.7, 0.7, 0.7, 0.0,
                        new Particle.DustOptions(resultColor, 1.6f));
            }
            case COMPLETION -> {
                for (StonePillar pillar : pillars) {
                    pillar.setGemBoost(1.0, 20);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- the clock

    /**
     * One step of the session (called every session step; the stage only acts every {@value #PERIOD} ticks).
     *
     * @param p           progress 0..1 inside the phase
     * @param cycleIndex  index of the race currently shown during resonance
     * @param stepTicks   ticks since the previous call
     */
    void update(RitualSession.Phase phase, double p, int cycleIndex, int stepTicks) {
        if (closed) {
            return;
        }
        acc += stepTicks;
        if (acc < PERIOD && !force) {
            return;
        }
        int dt = Math.max(1, acc);
        acc = 0;
        force = false;
        time += dt;
        if (!DisplayKit.allValid(all)) {
            // Something removed our displays (a kill command): stop drawing; the session carries on.
            close();
            return;
        }
        int ticks = dt + 1;
        switch (phase) {
            case ACTIVATION -> activation(p, dt, ticks);
            case RESONANCE -> resonance(cycleIndex, dt, ticks);
            case ACCUMULATION -> accumulation(p, dt, ticks);
            case COLLAPSE -> collapse(p, dt, ticks);
            case REVEAL -> reveal(p, dt, ticks);
            case COMPLETION -> completion(p, dt, ticks);
        }
    }

    private void activation(double p, int dt, int ticks) {
        double e = PillarMath.ease(p);
        spin(0.06, 0.07, dt);
        placeCore(0, 0, 0, ticks);
        orbit(PillarMath.lerp(0.8, 2.0, e), PillarMath.lerp(0.6, 1.5, e), 0.38 * e, 0, ticks);
        motes(-1, 26, ticks);
    }

    private void resonance(int index, int dt, int ticks) {
        int i = Math.max(0, Math.min(index, cycle.size() - 1));
        if (!cycle.isEmpty()) {
            AltarVisualSettings.Stone current = cycle.get(i);
            setCore(current.gem());
            setHalo(current.accent());
            int pillar = cyclePillar[i];
            for (int k = 0; k < pillars.size(); k++) {
                pillars.get(k).setGemBoost(k == pillar ? 2.4 : 1.4, 8);
            }
            motes(pillar, 26, ticks);
            dust(current.color(), 2);
        }
        spin(0.12, 0.14, dt);
        placeCore(0.55 + 0.08 * Math.sin(time * 0.35), 0, 0, ticks);
        orbit(2.0, 1.5, 0.38, 0, ticks);
    }

    private void accumulation(double p, int dt, int ticks) {
        double e = PillarMath.ease(p);
        if (!cycle.isEmpty()) {
            // The orb flickers through the four bloodlines, faster and faster.
            AltarVisualSettings.Stone current = cycle.get((int) (time / 8.0) % cycle.size());
            setCore(current.gem());
            setHalo(current.accent());
        }
        for (StonePillar pillar : pillars) {
            pillar.setGemBoost(1.4 + 1.2 * e, 6);
        }
        spin(0.2 + 0.25 * e, 0.22 + 0.25 * e, dt);
        placeCore(PillarMath.lerp(0.7, 1.6, e), 0, 0.03 + 0.05 * e, ticks);
        orbit(PillarMath.lerp(2.0, 1.1, e), PillarMath.lerp(1.5, 0.9, e), 0.38, 0, ticks);
        motes(-2, PillarMath.lerp(20, 12, e), ticks);
        dust(neutralColor(), 3);
        world.spawnParticle(Particle.END_ROD, orb, scaled(1), 0.5, 0.5, 0.5, 0.01);
    }

    private void collapse(double p, int dt, int ticks) {
        double e = PillarMath.ease(p);
        spin(0.5, 0.5, dt);
        placeCore(PillarMath.lerp(1.6, 0.2, e), 0, 0.07 * (1.0 - e), ticks);
        orbit(PillarMath.lerp(1.1, 0.25, e), PillarMath.lerp(0.9, 0.2, e), 0.38 * (1.0 - 0.5 * e), 0, ticks);
        motes(-1, 12, ticks);
    }

    private void reveal(double p, int dt, int ticks) {
        spin(0.08, 0.09, dt);
        double flash = p < 0.1
                ? PillarMath.lerp(0.2, 2.2, PillarMath.ease(p / 0.1))
                : PillarMath.lerp(2.2, 1.25, PillarMath.ease((p - 0.1) / 0.3));
        motes(-1, 26, ticks);
        if (p < 0.4) {
            // The shockwave: eight plates form a ring that sweeps outwards over the platform.
            double w = PillarMath.clamp01(p / 0.4);
            double radius = 0.6 + 7.6 * PillarMath.easeOut(w);
            placeCore(flash, 0, 0.04, ticks);
            ring(radius, 0.45 * (1.0 - w) + 0.03, 0.30 * (1.0 - w) + 0.02, WAVE_Y, ticks, false);
            haloPrimed = false;
            if (p < 0.2) {
                dust(resultColorValue(), 3);
            }
            return;
        }
        double settle = PillarMath.clamp01((p - 0.4) / 0.15);
        if (!haloPrimed) {
            // Park the stones at the orb without interpolation, so they do not fly in from the shockwave.
            for (BlockDisplay h : halo) {
                DisplayKit.set(h, DisplayKit.hidden(0, 0, 0), 0);
            }
            haloPrimed = true;
            java.util.Arrays.fill(haloFrac, 0.0);
            waveFrac = 0.0;
        }
        double sh = PillarMath.ease(settle);
        switch (style) {
            case PILLAR -> {
                double column = PillarMath.lerp(0.0, 7.0, PillarMath.ease((p - 0.1) / 0.5));
                placeCore(1.0, column, 0, ticks);
                orbit(1.6, 1.2, 0.38 * sh, 0.0, ticks);
            }
            case HELIX -> {
                placeCore(1.1 + 0.06 * Math.sin(time * 0.4), 0, 0, ticks);
                helix(0.38 * sh, ticks);
            }
            case SHOCKWAVE -> {
                double pulse = 1.0 + 0.45 * Math.max(0.0, Math.sin(time * 0.5));
                placeCore(pulse, 0, 0.02, ticks);
                repeatingWave(ticks);
            }
            case VORTEX -> {
                placeCore(1.2, 0, 0.06, ticks);
                swirl(ticks);
            }
        }
        if (p < 0.8) {
            dust(resultColorValue(), 2);
        }
    }

    private void completion(double p, int dt, int ticks) {
        double f = 1.0 - PillarMath.ease(p);
        spin(0.08, 0.09, dt);
        placeCore(1.25 * f, 0, 0, ticks);
        orbit(1.6, 1.2, 0.38 * f, 0, ticks);
        motes(-1, 26, ticks);
    }

    // ------------------------------------------------------------------------------------------- placement

    private void spin(double a, double b, int dt) {
        spinA += a * dt / 2.0;
        spinB -= b * dt / 2.0;
    }

    /** Orb: a diamond-turned cube plus a larger glass shell turning the other way; or a column of light. */
    private void placeCore(double size, double column, double shake, int ticks) {
        if (size < 0.01) {
            DisplayKit.set(core, DisplayKit.hidden(0, 0, 0), ticks);
            DisplayKit.set(shell, DisplayKit.hidden(0, 0, 0), ticks);
            return;
        }
        double sx = Math.sin(time * 7.0) * shake;
        double sy = Math.cos(time * 5.0) * shake;
        double sz = Math.sin(time * 3.0) * shake;
        Transformation inner;
        if (column > size) {
            inner = DisplayKit.centered(sx, sy + column / 2.0 - size / 2.0, sz,
                    new Quaternionf().rotationY((float) (spinA * 0.5)), size * 0.6, column, size * 0.6);
        } else {
            inner = DisplayKit.centered(sx, sy, sz,
                    new Quaternionf().rotationY((float) spinA).rotateX(0.6155f).rotateZ((float) (Math.PI / 4.0)),
                    size, size, size);
        }
        DisplayKit.set(core, inner, ticks);
        Quaternionf outer = new Quaternionf().rotationY((float) spinB).rotateX(0.5f).rotateZ(0.3f);
        double shellSize = Math.max(size, 0.6) * 1.45;
        DisplayKit.set(shell, DisplayKit.centered(sx, sy, sz, outer, shellSize, shellSize, shellSize), ticks);
    }

    /** Two rings of four rune stones, turning in opposite directions on different tilts. */
    private void orbit(double ra, double rb, double size, double yOffset, int ticks) {
        for (int i = 0; i < HALO; i++) {
            boolean second = i >= 4;
            double r = second ? rb : ra;
            double angle = (second ? spinB : spinA) + (i % 4) * Math.PI / 2.0;
            double tiltX = second ? -0.2 : 0.35;
            double tiltZ = second ? 0.9 : 0.0;
            Vector3f v = new Vector3f((float) (Math.cos(angle) * r), 0f, (float) (Math.sin(angle) * r))
                    .rotateX((float) tiltX).rotateZ((float) tiltZ);
            Quaternionf q = new Quaternionf().rotateX((float) tiltX).rotateZ((float) tiltZ)
                    .rotateY((float) (-angle + Math.PI / 4.0));
            place(halo[i], v.x, v.y + yOffset, v.z, q, size, ticks);
        }
    }

    /** A closed ring of eight plates; {@code thickness} is radial, {@code height} vertical. */
    private void ring(double radius, double thickness, double height, double y, int ticks, boolean snap) {
        double width = PillarMath.plateWidth(radius, HALO);
        for (int i = 0; i < HALO; i++) {
            double angle = i * TAU / HALO;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            Quaternionf q = new Quaternionf().rotationY((float) Math.atan2(dx, dz));
            Transformation t = DisplayKit.centered(dx * radius, y, dz * radius, q, width, height, thickness);
            DisplayKit.set(halo[i], t, snap ? 0 : ticks);
        }
    }

    private void helix(double size, int ticks) {
        for (int i = 0; i < HALO; i++) {
            int strand = i % 2;
            int level = i / 2;
            double angle = spinA * 1.6 + strand * Math.PI + level * 0.9;
            double y = -1.1 + level * 0.95;
            place(halo[i], Math.cos(angle) * 1.15, y, Math.sin(angle) * 1.15,
                    new Quaternionf().rotationY((float) -angle), size, ticks);
        }
    }

    private void repeatingWave(int ticks) {
        double f = PillarMath.wrap(time / 24.0);
        boolean snap = PillarMath.wrapped(waveFrac, f);
        waveFrac = f;
        ring(0.8 + 5.8 * f, 0.35 * (1.0 - f) + 0.03, 0.25 * (1.0 - f) + 0.02, WAVE_Y, ticks, snap);
    }

    private void swirl(int ticks) {
        for (int i = 0; i < HALO; i++) {
            double f = PillarMath.wrap(time / 34.0 + i / (double) HALO);
            boolean snap = PillarMath.wrapped(haloFrac[i], f);
            haloFrac[i] = f;
            double r = 3.3 * (1.0 - f) + 0.5;
            double angle = spinA * 2.0 + i * Math.PI / 4.0 + f * 4.0;
            double size = 0.42 * Math.sin(Math.PI * f) + 0.05;
            Quaternionf q = new Quaternionf().rotationY((float) -angle).rotateX(0.5f);
            Transformation t = DisplayKit.centered(Math.cos(angle) * r, -1.0 + f * 2.4, Math.sin(angle) * r,
                    q, size, size, size);
            DisplayKit.set(halo[i], t, snap ? 0 : ticks);
        }
    }

    private void place(BlockDisplay display, double x, double y, double z, Quaternionf q, double size,
                       int ticks) {
        if (size < 0.01) {
            DisplayKit.set(display, DisplayKit.hidden(x, y, z), ticks);
        } else {
            DisplayKit.set(display, DisplayKit.centered(x, y, z, q, size, size, size), ticks);
        }
    }

    /**
     * Energy motes flying from the gem of a pillar to the orb along a small arc.
     *
     * @param pillar index of the only pillar that sends (-1 none, -2 all)
     * @param travel ticks one trip takes
     */
    private void motes(int pillar, double travel, int ticks) {
        for (int i = 0; i < pillars.size(); i++) {
            boolean on = pillar == -2 || pillar == i;
            for (int j = 0; j < 2; j++) {
                int index = i * 2 + j;
                BlockDisplay mote = motes[index];
                if (!on) {
                    if (!moteHidden[index]) {
                        moteHidden[index] = true;
                        DisplayKit.set(mote, DisplayKit.hidden(tops[i].x, tops[i].y, tops[i].z), ticks);
                    }
                    continue;
                }
                moteHidden[index] = false;
                double f = PillarMath.wrap(time / travel + j * 0.5 + i * 0.11);
                boolean snap = PillarMath.wrapped(moteFrac[index], f);
                moteFrac[index] = f;
                double k = Math.pow(f, 1.6);
                double size = 0.26 * (1.0 - 0.55 * f);
                Quaternionf q = new Quaternionf().rotationY((float) (f * TAU)).rotateX(0.6155f);
                Transformation t = DisplayKit.centered(tops[i].x * (1.0 - k), tops[i].y * (1.0 - k)
                        + Math.sin(Math.PI * f) * 0.8, tops[i].z * (1.0 - k), q, size, size, size);
                DisplayKit.set(mote, t, snap ? 0 : ticks);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ misc

    private void setCore(Material material) {
        if (material != coreMaterial) {
            coreMaterial = material;
            core.setBlock(material.createBlockData());
        }
    }

    private void setHalo(Material material) {
        if (material != haloMaterial) {
            haloMaterial = material;
            for (BlockDisplay h : halo) {
                h.setBlock(material.createBlockData());
            }
        }
    }

    private int scaled(int base) {
        return multiplier <= 0.0 ? 0 : Math.max(1, (int) Math.round(base * multiplier));
    }

    private void dust(Color color, int base) {
        int count = scaled(base);
        if (count > 0) {
            world.spawnParticle(Particle.DUST, orb, count, 0.6, 0.6, 0.6, 0.0, new Particle.DustOptions(color, 1.1f));
        }
    }

    private void dust(TextColor color, int base) {
        dust(Color.fromRGB(color.value()), base);
    }

    private static Color neutralColor() {
        return Color.fromRGB(0xB388FF);
    }

    private Color resultColorValue() {
        return resultColor;
    }
}
