package com.veyrion.character.awakening;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.race.RaceDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * One player's ritual: a single repeating task that plays the cinematic as a pure function of elapsed ticks.
 *
 * <p>The race is already stored in the database before a session exists; the session only displays it. Nothing
 * in here reads or changes the race result, and the soul-resonance sequence is a fixed visual list.
 *
 * <p>The ritual player gets the personal parts (titles, boss bar). The summoning itself is drawn by an
 * {@link AltarStage} made of display entities and the sounds are played in the world, so everybody near the
 * altar sees and hears it. The stage may be null (altar visuals off): the ritual then runs with titles and
 * sounds only. Every session has its own result; only one stage exists at a time.
 */
final class RitualSession implements Runnable {

    enum Phase { ACTIVATION, RESONANCE, ACCUMULATION, COLLAPSE, REVEAL, COMPLETION }

    /** SKIPPED = the player skipped the cutscene; the race was stored long before, so it ends like COMPLETED. */
    enum EndReason { COMPLETED, SKIPPED, QUIT, DEATH, LEASH, WORLD, DISABLE, ERROR }

    /** One entry of the visual-only resonance sequence. */
    private record ResonanceStep(AwakeningSettings.RaceVisual visual, Component name, TextColor color,
                                 TagResolver[] resolvers) {}

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final VeyrionCharacterPlugin plugin;
    private final AwakeningService service;
    private final UUID playerId;
    private final String playerName;
    private final AwakeningSettings cfg;
    private final UUID worldId;
    private final Location ground;
    private final Location center;
    private final double leashSquared;
    private final String raceId;
    private final double chance;
    private final AwakeningSettings.RaceVisual raceVisual;
    private final TextColor raceColor;
    private final TagResolver[] raceResolvers;
    private final List<ResonanceStep> resonance;
    private final @Nullable AltarStage stage;
    private final BossBar bar;
    private final boolean preview;

    private final int interval;
    private final int activationEnd;
    private final int resonanceEnd;
    private final int accumulationEnd;
    private final int collapseEnd;
    private final int revealEnd;
    private final int total;

    private int elapsed;
    private @Nullable Phase lastPhase;
    private int lastResonanceIndex = -1;
    private @Nullable BukkitTask task;
    private boolean ended;
    private boolean barShown;
    private @Nullable Float originalWalkSpeed;
    private @Nullable Float originalFlySpeed;
    private @Nullable CameraRig rig;

    RitualSession(VeyrionCharacterPlugin plugin, AwakeningService service, Player player, AwakeningSettings cfg,
                  Location ground, Location center, RaceDefinition race, double chance,
                  @Nullable AltarStage stage, boolean preview) {
        this.plugin = plugin;
        this.preview = preview;
        this.service = service;
        this.playerId = player.getUniqueId();
        this.playerName = player.getName();
        this.cfg = cfg;
        this.worldId = ground.getWorld().getUID();
        this.ground = ground;
        this.center = center;
        this.leashSquared = cfg.altar().leashRadius() * cfg.altar().leashRadius();
        this.raceId = race.id();
        this.chance = chance;
        this.raceVisual = cfg.visualFor(race.id());
        this.raceColor = raceVisual.color() != null ? raceVisual.color() : race.color();
        this.raceResolvers = resolversFor(race.displayName(), race.description(), raceColor, playerName, chance);
        this.stage = stage;

        // The sequence only shows races that exist in races.yml; it never depends on the result.
        List<ResonanceStep> steps = new ArrayList<>();
        List<String> stepIds = new ArrayList<>();
        for (String id : cfg.resonanceRaces()) {
            RaceDefinition definition = plugin.races().settings().find(id);
            if (definition == null) {
                continue;
            }
            AwakeningSettings.RaceVisual visual = cfg.visualFor(id);
            TextColor color = visual.color() != null ? visual.color() : definition.color();
            steps.add(new ResonanceStep(visual, Component.text(definition.displayName(), color), color,
                    resolversFor(definition.displayName(), definition.description(), color, playerName, 0)));
            stepIds.add(id);
        }
        this.resonance = List.copyOf(steps);
        if (stage != null) {
            stage.setCycle(stepIds);
        }

        this.interval = cfg.timing().tickInterval();
        this.activationEnd = cfg.timing().activation();
        this.resonanceEnd = activationEnd + cfg.timing().resonancePerRace() * resonance.size();
        this.accumulationEnd = resonanceEnd + cfg.timing().accumulation();
        this.collapseEnd = accumulationEnd + cfg.timing().collapse();
        this.revealEnd = collapseEnd + cfg.timing().reveal();
        this.total = revealEnd + cfg.timing().completion();

        this.bar = BossBar.bossBar(MINI.deserialize(cfg.bossBar().title()), 0f, cfg.bossBar().color(),
                cfg.bossBar().overlay());
    }

