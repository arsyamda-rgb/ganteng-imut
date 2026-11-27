package com.veyrion.character.character;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of a player's character as stored in the database.
 *
 * @param race the character's race, or {@code null} until one has been assigned
 */
public record CharacterData(
        UUID uuid,
        String playerName,
        @Nullable String race,
        long adventureExp,
        String adventureRank,
        Instant createdAt,
        boolean hasCompletedGacha) {

    public CharacterData {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(adventureRank, "adventureRank");
        Objects.requireNonNull(createdAt, "createdAt");
        if (adventureExp < 0) {
            throw new IllegalArgumentException("adventureExp must not be negative: " + adventureExp);
        }
    }

    public boolean hasRace() {
        return race != null;
    }

    public CharacterData withRank(String newRank) {
        return new CharacterData(uuid, playerName, race, adventureExp, newRank, createdAt, hasCompletedGacha);
    }

    public CharacterData withPlayerName(String newName) {
        return new CharacterData(uuid, newName, race, adventureExp, adventureRank, createdAt, hasCompletedGacha);
    }
}
