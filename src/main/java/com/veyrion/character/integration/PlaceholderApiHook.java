package com.veyrion.character.integration;

import com.veyrion.character.core.VeyrionCharacterPlugin;

/**
 * Registers and unregisters the expansion. Only instantiate this after checking that PlaceholderAPI is
 * enabled: this class (and the expansion) need PlaceholderAPI's classes to load.
 */
public final class PlaceholderApiHook implements AutoCloseable {

    private final VeyrionPlaceholderExpansion expansion;
    private boolean registered;

    public PlaceholderApiHook(VeyrionCharacterPlugin plugin, PlaceholderResolver resolver) {
        this.expansion = new VeyrionPlaceholderExpansion(plugin, resolver);
    }

    /** @return false if PlaceholderAPI refused (for example another plugin already owns the identifier) */
    public boolean register() {
        if (registered) {
            return true; // never register twice
        }
        registered = expansion.register();
        return registered;
    }

    @Override
    public void close() {
        if (registered) {
            registered = false;
            expansion.unregister();
        }
    }
}
