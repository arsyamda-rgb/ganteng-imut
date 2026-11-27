package com.veyrion.character.race;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * One race from {@code races.yml}.
 *
 * @param id          lower-case id stored in the database
 * @param displayName text shown to players (plain text, never parsed as MiniMessage)
 * @param weight      base weight, always finite and > 0
 * @param enabled     only enabled races can be rolled; disabled ones can still be set by an admin
 */
public record RaceDefinition(String id, String displayName, TextColor color, String description,
                             double weight, boolean enabled) {

    public Component displayComponent() {
        return Component.text(displayName, color);
    }
}
