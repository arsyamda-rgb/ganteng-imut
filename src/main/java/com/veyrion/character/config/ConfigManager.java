package com.veyrion.character.config;

import org.bukkit.plugin.java.JavaPlugin;

/** Loads {@code config.yml} and exposes the current validated settings. */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private volatile PluginSettings settings;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads config.yml from disk; missing keys fall back to the defaults bundled in the jar. */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.settings = PluginSettings.from(plugin.getConfig(), plugin.getLogger());
    }

    public PluginSettings settings() {
        return settings;
    }
}