    static TagResolver[] resolversFor(String name, String description, TextColor shownColor, String player,
                                      double chance) {
        return new TagResolver[] {
                Placeholder.component("race", Component.text(name, shownColor)),
                Placeholder.unparsed("race_name", name),
                Placeholder.unparsed("description", description),
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("chance", String.format(Locale.ROOT, "%.1f", chance * 100))
        };
    }

    UUID playerId() {
        return playerId;
    }

    UUID worldId() {
        return worldId;
    }

    String raceId() {
        return raceId;
    }

    double chance() {
        return chance;
    }

    TagResolver[] raceResolvers() {
        return raceResolvers;
    }

    /** The original speeds, for crash recovery; null until the lock changed them. */
    @Nullable Float originalWalkSpeed() {
        return originalWalkSpeed;
    }

    @Nullable Float originalFlySpeed() {
        return originalFlySpeed;
    }

    /** True for an admin's preview: nothing was rolled, nothing is stored, no message about a race is sent. */
    boolean preview() {
        return preview;
    }

    /** The camera snapshot to keep for crash recovery; null if no camera runs. */
    @Nullable CameraSnapshot cameraSnapshot() {
        CameraRig current = rig;
        return current != null && current.active() ? current.snapshot() : null;
    }

    boolean cameraActive() {
        CameraRig current = rig;
        return current != null && current.active();
    }

    /** Ticks since the cutscene started (0 without a camera). */
    int cameraTicks() {
        CameraRig current = rig;
        return current == null ? 0 : current.ticks();
    }

