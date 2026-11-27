package com.veyrion.character.race;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/** Validated, immutable view of {@code races.yml}. */
public record RaceSettings(Map<String, RaceDefinition> races, Dynamic dynamic) {

    /** Dynamic chance settings. {@code minChancePercent} is 0-100. */
    public record Dynamic(boolean enabled, double smoothing, double alpha, double minMultiplier,
                          double maxMultiplier, double minChancePercent) {}

    public RaceSettings {
        races = Collections.unmodifiableMap(new LinkedHashMap<>(races));
    }

    /** Enabled races, in file order. */
    public List<RaceDefinition> active() {
        List<RaceDefinition> result = new ArrayList<>();
        for (RaceDefinition race : races.values()) {
            if (race.enabled()) {
                result.add(race);
            }
        }
        return List.copyOf(result);
    }

    public @Nullable RaceDefinition find(String id) {
        return races.get(id.toLowerCase(Locale.ROOT));
    }

    /** Coloured display name; an id that is no longer configured (legacy) is shown as plain text. */
    public Component display(String id) {
        RaceDefinition race = find(id);
        return race != null ? race.displayComponent() : Component.text(id);
    }

    public List<RaceChanceCalculator.Entry> entries() {
        List<RaceChanceCalculator.Entry> result = new ArrayList<>();
        for (RaceDefinition race : active()) {
            result.add(new RaceChanceCalculator.Entry(race.id(), race.weight()));
        }
        return List.copyOf(result);
    }

    public RaceChanceCalculator.Params params() {
        return new RaceChanceCalculator.Params(dynamic.enabled(), dynamic.smoothing(), dynamic.alpha(),
                dynamic.minMultiplier(), dynamic.maxMultiplier(), dynamic.minChancePercent() / 100.0);
    }
}
