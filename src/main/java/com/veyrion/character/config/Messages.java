package com.veyrion.character.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Language-file backed messages in MiniMessage format. The selected language file is read from
 * {@code plugins/VeyrionCharacter/lang/}; missing keys fall back to the bundled English file.
 */
public final class Messages {

    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,3}([_-][a-z]{2,4})?");
    private static final String FALLBACK_LANGUAGE = "en";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private volatile YamlConfiguration active = new YamlConfiguration();
    private volatile YamlConfiguration fallback = new YamlConfiguration();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String language) {
        File defaultFile = new File(plugin.getDataFolder(), "lang/" + FALLBACK_LANGUAGE + ".yml");
        if (!defaultFile.exists()) {
            plugin.saveResource("lang/" + FALLBACK_LANGUAGE + ".yml", false);
        }

        YamlConfiguration bundled = new YamlConfiguration();
        try (InputStream stream = plugin.getResource("lang/" + FALLBACK_LANGUAGE + ".yml")) {
            if (stream != null) {
                bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } else {
                plugin.getLogger().severe("Bundled lang/en.yml is missing from the plugin jar.");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read bundled lang/en.yml", e);
        }
        this.fallback = bundled;

        if (!LANGUAGE_CODE.matcher(language).matches()) {
            plugin.getLogger().warning("Invalid language code '" + language + "'; using English.");
            this.active = bundled;
            return;
        }

        File file = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("Language file lang/" + language + ".yml not found; using English.");
            this.active = bundled;
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            this.active = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read lang/" + language + ".yml; using English.", e);
            this.active = bundled;
        }
    }

    /** Parses the message for {@code key} without the prefix. */
    public Component get(String key, TagResolver... resolvers) {
        String raw = active.getString(key);
        if (raw == null) {
            raw = fallback.getString(key);
        }
        if (raw == null) {
            return Component.text("<missing message: " + key + ">");
        }
        return MINI_MESSAGE.deserialize(raw, resolvers);
    }

    /** Sends the message for {@code key}. No plugin prefix is added: command output is shown as plain text. */
    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(get(key, resolvers));
    }
}
