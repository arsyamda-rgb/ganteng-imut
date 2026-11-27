package com.veyrion.character.awakening;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

/**
 * The scripted camera of one ritual. Main thread only.
 *
 * <p>How it works: an invisible marker armor stand is the camera. The player is put in spectator mode attached to
 * it ({@code setSpectatorTarget}), so they see through it but cannot steer it; every tick the stand is moved along
 * the shots of {@link CameraMath}. A second entity, a text display that is only shown to this player, floats in
 * front of the camera and is the film subtitle.
 *
 * <p>Restoring is the important part. {@link #close} always puts the player back (game mode, flight, position),
 * whatever ended the ritual; every step is guarded on its own so one failing step cannot skip the others. The
 * snapshot is also exposed so the service can store it in awakening-pending.yml for a crash.
 */
final class CameraRig implements Runnable {

    private static final Display.Brightness FULL = new Display.Brightness(15, 15);
    /** Ticks in a row the player may be off the camera before the cutscene is given up. */
    private static final int LOST_LIMIT = 60;

    private final VeyrionCharacterPlugin plugin;
    private final UUID playerId;
    private final AwakeningSettings.Cinematic cfg;
    private final World world;
    private final Location center;
    private final double baseAngle;
    private final int totalTicks;
    private final Runnable onLost;
    private final CameraSnapshot snapshot;

    private @Nullable ArmorStand stand;
    private @Nullable TextDisplay subtitle;
    private @Nullable PlayerDouble body;
    private @Nullable BukkitTask task;
    private double eyeHeight;
    private int tick;
    private boolean started;
    private boolean closed;
    private int lost;

    private @Nullable Component cueText;
    private int cueStart = Integer.MAX_VALUE;
    private int shownOpacity = -1;
    private int shownBackground = -1;
    private boolean subtitleFallback;

    /**
     * @param center     the ritual centre
     * @param baseAngle  radians; direction (from the centre) of the side where the player stands
     * @param totalTicks the length of the whole ritual; the shots are spread over it
     * @param onLost     called (once) when the player cannot be kept on the camera
     */
    CameraRig(VeyrionCharacterPlugin plugin, Player player, AwakeningSettings.Cinematic cfg, Location center,
              double baseAngle, int totalTicks, Runnable onLost) {
        this.plugin = plugin;
        this.playerId = player.getUniqueId();
        this.cfg = cfg;
        this.world = center.getWorld();
        this.center = center.clone();
        this.baseAngle = baseAngle;
        this.totalTicks = Math.max(1, totalTicks);
        this.onLost = onLost;
        this.snapshot = capture(player);
    }

    static CameraSnapshot capture(Player player) {
        Location at = player.getLocation();
        return new CameraSnapshot(player.getGameMode().name(), at.getWorld().getName(), at.getX(), at.getY(),
                at.getZ(), at.getYaw(), at.getPitch(), player.getAllowFlight(), player.isFlying());
    }

    CameraSnapshot snapshot() {
        return snapshot;
    }

    /** Direction of the player as seen from the centre; if they stand in the middle, the way they face. */
    static double baseAngleOf(Player player, Location center) {
        Location at = player.getLocation();
        double dx = at.getX() - center.getX();
        double dz = at.getZ() - center.getZ();
        if (dx * dx + dz * dz < 0.25) {
            double yaw = Math.toRadians(at.getYaw());
            // The player looks along (-sin(yaw), cos(yaw)); the camera starts behind them.
            return Math.atan2(Math.cos(yaw), -Math.sin(yaw)) + Math.PI;
        }
        return Math.atan2(dz, dx);
    }

    boolean active() {
        return started && !closed;
    }

    /** Ticks since the cutscene started. */
    int ticks() {
        return tick;
    }

    // ------------------------------------------------------------------------------------------------ start

