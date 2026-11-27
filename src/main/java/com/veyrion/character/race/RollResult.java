package com.veyrion.character.race;

import org.jetbrains.annotations.Nullable;

/**
 * Outcome of a gacha roll.
 *
 * @param race   the assigned race (ASSIGNED) or the race the character already has (ALREADY_ROLLED, may be null)
 * @param chance final chance of the assigned race as a fraction (only for ASSIGNED)
 */
public record RollResult(Status status, @Nullable String race, double chance) {

    public enum Status {
        ASSIGNED,
        ALREADY_ROLLED,
        NO_CHARACTER,
        NO_RACES,
        /** A roll for this player is already being processed. Set by the service, never by the database. */
        IN_PROGRESS
    }

    public static RollResult assigned(String race, double chance) {
        return new RollResult(Status.ASSIGNED, race, chance);
    }

    public static RollResult alreadyRolled(@Nullable String race) {
        return new RollResult(Status.ALREADY_ROLLED, race, 0);
    }

    public static RollResult of(Status status) {
        return new RollResult(status, null, 0);
    }
}
