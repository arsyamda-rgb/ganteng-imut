package com.veyrion.character.race;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Loads {@code plugins/VeyrionCharacter/races.yml}. Bad values fall back to defaults with a warning; a file
 * that cannot be parsed keeps the previous settings on reload (or the bundled defaults on first load).
 */
public final class RaceRegistry {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final String RESOURCE = "races.yml";

    private final JavaPlugin plugin;
    private final Logger logger;
    private volatile RaceSettings settings;

    public RaceRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public RaceSettings settings() {
        return settings;
    }

    /** (Re)reads races.yml. Never touches stored character data. */
    public void load() {
        File file = new File(plugin.getDataFolder(), RESOURCE);
        if (!file.exists()) {
            plugin.saveResource(RESOURCE, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            if (settings != null) {
                logger.log(Level.WARNING, "Could not read races.yml; keeping the previous race settings.", e);
                return;
            }
            logger.log(Level.WARNING, "Could not read races.yml; using the bundled defaults.", e);
            yaml = bundled();
        }
        this.settings = parse(yaml);
        long active = settings.active().size();
        logger.info("Loaded " + settings.races().size() + " race(s), " + active + " enabled.");
    }

    private YamlConfiguration bundled() {
        try (InputStream stream = plugin.getResource(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Bundled races.yml is missing from the plugin jar.");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled races.yml", e);
        }
    }

    private RaceSettings parse(YamlConfiguration yaml) {
        Map<String, RaceDefinition> races = parseRaces(yaml);
        if (races.isEmpty()) {
            logger.warning("races.yml: no valid race is defined under 'races'; using the bundled defaults.");
            races = parseRaces(bundled());
        }
        RaceSettings.Dynamic dynamic = parseDynamic(yaml, races);
        return new RaceSettings(races, dynamic);
    }

    private Map<String, RaceDefinition> parseRaces(YamlConfiguration yaml) {
        Map<String, RaceDefinition> races = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("races");
        if (section == null) {
            return races;
        }
        for (String key : section.getKeys(false)) {
            String id = key.trim().toLowerCase(Locale.ROOT);
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                logger.warning("races.yml: 'races." + key + "' must be a section; skipped.");
                continue;
            }
            if (!ID_PATTERN.matcher(id).matches()) {
                logger.warning("races.yml: invalid race id '" + key + "' (use a-z, 0-9, _ and -, max 32); skipped.");
                continue;
            }
            if (races.containsKey(id)) {
                logger.warning("races.yml: duplicate race id '" + id + "'; the later entry is skipped.");
                continue;
            }

            String displayName = entry.getString("display-name", "");
            if (displayName.isBlank()) {
                displayName = capitalize(id);
            }
            TextColor color = parseColor(entry.getString("color"));
            if (color == null) {
                logger.warning("races.yml: races." + id + ".color is not a valid color (use #RRGGBB or a color name); using white.");
                color = NamedTextColor.WHITE;
            }

            double weight = entry.getDouble("weight", 1.0);
            boolean enabled = entry.getBoolean("enabled", true);
            if (!Double.isFinite(weight) || weight <= 0) {
                logger.warning("races.yml: races." + id + ".weight must be greater than 0; race disabled.");
                weight = 1.0;
                enabled = false;
            }
            races.put(id, new RaceDefinition(id, displayName.trim(), color,
                    entry.getString("description", "").trim(), weight, enabled));
        }
        return races;
    }

    private RaceSettings.Dynamic parseDynamic(YamlConfiguration yaml, Map<String, RaceDefinition> races) {
        String path = "dynamic-chance.";
        boolean enabled = yaml.getBoolean(path + "enabled", true);

        double smoothing = yaml.getDouble(path + "smoothing-k", 5.0);
        if (!Double.isFinite(smoothing) || smoothing <= 0) {
            logger.warning("races.yml: 'dynamic-chance.smoothing-k' must be greater than 0; using 5.");
            smoothing = 5.0;
        }
        double alpha = yaml.getDouble(path + "alpha", 1.0);
        if (!Double.isFinite(alpha) || alpha < 0) {
            logger.warning("races.yml: 'dynamic-chance.alpha' must be 0 or more; using 1.");
            alpha = 1.0;
        }
        double minMultiplier = yaml.getDouble(path + "min-multiplier", 0.5);
        double maxMultiplier = yaml.getDouble(path + "max-multiplier", 2.0);
        if (!Double.isFinite(minMultiplier) || minMultiplier <= 0
                || !Double.isFinite(maxMultiplier) || maxMultiplier < minMultiplier) {
            logger.warning("races.yml: multipliers must satisfy 0 < min-multiplier <= max-multiplier; using 0.5 and 2.");
            minMultiplier = 0.5;
            maxMultiplier = 2.0;
        }
        double minChance = yaml.getDouble(path + "min-chance-percent", 5.0);
        if (!Double.isFinite(minChance) || minChance < 0 || minChance > 100) {
            logger.warning("races.yml: 'dynamic-chance.min-chance-percent' must be 0-100; using 5.");
            minChance = 5.0;
        }
        long activeCount = races.values().stream().filter(RaceDefinition::enabled).count();
        if (activeCount > 0 && minChance * activeCount > 100.0) {
            double capped = 100.0 / activeCount;
            logger.warning("races.yml: 'min-chance-percent' x enabled races is above 100%; effective minimum is "
                    + String.format(Locale.ROOT, "%.2f", capped) + "% (equal chances).");
        }
        return new RaceSettings.Dynamic(enabled, smoothing, alpha, minMultiplier, maxMultiplier, minChance);
    }

    private static @Nullable TextColor parseColor(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return NamedTextColor.WHITE;
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            return TextColor.fromHexString(value);
        }
        return NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
    }

    private static String capitalize(String id) {
        return id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }
}
