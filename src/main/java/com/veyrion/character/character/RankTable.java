package com.veyrion.character.character;

import java.util.List;
import java.util.Optional;

/** Ordered Adventure Ranks. Lowest rank first; EXP thresholds strictly increase and start at 0. */
public record RankTable(List<Rank> ranks) {

    public record Rank(String name, long requiredExp) {}

    public RankTable {
        ranks = List.copyOf(ranks);
        if (ranks.isEmpty()) {
            throw new IllegalArgumentException("at least one rank is required");
        }
        if (ranks.get(0).requiredExp() != 0) {
            throw new IllegalArgumentException("the first rank must require 0 EXP");
        }
        for (int i = 1; i < ranks.size(); i++) {
            if (ranks.get(i).requiredExp() <= ranks.get(i - 1).requiredExp()) {
                throw new IllegalArgumentException("rank EXP values must strictly increase (at '"
                        + ranks.get(i).name() + "')");
            }
        }
    }

    public static RankTable defaults() {
        return new RankTable(List.of(
                new Rank("Novice", 0),
                new Rank("Apprentice", 1000),
                new Rank("Adventurer", 5000),
                new Rank("Veteran", 15000),
                new Rank("Hero", 40000)));
    }

    /** Highest rank whose threshold is at or below {@code exp}. */
    public Rank rankFor(long exp) {
        Rank result = ranks.get(0);
        for (Rank rank : ranks) {
            if (exp >= rank.requiredExp()) {
                result = rank;
            } else {
                break;
            }
        }
        return result;
    }

    /** @return position of the rank (case-insensitive), or -1 if unknown */
    public int indexOf(String name) {
        for (int i = 0; i < ranks.size(); i++) {
            if (ranks.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public Optional<Rank> next(String name) {
        int index = indexOf(name);
        return index >= 0 && index + 1 < ranks.size() ? Optional.of(ranks.get(index + 1)) : Optional.empty();
    }
}
