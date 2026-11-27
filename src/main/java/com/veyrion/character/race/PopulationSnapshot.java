package com.veyrion.character.race;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw character counts from ONE database statement, so the parts always add up.
 *
 * <p>Every row of {@code characters} falls into exactly one group: it holds a race id (lower-cased) or it has
 * none. Nothing is counted twice. Legacy and disabled race ids are kept as they are.
 *
 * @param byRace     race id (lower case) to number of characters holding it; only ids with at least 1
 * @param unassigned characters whose race is NULL or empty
 */
public record PopulationSnapshot(Map<String, Long> byRace, long unassigned) {

    public static final PopulationSnapshot EMPTY = new PopulationSnapshot(Map.of(), 0);

    public PopulationSnapshot {
        if (unassigned < 0) {
            throw new IllegalArgumentException("unassigned must not be negative");
        }
        Map<String, Long> copy = new HashMap<>();
        byRace.forEach((id, count) -> {
            if (count != null && count > 0) {
                copy.merge(id.toLowerCase(java.util.Locale.ROOT), count, Long::sum);
            }
        });
        byRace = Collections.unmodifiableMap(copy);
    }

    public long count(String id) {
        return byRace.getOrDefault(id.toLowerCase(java.util.Locale.ROOT), 0L);
    }

    /** Characters that hold any race, including legacy and disabled ones. */
    public long assigned() {
        long sum = 0;
        for (long count : byRace.values()) {
            sum += count;
        }
        return sum;
    }

    /** Every registered character. */
    public long total() {
        return assigned() + unassigned;
    }
}
