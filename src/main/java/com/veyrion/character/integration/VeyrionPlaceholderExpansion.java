package com.veyrion.character.integration;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The %veyrioncharacter_...% expansion. References PlaceholderAPI classes, so it must only be loaded when
 * PlaceholderAPI is present (see {@link PlaceholderApiHook}). All answers come from caches; nothing here blocks.
 */
final class VeyrionPlaceholderExpansion extends PlaceholderExpansion {

    private final VeyrionCharacterPlugin plugin;
    private final PlaceholderResolver resolver;

    VeyrionPlaceholderExpansion(VeyrionCharacterPlugin plugin, PlaceholderResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "veyrioncharacter";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Keep the expansion when PlaceholderAPI reloads; the plugin unregisters it itself on disable. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        try {
            return resolver.resolve(player == null ? null : player.getUniqueId(),
                    player == null ? null : player.getName(), params);
        } catch (RuntimeException e) {
            plugin.debug("Placeholder '" + params + "' failed: " + e);
            return "";
        }
    }
}