    /** The reveal lore line, ready to send (used for the interrupted fallback too). */
    @Nullable Component lore() {
        String lore = raceVisual.reveal().lore();
        return lore.isBlank() ? null : MINI.deserialize(lore, raceResolvers);
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    /** Locks movement, shows the boss bar and starts the single repeating task. Main thread. */
    void start(Player player) {
        if (cfg.movement().lock() && cfg.movement().lockSpeed()) {
            originalWalkSpeed = player.getWalkSpeed();
            originalFlySpeed = player.getFlySpeed();
            player.setWalkSpeed(0f);
            player.setFlySpeed(0f);
        }
        startCamera(player);
        if (cfg.bossBar().enabled()) {
            if (cameraActive() && !cfg.cinematic().skipHint().isBlank()) {
                bar.name(MINI.deserialize(cfg.bossBar().title() + "  " + cfg.cinematic().skipHint()));
            }
            player.showBossBar(bar);
            barShown = true;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this, 0L, interval);
    }

    /**
     * Moves the player into the scripted camera. A failure never stops the ritual: it then runs without a camera
     * (the race is already stored), and whatever the failed start changed was undone by the rig itself.
     */
    private void startCamera(Player player) {
        AwakeningSettings.Cinematic cinematic = cfg.cinematic();
        if (!cinematic.enabled()) {
            return;
        }
        try {
            CameraRig candidate = new CameraRig(plugin, player, cinematic, center,
                    CameraRig.baseAngleOf(player, center), total, () -> service.end(this, EndReason.ERROR));
            candidate.start(player);
            rig = candidate;
        } catch (RuntimeException e) {
            rig = null;
            plugin.getLogger().log(Level.SEVERE, "Could not start the awakening camera for " + playerName
                    + "; the ritual runs without it.", e);
            return;
        }
        if (cinematic.skipChatButton() && !cinematic.skipButton().isBlank()) {
            player.sendMessage(MINI.deserialize(cinematic.skipButton())
                    .clickEvent(ClickEvent.runCommand("/race skip"))
                    .hoverEvent(HoverEvent.showText(Component.text("/race skip", NamedTextColor.GRAY))));
        }
    }

    /**
     * Stops the task and removes every temporary effect. Idempotent. {@code player} may be null if the player
     * is gone; speeds are then restored by the pending store on the next join.
     */
    void close(@Nullable Player player, boolean clearTitle) {
        ended = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        CameraRig camera = rig;
        rig = null;
        if (camera != null) {
            // Game mode, flight and position back first: everything below assumes a normal player again.
            camera.close(player);
        }
        if (player != null) {
            if (barShown) {
                player.hideBossBar(bar);
            }
            if (originalWalkSpeed != null) {
                player.setWalkSpeed(originalWalkSpeed);
            }
            if (originalFlySpeed != null) {
                player.setFlySpeed(originalFlySpeed);
            }
            if (clearTitle) {
                player.clearTitle();
            }
        }
        barShown = false;
        originalWalkSpeed = null;
        originalFlySpeed = null;
    }

    boolean locksMovement() {
        return cfg.movement().lock();
    }

    boolean protectsFromDamage() {
        return cfg.movement().protectFromDamage();
    }

    boolean ended() {
        return ended;
    }

    // ------------------------------------------------------------------------------------------------ the task

    @Override
    public void run() {
        if (ended) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            service.end(this, EndReason.QUIT);
            return;
        }
        if (player.isDead()) {
            service.end(this, EndReason.DEATH);
            return;
        }
        if (Bukkit.getWorld(worldId) == null) {
            service.end(this, EndReason.WORLD);
            return;
        }
        // While the camera runs the player's body follows it, so the distance says nothing then.
        if (!player.getWorld().getUID().equals(worldId)
                || (!cameraActive() && player.getLocation().distanceSquared(center) > leashSquared)) {
            service.end(this, EndReason.LEASH);
            return;
        }
        try {
            step(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Awakening ritual of " + playerName + " failed", e);
            service.end(this, EndReason.ERROR);
            return;
        }
        elapsed += interval;
        if (elapsed >= total) {
            service.end(this, EndReason.COMPLETED);
        }
    }

    private Phase phaseAt(int t) {
        if (t < activationEnd) {
            return Phase.ACTIVATION;
        }
        if (t < resonanceEnd) {
            return Phase.RESONANCE;
        }
        if (t < accumulationEnd) {
            return Phase.ACCUMULATION;
        }
        if (t < collapseEnd) {
            return Phase.COLLAPSE;
        }
        if (t < revealEnd) {
            return Phase.REVEAL;
        }
        return Phase.COMPLETION;
    }

    /** True once per {@code every} ticks (always at the first step of a phase boundary multiple). */
    private boolean due(int t, int every) {
        return t % every < interval;
    }

    private void step(Player player) {
        int t = elapsed;
        Phase phase = phaseAt(t);

        if (phase != lastPhase) {
            lastPhase = phase;
            enter(player, phase);
            if (stage != null) {
                stage.enter(phase);
            }
        }
        if (cfg.bossBar().enabled()) {
            bar.progress((float) Math.max(0.0, Math.min(1.0, (double) t / total)));
        }

        double p;
        switch (phase) {
            case ACTIVATION -> {
                p = progress(t, 0, activationEnd);
                if (due(t, cfg.activation().soundEveryTicks())) {
                    cfg.activation().secondSound().broadcast(center, 1.0f);
                }
            }
            case RESONANCE -> {
                p = progress(t, activationEnd, resonanceEnd);
                resonanceStep(player, t);
            }
            case ACCUMULATION -> {
                p = progress(t, resonanceEnd, accumulationEnd);
                if (due(t - resonanceEnd, cfg.accumulation().soundEveryTicks())) {
                    // The pitch climbs with the build-up.
                    cfg.accumulation().sound().broadcast(center, (float) (1.0 + p));
                }
            }
            case COLLAPSE -> p = progress(t, accumulationEnd, collapseEnd);
            case REVEAL -> p = progress(t, collapseEnd, revealEnd);
            default -> p = progress(t, revealEnd, total);
        }
        if (stage != null) {
            stage.update(phase, p, Math.max(0, lastResonanceIndex), interval);
        }
    }

    private static double progress(int t, int from, int to) {
        return to <= from ? 1.0 : Math.max(0.0, Math.min(1.0, (double) (t - from) / (to - from)));
    }

    /** Sounds and titles for the world; the personal titles only go to the ritual player. */
    private void enter(Player player, Phase phase) {
        switch (phase) {
            case ACTIVATION -> {
                showTitle(player, cfg.activation().title());
                cfg.activation().sound().broadcast(center, 1.0f);
            }
            case RESONANCE -> showTitle(player, cfg.resonance().title());
            case ACCUMULATION -> showTitle(player, cfg.accumulation().title());
            case COLLAPSE -> {
                showTitle(player, cfg.collapse().title());
                cfg.collapse().sound().broadcast(center, 1.0f);
            }
            case REVEAL -> {
                // Dimensional collapse ends in a controlled burst, then the real result is revealed.
                cfg.collapse().secondSound().broadcast(center, 1.0f);
                AwakeningSettings.Reveal reveal = raceVisual.reveal();
                reveal.sound().broadcast(center, 1.0f);
                reveal.accentSound().broadcast(center, 1.0f);
                showTitle(player, reveal.title(), raceResolvers);
                showDialogue(player);
                Component lore = lore();
                if (lore != null) {
                    player.sendMessage(lore);
                }
            }
            case COMPLETION -> { }
        }
    }

    /** Big titles are left out while the cutscene runs (the narration is the small subtitle). */
    private void showTitle(Player player, TitleSpec spec, TagResolver... resolvers) {
        if (cameraActive() && cfg.cinematic().suppressTitles()) {
            return;
        }
        spec.show(player, resolvers);
    }

    /** The line spoken at the reveal: a film subtitle, never a title. Display only. */
    private void showDialogue(Player player) {
        AwakeningSettings.Cinematic cinematic = cfg.cinematic();
        if (!cinematic.enabled() || !cinematic.subtitle().enabled()) {
            return;
        }
        Component line = MINI.deserialize(cinematic.dialogueFor(raceId), raceResolvers)
                .colorIfAbsent(NamedTextColor.WHITE);
        CameraRig camera = rig;
        if (camera != null && camera.active()) {
            camera.showSubtitle(line, cinematic.subtitle().delayTicks());
        } else {
            player.sendActionBar(line); // no camera (it failed to start): the plain action bar line
        }
    }

    private void resonanceStep(Player player, int t) {
        if (resonance.isEmpty()) {
            return;
        }
        int perRace = cfg.timing().resonancePerRace();
        int index = Math.min(resonance.size() - 1, (t - activationEnd) / Math.max(1, perRace));
        if (index == lastResonanceIndex) {
            return;
        }
        lastResonanceIndex = index;
        ResonanceStep current = resonance.get(index);
        current.visual().sound().broadcast(center, 1.0f);
        if (index > 0) {
            // The first race shares the moment with the "Soul Resonance" title shown on entering the phase.
            showTitle(player, current.visual().title(), current.resolvers());
        }
    }
}
