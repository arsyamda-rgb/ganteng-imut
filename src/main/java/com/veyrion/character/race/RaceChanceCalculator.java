package com.veyrion.character.race;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Pure math for Dynamic Race Chance. No Bukkit, no database: everything is passed in, so it is easy to test.
 *
 * <pre>
 * adjusted_i = weight_i * clamp(((expected_i + k) / (population_i + k)) ^ alpha, minMultiplier, maxMultiplier)
 * expected_i = totalPopulation * weight_i / sum(weight)
 * </pre>
 *
 * {@code totalPopulation} is ALL registered characters (online or not, with any race, a legacy race or none).
 * {@code population_i} is only the characters holding race i. See {@link PopulationSnapshot}.
 *
 * The adjusted weights are normalised to sum to 1, then every race is lifted to at least the minimum chance
 * (water-filling: the extra is taken proportionally from the races above the floor).
 */
public final class RaceChanceCalculator {

    private static final double EPSILON = 1e-12;

    private RaceChanceCalculator() {}

    /** A race that can be rolled. {@code weight} must be finite and greater than 0. */
    public record Entry(String id, double weight) {
        public Entry {
            if (!Double.isFinite(weight) || weight <= 0) {
                throw new IllegalArgumentException("weight must be finite and > 0: " + id);
            }
        }
    }

    /**
     * @param dynamic       when false every multiplier is 1 and only the base weights (and floor) count
     * @param smoothing     k, must be > 0 (this is what prevents division by zero)
     * @param alpha         strength of the adjustment, must be >= 0
     * @param minChance     minimum final chance per race as a fraction (0.05 = 5%); capped to 1 / raceCount
     */
    public record Params(boolean dynamic, double smoothing, double alpha, double minMultiplier,
                         double maxMultiplier, double minChance) {
        public Params {
            if (!Double.isFinite(smoothing) || smoothing <= 0) {
                throw new IllegalArgumentException("smoothing must be finite and > 0");
            }
            if (!Double.isFinite(alpha) || alpha < 0) {
                throw new IllegalArgumentException("alpha must be finite and >= 0");
            }
            if (!Double.isFinite(minMultiplier) || minMultiplier <= 0
                    || !Double.isFinite(maxMultiplier) || maxMultiplier < minMultiplier) {
                throw new IllegalArgumentException("multipliers must satisfy 0 < min <= max");
            }
            if (!Double.isFinite(minChance) || minChance < 0 || minChance > 1) {
                throw new IllegalArgumentException("minChance must be within 0..1");
            }
        }
    }

    /** Full breakdown for one race. {@code chance} is the final probability in 0..1. */
    public record Chance(String id, double weight, long population, double expected, double multiplier,
                         double adjustedWeight, double chance) {}

    /**
     * Legacy overload: the total is only the sum of the enabled races' populations. Production code uses
     * {@link #compute(List, Map, long, Params)}; this one stays for the standalone dev check.
     */
    public static List<Chance> compute(List<Entry> entries, Map<String, Long> populations, Params params) {
        return compute(entries, populations, 0L, params);
    }

    /**
     * @param entries         enabled races only (at least one)
     * @param populations     race id (lower case) to number of characters holding it; missing means 0
     * @param totalPopulation every registered character; raised to the enabled races' sum if it is lower
     * @return one {@link Chance} per entry, same order; the chances sum to 1
     */
    public static List<Chance> compute(List<Entry> entries, Map<String, Long> populations, long totalPopulation,
                                       Params params) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("at least one race is required");
        }
        int n = entries.size();

        double weightSum = 0;
        long enabledTotal = 0;
        long[] population = new long[n];
        for (int i = 0; i < n; i++) {
            Entry entry = entries.get(i);
            weightSum += entry.weight();
            population[i] = Math.max(0L, populations.getOrDefault(entry.id(), 0L));
            enabledTotal += population[i];
        }
        long total = Math.max(Math.max(0L, totalPopulation), enabledTotal);

        double[] expected = new double[n];
        double[] multiplier = new double[n];
        double[] adjusted = new double[n];
        double adjustedSum = 0;
        for (int i = 0; i < n; i++) {
            Entry entry = entries.get(i);
            expected[i] = total * (entry.weight() / weightSum);
            double m = 1.0;
            if (params.dynamic()) {
                // Both (expected + k) and (population + k) are > 0 because k > 0: no division by zero.
                m = Math.pow((expected[i] + params.smoothing()) / (population[i] + params.smoothing()), params.alpha());
                if (Double.isNaN(m)) {
                    m = 1.0;
                }
                m = Math.min(params.maxMultiplier(), Math.max(params.minMultiplier(), m));
            }
            multiplier[i] = m;
            adjusted[i] = entry.weight() * m;
            adjustedSum += adjusted[i];
        }

        double[] probability = new double[n];
        for (int i = 0; i < n; i++) {
            probability[i] = adjusted[i] / adjustedSum;
        }
        probability = applyFloor(probability, Math.min(params.minChance(), 1.0 / n));

        List<Chance> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double chance = Math.min(1.0, Math.max(0.0, probability[i]));
            result.add(new Chance(entries.get(i).id(), entries.get(i).weight(), population[i], expected[i],
                    multiplier[i], adjusted[i], chance));
        }
        return List.copyOf(result);
    }

    /** Picks one race. {@code roll} must be in [0, 1). Falls back to the last entry on rounding error. */
    public static Chance pick(List<Chance> chances, double roll) {
        double cumulative = 0;
        for (Chance chance : chances) {
            cumulative += chance.chance();
            if (roll < cumulative) {
                return chance;
            }
        }
        return chances.get(chances.size() - 1);
    }

    public static Chance pick(List<Chance> chances, RandomGenerator random) {
        return pick(chances, random.nextDouble());
    }

    /** Lifts every probability to at least {@code floor}, keeping the total at 1. */
    static double[] applyFloor(double[] probability, double floor) {
        int n = probability.length;
        if (floor <= 0 || n == 0) {
            return probability;
        }
        if (floor * n >= 1.0 - EPSILON) {
            double[] uniform = new double[n];
            java.util.Arrays.fill(uniform, 1.0 / n);
            return uniform;
        }

        boolean[] fixed = new boolean[n];
        int fixedCount = 0;
        double scale = 1.0;
        for (int round = 0; round <= n; round++) {
            double freeSum = 0;
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    freeSum += probability[i];
                }
            }
            if (freeSum <= 0) {
                break;
            }
            scale = (1.0 - fixedCount * floor) / freeSum;
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (!fixed[i] && probability[i] * scale < floor - EPSILON) {
                    fixed[i] = true;
                    fixedCount++;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = fixed[i] ? floor : probability[i] * scale;
        }
        return result;
    }
}
