package com.veyrion.character.npc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

/**
 * {@code npcs.yml}: the permanent copy of every NPC. Every change is saved right away: the YAML text is built on
 * the calling (main) thread from an immutable snapshot, and written by one background thread to a temp file that
 * then replaces the real file, so a crash never leaves a half-written file. Writes are in order; a failed write is
 * logged and the next change tries again with the full state.
 */
public final class NpcStore {

    private final Logger logger;
    private final File file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VeyrionCharacter-npc-writer");
        thread.setDaemon(true);
        return thread;
    });

    public NpcStore(Logger logger, File dataFolder) {
        this.logger = logger;
        this.file = new File(dataFolder, "npcs.yml");
    }

    /**
     * Reads npcs.yml. A missing file means no NPCs. Unusable entries are skipped with a warning and the file is
     * left alone (it is only rewritten when something changes).
     *
     * @return null when the file exists but cannot be read at all (the caller must then NOT save over it)
     */
    public @Nullable Map<String, NpcData> load() {
        Map<String, NpcData> result = new LinkedHashMap<>();
        if (!file.isFile()) {
            return result;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            logger.log(Level.SEVERE, "Could not read npcs.yml; NPCs are NOT loaded and the file is left untouched. "
                    + "Fix or remove the file and run /vnpc reload.", e);
            return null;
        }
        ConfigurationSection section = yaml.getConfigurationSection("npcs");
        if (section == null) {
            return result;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            NpcData data = entry == null ? null : NpcData.fromMap(id, entry.getValues(false));
            if (data == null) {
                logger.warning("npcs.yml: entry '" + id + "' is unusable (bad id, world or coordinates); skipped.");
            } else if (result.putIfAbsent(data.id(), data) != null) {
                logger.warning("npcs.yml: duplicate entry '" + id + "'; the first one is used.");
            }
        }
        return result;
    }

    /** Saves the full set in the background. Call from the main thread with the current state. */
    public void save(Collection<NpcData> all) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (NpcData data : all) {
            yaml.createSection("npcs." + data.id(), data.toMap());
        }
        String text = yaml.saveToString();
        try {
            writer.execute(() -> write(text));
        } catch (RuntimeException e) {
            write(text); // writer already shut down: write directly
        }
    }

    private void write(String text) {
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save npcs.yml; the change is kept in memory and saved with the next one.", e);
        }
    }

    /** Waits (briefly) for pending writes. Call on disable. */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("npcs.yml was still being written after 5 seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
