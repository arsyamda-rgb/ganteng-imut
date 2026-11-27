package com.veyrion.character.quest;

import com.veyrion.character.util.Durations;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/** Loads quest definitions from {@code plugins/VeyrionCharacter/quests/*.yml}. A bad file is skipped, not fatal. */
public final class QuestRegistry {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final List<String> EXAMPLES = List.of("quests/the_lost_relic.yml", "quests/zombie_cull.yml");

    private final JavaPlugin plugin;
    private volatile Map<String, Quest> quests = Map.of();

    public QuestRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads all quest files. Example quests are copied only when the folder does not exist yet. */
    public int reload() {
        File folder = new File(plugin.getDataFolder(), "quests");
        if (!folder.exists()) {
            EXAMPLES.forEach(resource -> plugin.saveResource(resource, false));
        }

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            plugin.getLogger().warning("Cannot read quest folder " + folder + "; no quests loaded.");
            this.quests = Map.of();
            return 0;
        }
        Arrays.sort(files);

        Map<String, Quest> loaded = new LinkedHashMap<>();
        for (File file : files) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file);
                Quest quest = parse(file.getName(), yaml);
                if (loaded.putIfAbsent(quest.id(), quest) != null) {
                    throw new IllegalArgumentException("duplicate quest id '" + quest.id() + "'");
                }
            } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping quest file " + file.getName() + ": " + e.getMessage());
            }
        }
        this.quests = Collections.unmodifiableMap(loaded);
        plugin.getLogger().info("Loaded " + loaded.size() + " quest(s).");
        return loaded.size();
    }

    public @Nullable Quest get(String id) {
        return quests.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Quest> all() {
        return quests.values();
    }

    private static Quest parse(String fileName, YamlConfiguration yaml) {
        String defaultId = fileName.substring(0, fileName.length() - ".yml".length());
        String id = yaml.getString("id", defaultId).trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid id '" + id + "' (use a-z, 0-9, _ and -)");
        }

        QuestType type = parseEnum(QuestType.class, yaml.getString("type", "SIDE"), "type");

        OptionalLong cooldown = Durations.parseSeconds(yaml.getString("cooldown", "0"));
        if (cooldown.isEmpty()) {
            throw new IllegalArgumentException("invalid cooldown (examples: 3600, 30m, 1d12h)");
        }

        String requiredRank = yaml.getString("requirements.rank");
        if (requiredRank != null && requiredRank.isBlank()) {
            requiredRank = null;
        }
        List<String> prerequisites = new ArrayList<>();
        for (String prerequisite : yaml.getStringList("requirements.quests")) {
            prerequisites.add(prerequisite.trim().toLowerCase(Locale.ROOT));
        }

        long exp = yaml.getLong("rewards.exp", 0);
        double vyr = yaml.getDouble("rewards.vyr", 0);
        if (exp < 0 || vyr < 0) {
            throw new IllegalArgumentException("rewards must not be negative");
        }
        if (!Double.isFinite(vyr) || vyr > com.veyrion.character.economy.Money.MAX) {
            throw new IllegalArgumentException("rewards.vyr must be a normal number up to "
                    + (long) com.veyrion.character.economy.Money.MAX);
        }
        vyr = com.veyrion.character.economy.Money.round(vyr); // Veyra has at most two decimals

        ConfigurationSection section = yaml.getConfigurationSection("objectives");
        if (section == null || section.getKeys(false).isEmpty()) {
            throw new IllegalArgumentException("a quest needs at least one entry under 'objectives'");
        }
        List<QuestObjective> objectives = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection objectiveSection = section.getConfigurationSection(key);
            if (objectiveSection == null) {
                throw new IllegalArgumentException("objective '" + key + "' must be a section");
            }
            objectives.add(parseObjective(key, objectiveSection));
        }

        String area = yaml.getString("area");
        if (area != null && area.isBlank()) {
            area = null;
        }
        return new Quest(id, yaml.getString("name", id), yaml.getString("description", ""), type,
                cooldown.getAsLong(), requiredRank, prerequisites, exp, vyr, objectives, area);
    }

    private static QuestObjective parseObjective(String id, ConfigurationSection section) {
        String where = "objectives." + id;
        ObjectiveType type = parseEnum(ObjectiveType.class, required(section, "type", where), where + ".type");
        int amount = Math.max(1, section.getInt("amount", 1));
        String description = section.getString("description", id);

        EntityType entity = null;
        Material material = null;
        String npc = null;
        String world = null;
        double x = 0;
        double y = 0;
        double z = 0;
        double radius = 0;
        String customKey = null;

        switch (type) {
            case KILL_MOB -> entity = parseEnum(EntityType.class, required(section, "entity", where), where + ".entity");
            case COLLECT_ITEM -> material = parseMaterial(section, where);
            case DELIVER_ITEM -> {
                material = parseMaterial(section, where);
                npc = required(section, "npc", where).toLowerCase(Locale.ROOT);
            }
            case EXPLORE_LOCATION -> {
                world = required(section, "world", where);
                if (!section.isSet("x") || !section.isSet("y") || !section.isSet("z")) {
                    throw new IllegalArgumentException(where + " needs x, y and z");
                }
                x = section.getDouble("x");
                y = section.getDouble("y");
                z = section.getDouble("z");
                radius = section.getDouble("radius", 5);
                if (radius <= 0) {
                    throw new IllegalArgumentException(where + ".radius must be positive");
                }
                amount = 1;
            }
            case TALK_TO_NPC -> {
                npc = required(section, "npc", where).toLowerCase(Locale.ROOT);
                amount = 1;
            }
            case CUSTOM -> customKey = section.getString("key", id);
        }
        return new QuestObjective(id, type, description, amount, entity, material, npc, world, x, y, z, radius, customKey);
    }

    private static Material parseMaterial(ConfigurationSection section, String where) {
        String name = required(section, "material", where);
        Material material = Material.matchMaterial(name);
        if (material == null) {
            throw new IllegalArgumentException(where + ".material: unknown material '" + name + "'");
        }
        return material;
    }

    private static String required(ConfigurationSection section, String key, String where) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(where + " is missing '" + key + "'");
        }
        return value.trim();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + field + " '" + value + "'");
        }
    }
}
