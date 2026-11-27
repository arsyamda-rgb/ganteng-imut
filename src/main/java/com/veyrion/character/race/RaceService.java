package com.veyrion.character.race;

import com.veyrion.character.character.CharacterData;
import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Race rules. Every future returned here completes on the Minecraft main thread, so callers may use the
 * Bukkit API directly in their callbacks. Database work itself runs on the database thread.
 */
public final class RaceService {

    /**
     * Current chances plus the population they were computed from.
     *
     * @param total      every registered character (the denominator of the expected population)
     * @param unassigned registered characters without a race
     * @param other      characters holding a legacy or disabled race (not a rollable one)
     */
    public record ChanceReport(List<RaceChanceCalculator.Chance> chances, long total, long unassigned, long other,
                               boolean dynamic) {}

    private final VeyrionCharacterPlugin plugin;
    private final RaceRegistry registry;
    private final RaceRepository repository;
    private final CharacterRepository characters;
    private final SecureRandom random = new SecureRandom();
    /** Players with a roll in flight. Main thread only. */
    private final Set<UUID> rolling = new HashSet<>();
    /** Told (on the main thread) after a character's race was stored, set or reset. */
    private final List<Consumer<UUID>> raceListeners = new CopyOnWriteArrayList<>();

    public RaceService(VeyrionCharacterPlugin plugin, RaceRegistry registry, RaceRepository repository,
                       CharacterRepository characters) {
        this.plugin = plugin;
        this.registry = registry;
        this.repository = repository;
        this.characters = characters;
    }

    /** Registers a callback for every successful race change of one character. Runs on the main thread. */
    public void addRaceChangeListener(Consumer<UUID> listener) {
        raceListeners.add(listener);
    }

    private void fireRaceChanged(UUID uuid) {
        for (Consumer<UUID> listener : raceListeners) {
            try {
                listener.accept(uuid);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "A race change listener failed", e);
            }
        }
    }

    public RaceRegistry registry() {
        return registry;
    }

    /** Call from the main thread. A second call while one is running returns IN_PROGRESS without touching the database. */
    public CompletableFuture<RollResult> roll(UUID uuid) {
        if (!rolling.add(uuid)) {
            return CompletableFuture.completedFuture(RollResult.of(RollResult.Status.IN_PROGRESS));
        }
        RaceSettings snapshot = registry.settings();
        return toMain(repository.roll(uuid, snapshot, random)).whenComplete((result, error) -> {
            rolling.remove(uuid);
            if (error == null && result != null && (result.status() == RollResult.Status.ASSIGNED
                    || result.status() == RollResult.Status.ALREADY_ROLLED)) {
                fireRaceChanged(uuid);
            }
        });
    }

    /** @return true if the character exists and was updated */
    public CompletableFuture<Boolean> setRace(UUID uuid, String raceId) {
        return toMain(repository.setRace(uuid, raceId)).whenComplete((updated, error) -> {
            if (error == null && Boolean.TRUE.equals(updated)) {
                fireRaceChanged(uuid);
            }
        });
    }

    /** @return true if the character exists and was reset */
    public CompletableFuture<Boolean> reset(UUID uuid) {
        return toMain(repository.reset(uuid)).whenComplete((updated, error) -> {
            if (error == null && Boolean.TRUE.equals(updated)) {
                fireRaceChanged(uuid);
            }
        });
    }

    public CompletableFuture<Optional<CharacterData>> character(UUID uuid) {
        return toMain(characters.find(uuid));
    }

    /** Live population statistics straight from the database (no cache, so never stale). */
    public CompletableFuture<PopulationReport> population() {
        List<PopulationReport.Configured> configured = new java.util.ArrayList<>();
        for (RaceDefinition race : registry.settings().races().values()) {
            configured.add(new PopulationReport.Configured(race.id(), race.enabled()));
        }
        return toMain(repository.snapshot().thenApply(snapshot -> PopulationReport.build(snapshot, configured)));
    }

    /** Current chances for every enabled race, from live populations. */
    public CompletableFuture<ChanceReport> chances() {
        RaceSettings snapshot = registry.settings();
        List<RaceChanceCalculator.Entry> entries = snapshot.entries();
        CompletableFuture<ChanceReport> computed = repository.snapshot().thenApply(population -> {
            if (entries.isEmpty()) {
                return new ChanceReport(List.of(), population.total(), population.unassigned(), 0,
                        snapshot.dynamic().enabled());
            }
            List<RaceChanceCalculator.Chance> chances = RaceChanceCalculator.compute(entries, population.byRace(),
                    population.total(), snapshot.params());
            long rollable = chances.stream().mapToLong(RaceChanceCalculator.Chance::population).sum();
            return new ChanceReport(chances, population.total(), population.unassigned(),
                    population.assigned() - rollable, snapshot.dynamic().enabled());
        });
        return toMain(computed);
    }

    private <T> CompletableFuture<T> toMain(CompletableFuture<T> source) {
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((value, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(value);
                }
            });
        });
        return result;
    }
}
