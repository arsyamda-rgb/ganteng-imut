package com.veyrion.character.api;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of a character. The rank always matches the EXP under the current rank table.
 *
 * @param race race id, or {@code null} until the player has one
 */
public record CharacterInfo(UUID uuid, String playerName, @Nullable String race, long adventureExp,
                            String adventureRank, boolean hasCompletedGacha) {

    public boolean hasRace() {
        return race != null;
    }
}
