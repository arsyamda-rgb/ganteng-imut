package com.veyrion.character.economy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Last balance read from the economy provider for each ONLINE player. It is a read-only copy for threads that must
 * not call the provider (PlaceholderAPI may ask from any thread). The provider stays the source of truth.
 */
final class BalanceCache {

    record Sample(double value, long atNanos) {}

    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();

    void put(UUID uuid, double value) {
        samples.put(uuid, new Sample(value, System.nanoTime()));
    }

    @Nullable Sample get(UUID uuid) {
        return samples.get(uuid);
    }

    void remove(UUID uuid) {
        samples.remove(uuid);
    }

    void clear() {
        samples.clear();
    }
}
