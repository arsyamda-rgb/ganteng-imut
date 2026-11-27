package com.veyrion.character.awakening;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

/**
 * Validated, immutable view of {@code awakening.yml}. Every key missing from the server owner's file falls back
 * to the bundled file; invalid values fall back to the bundled value with a warning. Rituals keep the instance
 * they started with, so a reload never changes a running ritual.
 */
public record AwakeningSettings(
        boolean enabled,
        boolean allowCommandRoll,
        Altar altar,
        AltarVisualSettings altarVisual,
        Timing timing,
        Movement movement,
        Effects effects,
        BossBarSpec bossBar,
        Gui gui,
        Stage activation,
        Stage resonance,
        Stage accumulation,
        Stage collapse,
        List<String> resonanceRaces,
        Map<String, RaceVisual> races,
        RaceVisual fallbackVisual,
        Map<String, String> messages,
        Cinematic cinematic) {

    public enum RevealStyle { PILLAR, HELIX, SHOCKWAVE, VORTEX }

    /**
     * The camera cutscene of the awakening (the player is moved into a camera, not given a free cam).
     *
     * @param suppressTitles   no big titles during the cutscene (the subtitle line and the boss bar remain)
     * @param skipGraceTicks   ticks after the start in which Shift does not skip (a held Shift must not skip at once)
     * @param skipChatButton   send a clickable skip button to the chat
     * @param skipHint         MiniMessage added to the boss bar title
     * @param skipButton       MiniMessage text of the clickable chat button
     * @param shots            camera shots in order; their weights share the length of the whole ritual
     * @param subtitle         the film subtitle
     * @param dialogue         race id to the line spoken at the reveal
     * @param defaultDialogue  line for a race without an entry
     * @param playerDouble     the visible copy of the player left standing during the cutscene
     */
    public record Cinematic(boolean enabled, boolean suppressTitles, int skipGraceTicks, boolean skipChatButton,
                            String skipHint, String skipButton, List<CameraMath.Shot> shots, Subtitle subtitle,
                            Map<String, String> dialogue, String defaultDialogue, DoubleSpec playerDouble) {

        public String dialogueFor(String raceId) {
            String line = dialogue.get(raceId.toLowerCase(Locale.ROOT));
            return line != null && !line.isBlank() ? line : defaultDialogue;
        }
    }

    /**
     * The copy of the player (packets, needs ProtocolLib) that stands where the player was while the real player
     * is the invisible camera.
     *
     * @param enabled      show the copy at all
     * @param equipment    copy armor and held items
     * @param viewerRadius other players within this many blocks of the altar also see the copy
     */
    public record DoubleSpec(boolean enabled, boolean equipment, double viewerRadius) {}

    /**
     * The film subtitle: a small centred line just above the hotbar on a translucent black bar.
     *
     * @param delayTicks        wait after the reveal starts
     * @param backgroundPercent opacity of the black bar (60-70 is the film look)
     * @param distance          how far in front of the camera the line floats (blocks)
     * @param offsetY           height of the line relative to the middle of the screen (negative = lower)
     * @param scale             text size
     * @param lineWidth         width in pixels before the text wraps
     */
    public record Subtitle(boolean enabled, int fadeIn, int stay, int fadeOut, int delayTicks, int backgroundPercent,
                           double distance, double offsetY, double scale, int lineWidth) {}

    public record Altar(long cooldownMs, double maxStartDistance, double centerOffsetY, double leashRadius) {}

    /** All lengths are in ticks. */
    public record Timing(int tickInterval, int activation, int resonancePerRace, int accumulation, int collapse,
                         int reveal, int completion) {}

    public record Movement(boolean lock, boolean lockSpeed, boolean protectFromDamage) {}

    public record Effects(double radius, double height, double multiplier, int maxParticlesPerStep) {}

    public record BossBarSpec(boolean enabled, String title, BossBar.Color color, BossBar.Overlay overlay) {}

    public record Button(Material material, String name, List<String> lore) {}

    public record Gui(String title, Button begin, Button cancel, Button info) {}

    /** One generic stage. Unused parts are NONE / empty. */
    public record Stage(TitleSpec title, ParticleSpec particle, ParticleSpec accent, SoundSpec sound,
                        SoundSpec secondSound, int soundEveryTicks, int burstCount) {}

    public record Reveal(RevealStyle style, ParticleSpec primary, ParticleSpec secondary, SoundSpec sound,
                         SoundSpec accentSound, TitleSpec title, String lore) {}

    public record RaceVisual(@Nullable TextColor color, int sides, boolean star, ParticleSpec particle,
                             ParticleSpec accent, SoundSpec sound, TitleSpec title, Reveal reveal) {}

    public RaceVisual visualFor(String raceId) {
        RaceVisual visual = races.get(raceId.toLowerCase(Locale.ROOT));
        return visual != null ? visual : fallbackVisual;
    }

    /** Raw MiniMessage text of a message key (see the {@code messages} section). */
    public String message(String key) {
        String value = messages.get(key);
        return value != null ? value : "<red>Missing awakening message: " + key;
    }

    /** Ticks from ritual start until the reveal ends (the whole animation), for the given number of races. */
    public int totalTicks(int raceCount) {
        return timing.activation() + timing.resonancePerRace() * raceCount + timing.accumulation()
                + timing.collapse() + timing.reveal() + timing.completion();
    }

    // ------------------------------------------------------------------------------------------------ parsing

    /**
     * @param user    the server owner's file
     * @param bundled the file bundled in the jar (also attached to {@code user} as its defaults)
     */
    public static AwakeningSettings parse(YamlConfiguration user, YamlConfiguration bundled, Logger logger) {
        // A race that only exists in the owner's file inherits every missing key from 'default-race'.
        // ('bundled' must be a private, freshly loaded copy: it is modified here.)
        ConfigurationSection template = bundled.getConfigurationSection("default-race");
        ConfigurationSection ownRaces = user.getConfigurationSection("races");
        if (template != null && ownRaces != null) {
            for (String key : ownRaces.getKeys(false)) {
                if (bundled.isConfigurationSection("races." + key)) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : template.getValues(true).entrySet()) {
                    if (!(entry.getValue() instanceof ConfigurationSection)) {
                        bundled.set("races." + key + "." + entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        user.setDefaults(bundled);
        Reader r = new Reader(user, bundled, logger);

        Altar altar = new Altar(
                r.longValue("altar.interaction-cooldown-ms", 0, 60_000),
                r.number("altar.max-start-distance", 1, 64),
                r.number("altar.center-offset-y", -2, 10),
                r.number("altar.leash-radius", 1.5, 64));

        AltarVisualSettings altarVisual = parseAltarVisual(r, user, bundled, logger);

        Timing timing = new Timing(
                r.integer("ritual.tick-interval", 1, 10),
                r.integer("ritual.activation-ticks", 0, 6000),
                r.integer("ritual.resonance-ticks-per-race", 0, 6000),
                r.integer("ritual.accumulation-ticks", 0, 6000),
                r.integer("ritual.collapse-ticks", 0, 6000),
                r.integer("ritual.reveal-ticks", 20, 6000),
                r.integer("ritual.completion-ticks", 0, 6000));

        Movement movement = new Movement(
                user.getBoolean("movement.lock", true),
                user.getBoolean("movement.lock-speed", true),
                user.getBoolean("movement.protect-from-damage", true));

        Effects effects = new Effects(
                r.number("effects.radius", 0.5, 16),
                r.number("effects.height", 0.5, 16),
                r.number("effects.particle-multiplier", 0.0, 10),
                r.integer("effects.max-particles-per-step", 0, 2000));

        BossBarSpec bossBar = new BossBarSpec(
                user.getBoolean("boss-bar.enabled", true),
                r.text("boss-bar.title"),
                r.enumValue("boss-bar.color", BossBar.Color.class),
                r.enumValue("boss-bar.overlay", BossBar.Overlay.class));

        Gui gui = new Gui(r.text("gui.title"),
                r.button("gui.begin"), r.button("gui.cancel"), r.button("gui.info"));

        Stage activation = new Stage(
                r.title("stages.activation.title"),
                r.particle("stages.activation.particle"),
                ParticleSpec.NONE,
                r.sound("stages.activation.sound"),
                r.sound("stages.activation.ambient-sound"),
                r.integer("stages.activation.ambient-every-ticks", 1, 6000),
                0);
        Stage resonance = new Stage(
                r.title("stages.resonance.title"),
                ParticleSpec.NONE, ParticleSpec.NONE, SoundSpec.NONE, SoundSpec.NONE, 1, 0);
        Stage accumulation = new Stage(
                r.title("stages.accumulation.title"),
                r.particle("stages.accumulation.particle"),
                r.particle("stages.accumulation.accent"),
                r.sound("stages.accumulation.sound"),
                SoundSpec.NONE,
                r.integer("stages.accumulation.sound-every-ticks", 1, 6000),
                0);
        Stage collapse = new Stage(
                r.title("stages.collapse.title"),
                r.particle("stages.collapse.particle"),
                r.particle("stages.collapse.burst-particle"),
                r.sound("stages.collapse.sound"),
                r.sound("stages.collapse.burst-sound"),
                1,
                r.integer("stages.collapse.burst-count", 0, 1000));

        List<String> resonanceRaces = new ArrayList<>();
        for (String id : user.getStringList("stages.resonance.races")) {
            String clean = id.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty() && !resonanceRaces.contains(clean)) {
                resonanceRaces.add(clean);
            }
        }
        if (resonanceRaces.isEmpty()) {
            logger.warning("awakening.yml: 'stages.resonance.races' is empty; using the bundled list.");
            for (String id : bundled.getStringList("stages.resonance.races")) {
                resonanceRaces.add(id.toLowerCase(Locale.ROOT));
            }
        }

        Set<String> raceIds = new LinkedHashSet<>();
        addKeys(raceIds, user.getConfigurationSection("races"));
        addKeys(raceIds, bundled.getConfigurationSection("races"));
        Map<String, RaceVisual> races = new LinkedHashMap<>();
        for (String id : raceIds) {
            races.put(id.toLowerCase(Locale.ROOT), r.raceVisual("races." + id));
        }
        RaceVisual fallback = r.raceVisual("default-race");

        Map<String, String> messages = new LinkedHashMap<>();
        collectMessages(messages, bundled.getConfigurationSection("messages"), "");
        collectMessages(messages, user.getConfigurationSection("messages"), "");

        return new AwakeningSettings(user.getBoolean("enabled", true), user.getBoolean("allow-command-roll", true),
                altar, altarVisual, timing, movement, effects, bossBar, gui, activation, resonance, accumulation, collapse,
                List.copyOf(resonanceRaces), races, fallback, Map.copyOf(messages),
                parseCinematic(r, user, bundled, logger));
    }

    private static Cinematic parseCinematic(Reader r, YamlConfiguration user, YamlConfiguration bundled, Logger logger) {
        String base = "cinematic";
        List<CameraMath.Shot> shots = readShots(user.getMapList(base + ".shots"), logger);
        if (shots.isEmpty()) {
            logger.warning("awakening.yml: 'cinematic.shots' has no usable shot; using the bundled shots.");
            shots = readShots(bundled.getMapList(base + ".shots"), logger);
        }
        double distance = r.number(base + ".subtitle.distance", -4, 4);
        if (Math.abs(distance) < 0.2) {
            logger.warning("awakening.yml: 'cinematic.subtitle.distance' must not be close to 0; using 1.0.");
            distance = 1.0;
        }
        Subtitle subtitle = new Subtitle(
                user.getBoolean(base + ".subtitle.enabled", true),
                r.integer(base + ".subtitle.fade-in-ticks", 0, 100),
                r.integer(base + ".subtitle.stay-ticks", 10, 1200),
                r.integer(base + ".subtitle.fade-out-ticks", 0, 100),
                r.integer(base + ".subtitle.delay-ticks", 0, 1200),
                r.integer(base + ".subtitle.background-percent", 0, 100),
                distance,
                r.number(base + ".subtitle.offset-y", -3, 3),
                r.number(base + ".subtitle.scale", 0.05, 2),
                r.integer(base + ".subtitle.line-width", 100, 2000));
        Map<String, String> dialogue = new LinkedHashMap<>();
        collectMessages(dialogue, bundled.getConfigurationSection(base + ".dialogue"), "");
        collectMessages(dialogue, user.getConfigurationSection(base + ".dialogue"), "");
        Map<String, String> lowered = new LinkedHashMap<>();
        dialogue.forEach((key, value) -> lowered.put(key.toLowerCase(Locale.ROOT), value));
        return new Cinematic(
                user.getBoolean(base + ".enabled", true),
                user.getBoolean(base + ".suppress-titles", true),
                r.integer(base + ".skip.grace-ticks", 0, 600),
                user.getBoolean(base + ".skip.chat-button", true),
                r.text(base + ".skip.hint"),
                r.text(base + ".skip.button"),
                List.copyOf(shots), subtitle, Map.copyOf(lowered), r.text(base + ".dialogue-default"),
                new DoubleSpec(user.getBoolean(base + ".player-double.enabled", true),
                        user.getBoolean(base + ".player-double.equipment", true),
                        r.number(base + ".player-double.viewer-radius", 0, 128)));
    }

    private static List<CameraMath.Shot> readShots(List<Map<?, ?>> raw, Logger logger) {
        List<CameraMath.Shot> shots = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> entry : raw) {
            index++;
            try {
                double weight = number(entry.get("weight"), 0.1, 100, "weight");
                CameraMath.Ease ease = CameraMath.Ease.valueOf(String.valueOf(entry.get("ease")).trim().toUpperCase(Locale.ROOT));
                shots.add(new CameraMath.Shot(weight, ease, pose(entry.get("from")), pose(entry.get("to"))));
            } catch (IllegalArgumentException | ClassCastException e) {
                logger.warning("awakening.yml: 'cinematic.shots' entry " + index + " is skipped (" + e.getMessage() + ").");
            }
            if (shots.size() >= 16) {
                break;
            }
        }
        return shots;
    }

    private static CameraMath.Pose pose(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("'from' and 'to' must be sections");
        }
        return new CameraMath.Pose(number(map.get("radius"), 0.5, 16, "radius"),
                number(map.get("angle"), -3600, 3600, "angle"),
                number(map.get("height"), -4, 16, "height"),
                number(map.get("look"), -4, 8, "look"));
    }

    private static double number(@Nullable Object value, double min, double max, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("'" + name + "' must be a number");
        }
        double v = number.doubleValue();
        if (!Double.isFinite(v) || v < min || v > max) {
            throw new IllegalArgumentException("'" + name + "' must be between " + min + " and " + max);
        }
        return v;
    }


    /** The {@code altar-visual} section. Stones missing from {@code order} or broken are skipped with a warning. */
    private static AltarVisualSettings parseAltarVisual(Reader r, YamlConfiguration user, YamlConfiguration bundled,
                                                         Logger logger) {
        String base = "altar-visual";
        double stump = r.number(base + ".stump-height", 0.3, 3);
        double tall = r.number(base + ".pillar-height", 1.5, 12);
        if (tall <= stump) {
            logger.warning("awakening.yml: 'altar-visual.pillar-height' must be higher than 'stump-height'; using the defaults.");
            stump = 0.9;
            tall = 4.8;
        }
        List<AltarVisualSettings.Stone> stones = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : user.getStringList(base + ".order")) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || !seen.add(id) || stones.size() >= 8) {
                continue;
            }
            String path = base + ".stones." + id;
            if (!user.isConfigurationSection(path)) {
                logger.warning("awakening.yml: '" + path + "' is missing; that stone is skipped.");
                continue;
            }
            String rune = r.text(path + ".rune");
            stones.add(new AltarVisualSettings.Stone(id, r.color(path + ".color"), r.block(path + ".accent"),
                    r.block(path + ".gem"), rune.isEmpty() ? "*" : rune));
        }
        if (stones.isEmpty()) {
            logger.warning("awakening.yml: 'altar-visual.order' has no usable stone; the pillars are disabled.");
        }
        return new AltarVisualSettings(
                user.getBoolean(base + ".enabled", true) && !stones.isEmpty(),
                r.number(base + ".radius", 1, 32),
                r.number(base + ".exit-margin", 0, 8),
                r.integer(base + ".lower-delay-ticks", 0, 1200),
                r.number(base + ".stone-distance", 1.5, 16),
                r.number(base + ".angle-offset-degrees", -360, 360),
                r.number(base + ".base-offset-y", -4, 4),
                r.integer(base + ".rise-ticks", 10, 1200),
                r.integer(base + ".lower-ticks", 10, 1200),
                r.integer(base + ".segments", 1, 24),
                stump, tall,
                (float) r.number(base + ".view-range", 0.1, 10),
                r.integer(base + ".ambient-every-ticks", 2, 200),
                r.integer(base + ".ambient-particles", 0, 20),
                r.sound(base + ".sounds.rise-start"),
                r.sound(base + ".sounds.motion-step"),
                r.sound(base + ".sounds.activated"),
                r.sound(base + ".sounds.lowered"),
                List.copyOf(stones),
                parseCore(r, user, bundled, base + ".core"));
    }

    private static AltarVisualSettings.CoreSettings parseCore(Reader r, YamlConfiguration user,
                                                               YamlConfiguration bundled, String path) {
        double hover = r.number(path + ".hover-height", 0.8, 8);
        double dormant = Math.min(r.number(path + ".dormant-height", 0.2, 8), hover);
        return new AltarVisualSettings.CoreSettings(
                user.getBoolean(path + ".enabled", bundled.getBoolean(path + ".enabled", true)),
                r.number(path + ".size", 0.4, 3),
                hover, dormant,
                r.number(path + ".orbit-radius", 0.4, 4),
                r.number(path + ".orbit-speed", 0, 5),
                r.integer(path + ".runes", 0, 8),
                r.integer(path + ".flow-motes", 0, 4),
                user.getBoolean(path + ".glow", bundled.getBoolean(path + ".glow", true)),
                r.color(path + ".glow-color"),
                user.getBoolean(path + ".trail", bundled.getBoolean(path + ".trail", true)),
                r.block(path + ".body"),
                r.block(path + ".inner"),
                r.block(path + ".tip"),
                r.block(path + ".shard"),
                r.integer(path + ".shards", 0, 12),
                r.number(path + ".hitbox-width", 0.5, 6),
                r.number(path + ".hitbox-height", 0.5, 8));
    }

    private static void addKeys(Set<String> target, @Nullable ConfigurationSection section) {
        if (section != null) {
            target.addAll(section.getKeys(false));
        }
    }

    private static void collectMessages(Map<String, String> target, @Nullable ConfigurationSection section,
                                        String prefix) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                collectMessages(target, section.getConfigurationSection(key), prefix + key + ".");
            } else {
                String value = section.getString(key);
                if (value != null) {
                    target.put(prefix + key, value);
                }
            }
        }
    }

    /** Typed reads with range checks; bad values fall back to the bundled value. */
    private static final class Reader {
        private final YamlConfiguration user;
        private final YamlConfiguration bundled;
        private final Logger logger;

        Reader(YamlConfiguration user, YamlConfiguration bundled, Logger logger) {
            this.user = user;
            this.bundled = bundled;
            this.logger = logger;
        }

        private void warn(String path, String problem) {
            logger.warning("awakening.yml: '" + path + "' " + problem + "; using the default.");
        }

        int integer(String path, int min, int max) {
            int value = user.getInt(path, bundled.getInt(path));
            if (value < min || value > max) {
                warn(path, "must be between " + min + " and " + max);
                return Math.max(min, Math.min(max, bundled.getInt(path)));
            }
            return value;
        }

        long longValue(String path, long min, long max) {
            long value = user.getLong(path, bundled.getLong(path));
            if (value < min || value > max) {
                warn(path, "must be between " + min + " and " + max);
                return Math.max(min, Math.min(max, bundled.getLong(path)));
            }
            return value;
        }

        double number(String path, double min, double max) {
            double value = user.getDouble(path, bundled.getDouble(path));
            if (!Double.isFinite(value) || value < min || value > max) {
                warn(path, "must be between " + min + " and " + max);
                return Math.max(min, Math.min(max, bundled.getDouble(path)));
            }
            return value;
        }

        String text(String path) {
            String value = user.getString(path);
            return value != null ? value : bundled.getString(path, "");
        }

        <E extends Enum<E>> E enumValue(String path, Class<E> type) {
            String raw = user.getString(path, "");
            try {
                return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                if (!raw.isBlank()) {
                    warn(path, "is not a valid " + type.getSimpleName());
                }
                try {
                    return Enum.valueOf(type, bundled.getString(path, "").trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException again) {
                    return type.getEnumConstants()[0];
                }
            }
        }


        Material block(String path) {
            Material material = Material.matchMaterial(user.getString(path, ""));
            if (material == null || material.isAir() || !material.isBlock()) {
                warn(path, "is not a valid block");
                material = Material.matchMaterial(bundled.getString(path, "STONE"));
                if (material == null || !material.isBlock()) {
                    material = Material.STONE;
                }
            }
            return material;
        }

        TextColor color(String path) {
            TextColor value = parseColor(user.getString(path, ""));
            if (value == null) {
                warn(path, "is not a valid color");
                value = parseColor(bundled.getString(path, "#FFFFFF"));
            }
            return value != null ? value : NamedTextColor.WHITE;
        }

        Button button(String path) {
            Material material = Material.matchMaterial(user.getString(path + ".material", ""));
            if (material == null || material.isAir() || !material.isItem()) {
                warn(path + ".material", "is not a valid item");
                material = Material.matchMaterial(bundled.getString(path + ".material", "STONE"));
                if (material == null) {
                    material = Material.STONE;
                }
            }
            List<String> lore = user.contains(path + ".lore")
                    ? user.getStringList(path + ".lore") : bundled.getStringList(path + ".lore");
            return new Button(material, text(path + ".name"), List.copyOf(lore));
        }

        TitleSpec title(String path) {
            return new TitleSpec(text(path + ".title"), text(path + ".subtitle"),
                    integer(path + ".fade-in", 0, 600), integer(path + ".stay", 0, 6000),
                    integer(path + ".fade-out", 0, 600));
        }

        SoundSpec sound(String path) {
            String key = user.getString(path + ".key", "");
            if (!key.isBlank() && NamespacedKey.fromString(key.trim().toLowerCase(Locale.ROOT)) == null) {
                warn(path + ".key", "is not a valid sound key");
                return SoundSpec.NONE;
            }
            float volume = (float) number(path + ".volume", 0, 10);
            float pitch = (float) number(path + ".pitch", 0.5, 2.0);
            return new SoundSpec(key.trim().toLowerCase(Locale.ROOT), volume, pitch);
        }

        ParticleSpec particle(String path) {
            String raw = user.getString(path + ".type", "");
            Particle type;
            try {
                type = Particle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                if (!ParticleSpec.supported(type)) {
                    warn(path + ".type", "needs extra data and is not supported (use a plain particle or DUST)");
                    type = Particle.END_ROD;
                }
            } catch (IllegalArgumentException e) {
                warn(path + ".type", "is not a valid particle");
                type = Particle.END_ROD;
            }
            TextColor color = null;
            String colorRaw = user.getString(path + ".color", "");
            if (!colorRaw.isBlank()) {
                color = parseColor(colorRaw);
                if (color == null) {
                    warn(path + ".color", "is not a valid color");
                }
            }
            return new ParticleSpec(type, integer(path + ".count", 0, 100),
                    number(path + ".spread", 0, 10), number(path + ".speed", 0, 10), color,
                    (float) number(path + ".size", 0.1, 5));
        }

        RaceVisual raceVisual(String base) {
            TextColor color = null;
            String colorRaw = user.getString(base + ".color", "");
            if (!colorRaw.isBlank()) {
                color = parseColor(colorRaw);
                if (color == null) {
                    warn(base + ".color", "is not a valid color");
                }
            }
            String shape = base + ".resonance.shape";
            Reveal reveal = new Reveal(
                    enumValue(base + ".reveal.style", RevealStyle.class),
                    particle(base + ".reveal.particle"),
                    particle(base + ".reveal.secondary"),
                    sound(base + ".reveal.sound"),
                    sound(base + ".reveal.accent-sound"),
                    title(base + ".reveal.title"),
                    text(base + ".reveal.lore"));
            return new RaceVisual(color, integer(shape + ".sides", 3, 12), user.getBoolean(shape + ".star", false),
                    particle(base + ".resonance.particle"), particle(base + ".resonance.accent"),
                    sound(base + ".resonance.sound"), title(base + ".resonance.title"), reveal);
        }

        private static @Nullable TextColor parseColor(String raw) {
            String value = raw.trim();
            if (value.startsWith("#")) {
                return TextColor.fromHexString(value);
            }
            return NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
        }
    }
}
