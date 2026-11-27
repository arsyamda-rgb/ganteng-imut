package com.veyrion.character.awakening;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads {@code plugins/VeyrionCharacter/awakening.yml}. A broken file keeps the previous settings. */
public final class AwakeningConfig {

    private static final String RESOURCE = "awakening.yml";

    private final JavaPlugin plugin;
    private volatile AwakeningSettings settings;

    public AwakeningConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public AwakeningSettings settings() {
        return settings;
    }

    /** (Re)reads awakening.yml, creating it with the defaults on first run. */
    public void load() {
        File file = new File(plugin.getDataFolder(), RESOURCE);
        if (!file.exists()) {
            plugin.saveResource(RESOURCE, false);
        }
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            if (settings != null) {
                plugin.getLogger().log(Level.WARNING, "Could not read awakening.yml; keeping the previous settings.", e);
                return;
            }
            plugin.getLogger().log(Level.WARNING, "Could not read awakening.yml; using the bundled defaults.", e);
            user = bundled();
        }
        // The parser modifies the bundled copy, so each parse gets its own.
        this.settings = AwakeningSettings.parse(user, bundled(), plugin.getLogger());
    }

    private YamlConfiguration bundled() {
        try (InputStream stream = plugin.getResource(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Bundled awakening.yml is missing from the plugin jar.");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled awakening.yml", e);
        }
    }
}
