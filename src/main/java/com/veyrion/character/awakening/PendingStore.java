package com.veyrion.character.awakening;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

/**
 * Rituals that have a stored race but did not reach their ending (disconnect, crash, shutdown). It exists only
 * to tell the player their race on rejoin and to restore walk/fly speed and the cutscene camera (game mode, flight,
 * position) after a crash. It never decides or
 * stores a race: the race lives in the database. Entries are removed when the ritual completes.
 *
 * <p>The map is main-thread only; file writes run on a private thread so the main thread never waits on disk.
 */
public final class PendingStore {

    /** @param walkSpeed original walk speed to restore, or null if it was never changed */
    public record Entry(String race, double chance, @Nullable Float walkSpeed, @Nullable Float flySpeed,
                        @Nullable CameraSnapshot camera) {

        /** Entry without a camera (earlier stages). */
        public Entry(String race, double chance, @Nullable Float walkSpeed, @Nullable Float flySpeed) {
            this(race, chance, walkSpeed, flySpeed, null);
        }
    }

    private final Logger logger;
    private final Path file;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VeyrionCharacter-Awakening-IO");
        thread.setDaemon(true);
        return thread;
    });
    private final Object writeLock = new Object();

    public PendingStore(Logger logger, File dataFolder) {
        this.logger = logger;
        this.file = new File(dataFolder, "awakening-pending.yml").toPath();
    }

    public void load() {
        entries.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            logger.log(Level.WARNING, "Could not read awakening-pending.yml; ignoring it.", e);
            return;
        }
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            String race = section == null ? null : section.getString("race");
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (race == null || race.isBlank()) {
                continue;
            }
            Float walk = section.contains("walk-speed") ? (float) section.getDouble("walk-speed") : null;
            Float fly = section.contains("fly-speed") ? (float) section.getDouble("fly-speed") : null;
            CameraSnapshot camera = null;
            ConfigurationSection cam = section.getConfigurationSection("camera");
            if (cam != null) {
                CameraSnapshot parsed = new CameraSnapshot(cam.getString("game-mode", ""), cam.getString("world", ""),
                        cam.getDouble("x"), cam.getDouble("y"), cam.getDouble("z"), (float) cam.getDouble("yaw"),
                        (float) cam.getDouble("pitch"), cam.getBoolean("allow-flight"), cam.getBoolean("flying"));
                if (parsed.valid()) {
                    camera = parsed;
                } else {
                    logger.warning("awakening-pending.yml: the camera snapshot of " + key + " is invalid; ignored.");
                }
            }
            entries.put(uuid, new Entry(race, section.getDouble("chance"), walk, fly, camera));
        }
    }

    public @Nullable Entry get(UUID uuid) {
        return entries.get(uuid);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void put(UUID uuid, Entry entry) {
        entries.put(uuid, entry);
        save();
    }

    public void remove(UUID uuid) {
        if (entries.remove(uuid) != null) {
            save();
        }
    }

    /** Snapshots on the calling (main) thread, writes on the IO thread. */
    private void save() {
        String snapshot = snapshot();
        try {
            io.execute(() -> write(snapshot));
        } catch (RejectedExecutionException e) {
            write(snapshot);
        }
    }

    private String snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> item : entries.entrySet()) {
            String base = item.getKey().toString() + ".";
            Entry entry = item.getValue();
            yaml.set(base + "race", entry.race());
            yaml.set(base + "chance", entry.chance());
            if (entry.walkSpeed() != null) {
                yaml.set(base + "walk-speed", entry.walkSpeed().doubleValue());
            }
            if (entry.flySpeed() != null) {
                yaml.set(base + "fly-speed", entry.flySpeed().doubleValue());
            }
            CameraSnapshot camera = entry.camera();
            if (camera != null) {
                yaml.set(base + "camera.game-mode", camera.gameMode());
                yaml.set(base + "camera.world", camera.world());
                yaml.set(base + "camera.x", camera.x());
                yaml.set(base + "camera.y", camera.y());
                yaml.set(base + "camera.z", camera.z());
                yaml.set(base + "camera.yaw", (double) camera.yaw());
                yaml.set(base + "camera.pitch", (double) camera.pitch());
                yaml.set(base + "camera.allow-flight", camera.allowFlight());
                yaml.set(base + "camera.flying", camera.flying());
            }
        }
        return yaml.saveToString();
    }

    private void write(String content) {
        synchronized (writeLock) {
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Could not write awakening-pending.yml", e);
            }
        }
    }

    /** Waits for queued writes, then writes the final state. Call on plugin disable. */
    public void close() {
        String finalState = snapshot();
        boolean nothingToWrite = entries.isEmpty() && !Files.exists(file);
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("Timed out waiting for awakening-pending.yml writes.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!nothingToWrite) {
            write(finalState);
        }
    }
}
