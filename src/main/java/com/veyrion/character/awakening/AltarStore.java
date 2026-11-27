package com.veyrion.character.awakening;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * The configured altar, saved in {@code plugins/VeyrionCharacter/altar.yml} so it survives restarts.
 * The altar is the block an admin stood on when running {@code /raceadmin altar set}. Main thread only.
 */
public final class AltarStore {

    /** Where the altar is. The world is stored by id and name, so a renamed world still resolves by id. */
    public record Altar(UUID worldId, String worldName, int x, int y, int z) {

        public @Nullable World world() {
            World byId = Bukkit.getWorld(worldId);
            return byId != null ? byId : Bukkit.getWorld(worldName);
        }

        public boolean matches(Block block) {
            return block.getX() == x && block.getY() == y && block.getZ() == z
                    && (block.getWorld().getUID().equals(worldId) || block.getWorld().getName().equals(worldName));
        }

        /** Middle of the altar block, {@code offsetY} blocks above its top surface. */
        public @Nullable Location anchor(double offsetY) {
            World world = world();
            return world == null ? null : new Location(world, x + 0.5, y + 1.0 + offsetY, z + 0.5);
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private volatile @Nullable Altar altar;

    public AltarStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "altar.yml");
    }

    public @Nullable Altar get() {
        return altar;
    }

    /** Reads altar.yml. A missing file means no altar; a broken file keeps what is in memory. */
    public void load() {
        if (!file.isFile()) {
            altar = null;
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read altar.yml; keeping the current altar.", e);
            return;
        }
        String worldName = yaml.getString("world", "");
        String worldId = yaml.getString("world-id", "");
        if (worldName.isEmpty() || !yaml.contains("x") || !yaml.contains("y") || !yaml.contains("z")) {
            plugin.getLogger().warning("altar.yml is incomplete; no altar is configured.");
            altar = null;
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(worldId);
        } catch (IllegalArgumentException e) {
            id = new UUID(0L, 0L);
        }
        altar = new Altar(id, worldName, yaml.getInt("x"), yaml.getInt("y"), yaml.getInt("z"));
    }

    /** Saves first, then updates memory, so a failed save never leaves a different altar in memory. */
    public boolean set(Block block) {
        Altar value = new Altar(block.getWorld().getUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world", value.worldName());
        yaml.set("world-id", value.worldId().toString());
        yaml.set("x", value.x());
        yaml.set("y", value.y());
        yaml.set("z", value.z());
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save altar.yml", e);
            return false;
        }
        altar = value;
        return true;
    }

    /** @return false if the file could not be deleted (the altar then stays configured) */
    public boolean remove() {
        if (file.exists() && !file.delete()) {
            plugin.getLogger().severe("Could not delete altar.yml");
            return false;
        }
        altar = null;
        return true;
    }
}
