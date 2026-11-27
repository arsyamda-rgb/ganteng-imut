package com.veyrion.character.race;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Population statistics derived from a {@link PopulationSnapshot}. Pure: no Bukkit, no database.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code total} = every registered character = races listed in races.yml + legacy + unassigned.</li>
 *   <li>A configured race (enabled or disabled) has its own row. A race id that is not in races.yml is
 *       "legacy" and is summed in {@code legacy}, also listed per id.</li>
 *   <li>Percentages are of {@code total} (rows sum to 100%); an empty database gives 0, never NaN.</li>
 *   <li>Most/least populated look at enabled configured races only; ties go to the first in file order;
 *       both are null while those races hold no characters.</li>
 * </ul>
 */
public record PopulationReport(long total, long assigned, long unassigned, long legacy, List<RaceRow> races,
                               List<LegacyRow> legacyRaces, @Nullable RaceRow mostPopulated,
                               @Nullable RaceRow leastPopulated) {

    /** A race from races.yml. */
    public record Configured(String id, boolean enabled) {}

    public record RaceRow(String id, boolean enabled, long count, double percentOfTotal, double percentOfAssigned) {}

    public record LegacyRow(String id, long count) {}

    public double legacyPercentOfTotal() {
        return percent(legacy, total);
    }

    public double unassignedPercentOfTotal() {
        return percent(unassigned, total);
    }

    public double legacyPercentOfAssigned() {
        return percent(legacy, assigned);
    }

    public static PopulationReport build(PopulationSnapshot snapshot, List<Configured> configured) {
        long total = snapshot.total();
        long assigned = snapshot.assigned();

        List<RaceRow> rows = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (Configured race : configured) {
            String id = race.id().toLowerCase(Locale.ROOT);
            if (!known.add(id)) {
                continue;
            }
            long count = snapshot.count(id);
            rows.add(new RaceRow(id, race.enabled(), count, percent(count, total), percent(count, assigned)));
        }

        List<LegacyRow> legacyRows = new ArrayList<>();
        long legacy = 0;
        for (var entry : snapshot.byRace().entrySet()) {
            if (!known.contains(entry.getKey())) {
                legacyRows.add(new LegacyRow(entry.getKey(), entry.getValue()));
                legacy += entry.getValue();
            }
        }
        legacyRows.sort(Comparator.comparingLong(LegacyRow::count).reversed().thenComparing(LegacyRow::id));

        RaceRow most = null;
        RaceRow least = null;
        long enabledPopulation = 0;
        for (RaceRow row : rows) {
            if (!row.enabled()) {
                continue;
            }
            enabledPopulation += row.count();
            if (most == null || row.count() > most.count()) {
                most = row;
            }
            if (least == null || row.count() < least.count()) {
                least = row;
            }
        }
        if (enabledPopulation == 0) {
            most = null;
            least = null;
        }
        return new PopulationReport(total, assigned, snapshot.unassigned(), legacy, List.copyOf(rows),
                List.copyOf(legacyRows), most, least);
    }

    /** {@code part / whole * 100}; 0 when {@code whole} is 0. */
    public static double percent(long part, long whole) {
        return whole <= 0 ? 0.0 : part * 100.0 / whole;
    }
}