    /**
     * Spawns the camera and moves the player into it.
     *
     * @throws RuntimeException if anything fails; the caller then runs the ritual without a camera. Whatever was
     *                          already changed is undone before the exception leaves.
     */
    void start(Player player) {
        try {
            Location first = locationAt(0);
            stand = world.spawn(first, ArmorStand.class, a -> {
                a.setPersistent(false);
                a.addScoreboardTag(DisplayKit.TAG);
                a.setInvisible(true);
                a.setMarker(true);
                a.setGravity(false);
                a.setInvulnerable(true);
                a.setSilent(true);
                a.setBasePlate(false);
            });
            eyeHeight = stand.getEyeHeight();
            if (cfg.subtitle().enabled()) {
                spawnSubtitle(player, first);
            }
            spawnBody(player);
            player.setGameMode(GameMode.SPECTATOR);
            player.setSpectatorTarget(stand);
            started = true;
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this, 1L, 1L);
        } catch (RuntimeException e) {
            closed = true;
            discard();
            restore(plugin, player, snapshot);
            throw e;
        }
    }

    /**
     * Leaves a packet copy of the player where they stood: a spectator on the camera is invisible, so without it
     * nobody (the player included) would see them in the scene. A failure only means no copy.
     */
    private void spawnBody(Player player) {
        AwakeningSettings.DoubleSpec spec = cfg.playerDouble();
        PlayerDoubleFactory factory = plugin.playerDoubles();
        if (!spec.enabled() || factory == null) {
            return;
        }
        try {
            PlayerDouble copy = factory.create(player, player.getLocation(), spec.equipment());
            body = copy;
            copy.show(player);
            double radiusSquared = spec.viewerRadius() * spec.viewerRadius();
            for (Player other : world.getPlayers()) {
                if (!other.getUniqueId().equals(playerId) && other.getLocation().distanceSquared(center) <= radiusSquared) {
                    copy.show(other);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Could not create the player copy for the cutscene.", e);
        }
    }

    private void spawnSubtitle(Player player, Location at) {
        AwakeningSettings.Subtitle s = cfg.subtitle();
        try {
            TextDisplay display = world.spawn(at, TextDisplay.class, d -> {
                DisplayKit.prepare(d, 1.0f, FULL);
                d.setVisibleByDefault(false); // nobody else sees it
                d.setBillboard(Display.Billboard.CENTER);
                d.text(Component.empty());
                d.setLineWidth(s.lineWidth());
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(false);
                d.setSeeThrough(true);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                // The transformation is applied after the billboard turn, so it is relative to the player's view:
                // -z is straight ahead, y is up. Small text, just above the hotbar.
                float scale = (float) s.scale();
                d.setTransformation(new Transformation(
                        new Vector3f(0f, (float) s.offsetY(), (float) -s.distance()), new Quaternionf(),
                        new Vector3f(scale, scale, scale), new Quaternionf()));
            });
            player.showEntity(plugin, display);
            subtitle = display;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not create the subtitle display; using the action bar.", e);
            subtitleFallback = true;
        }
    }

    // ------------------------------------------------------------------------------------------------ camera

    private Location locationAt(int atTick) {
        AwakeningSettings.Cinematic c = cfg;
        CameraMath.Pose pose = CameraMath.poseAt(c.shots(), (double) atTick / totalTicks);
        double[] p = CameraMath.position(center.getX(), center.getY(), center.getZ(), baseAngle, pose);
        float[] look = CameraMath.look(p[0], p[1], p[2], center.getX(), center.getY() + pose.look(), center.getZ());
        return new Location(world, p[0], p[1], p[2], look[0], look[1]);
    }

    @Override
    public void run() {
        if (closed) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        ArmorStand camera = stand;
        if (player == null || !player.isOnline() || camera == null) {
            return; // the ritual notices this itself and ends; close() cleans up
        }
        tick++;
        try {
            Location eye = locationAt(Math.min(tick, totalTicks));
            camera.teleport(new Location(world, eye.getX(), eye.getY() - eyeHeight, eye.getZ(), eye.getYaw(),
                    eye.getPitch()), PlayerTeleportEvent.TeleportCause.PLUGIN);
            TextDisplay display = subtitle;
            if (display != null) {
                display.teleport(eye, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            keepOnCamera(player, camera);
            updateSubtitle(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "The awakening camera failed", e);
            onLost.run();
        }
    }

    /** Puts the player back on the camera if something (another plugin, the client) took them off it. */
    private void keepOnCamera(Player player, ArmorStand camera) {
        boolean ok = true;
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
            ok = false;
        }
        Entity target = player.getSpectatorTarget();
        if (target == null || !target.getUniqueId().equals(camera.getUniqueId())) {
            player.setSpectatorTarget(camera);
            ok = false;
        }
        if (ok) {
            lost = 0;
        } else if (++lost > LOST_LIMIT) {
            plugin.getLogger().warning("Could not keep " + player.getName() + " on the awakening camera; ending the cutscene.");
            onLost.run();
        }
    }

    // ---------------------------------------------------------------------------------------------- subtitle

    /** Shows {@code text} as the film subtitle {@code delayTicks} from now (it fades in, stays, fades out). */
    void showSubtitle(Component text, int delayTicks) {
        cueText = text;
        cueStart = tick + Math.max(0, delayTicks);
        shownOpacity = -1;
        shownBackground = -1;
    }

    private void updateSubtitle(Player player) {
        Component text = cueText;
        if (text == null) {
            return;
        }
        AwakeningSettings.Subtitle s = cfg.subtitle();
        int relative = tick - cueStart;
        int total = SubtitleTimeline.total(s.fadeIn(), s.stay(), s.fadeOut());
        if (relative >= total) {
            cueText = null;
            hideSubtitle();
            return;
        }
        double visibility = SubtitleTimeline.visibility(relative, s.fadeIn(), s.stay(), s.fadeOut());
        TextDisplay display = subtitle;
        if (display == null) {
            // Fallback: the action bar (white text just above the hotbar; no bar behind it, no fade).
            if (subtitleFallback && visibility > 0 && relative % 20 == 0) {
                player.sendActionBar(text);
            }
            return;
        }
        int opacity = SubtitleTimeline.textOpacity(visibility);
        int background = SubtitleTimeline.backgroundAlpha(visibility, SubtitleTimeline.alphaForPercent(s.backgroundPercent()));
        if (opacity == shownOpacity && background == shownBackground) {
            return;
        }
        if (opacity == 0) {
            hideSubtitle();
            return;
        }
        if (shownOpacity <= 0) {
            display.text(text);
        }
        display.setTextOpacity((byte) opacity);
        display.setBackgroundColor(Color.fromARGB(background, 0, 0, 0));
        shownOpacity = opacity;
        shownBackground = background;
    }

    private void hideSubtitle() {
        TextDisplay display = subtitle;
        if (display != null && shownOpacity != 0) {
            display.text(Component.empty());
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        }
        shownOpacity = 0;
        shownBackground = 0;
    }

    // ------------------------------------------------------------------------------------------------- close

    /**
     * Stops the camera and puts the player back. Idempotent. {@code player} may be null when they are gone; the
     * caller then keeps {@link #snapshot()} for their next join.
     */
    void close(@Nullable Player player) {
        if (closed) {
            return;
        }
        closed = true;
        discard();
        if (player != null) {
            restore(plugin, player, snapshot);
        }
    }

    private void discard() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        try {
            if (subtitle != null) {
                subtitle.remove();
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not remove the subtitle display", e);
        }
        try {
            if (stand != null) {
                stand.remove();
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not remove the camera entity", e);
        }
        try {
            if (body != null) {
                body.remove();
            }
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Could not remove the player copy", e);
        }
        subtitle = null;
        stand = null;
        body = null;
    }

    /**
     * Gives a player back their game mode, flight and position. Each step is guarded on its own: whatever fails,
     * the rest still runs. Also used on join after a crash.
     */
    static void restore(VeyrionCharacterPlugin plugin, Player player, CameraSnapshot snapshot) {
        try {
            if (player.getSpectatorTarget() != null) {
                player.setSpectatorTarget(null);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Camera restore: could not leave the camera of " + player.getName(), e);
        }
        try {
            if (CameraSnapshot.knownMode(snapshot.gameMode())) {
                player.setGameMode(GameMode.valueOf(snapshot.gameMode()));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Camera restore: could not restore the game mode of " + player.getName(), e);
        }
        try {
            player.setAllowFlight(snapshot.allowFlight());
            if (snapshot.allowFlight() && snapshot.flying()) {
                player.setFlying(true);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Camera restore: could not restore flight of " + player.getName(), e);
        }
        try {
            World back = plugin.getServer().getWorld(snapshot.world());
            if (back != null && !player.isDead()) {
                player.teleport(new Location(back, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(),
                        snapshot.pitch()), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Camera restore: could not move " + player.getName() + " back", e);
        }
    }
}
