package com.veyrion.character.integration;

import com.veyrion.character.character.CharacterData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only in-memory copy of character rows, used by integrations (PlaceholderAPI) so they never query the
 * database from the thread that asks. Contains no Bukkit code and never blocks.
 *
 * <ul>
 *   <li>Online players are tracked ({@link #track}/{@link #untrack}) and loaded eagerly; their entry is only
 *       replaced by newer loads, so lookups always answer instantly from memory.</li>
 *   <li>Offline players are loaded on first lookup in the background (the first answer is "unknown") into a
 *       small bounded LRU and re-read after {@link #OFFLINE_TTL_MS}.</li>
 *   <li>The database is the source of truth. After any write the writer calls {@link #refresh}, which re-reads
 *       the row. Loads complete in submission order (single database thread), so the newest read wins.</li>
 * </ul>
 */
public final class CharacterCache {

    public static final int OFFLINE_MAX = 256;
    public static final long OFFLINE_TTL_MS = 30_000;

    /** @param data the character, or null when the database has no character for this UUID */
    public record Entry(@Nullable CharacterData data, long loadedAtMs) {}

    private final Function<UUID, CompletableFuture<Optional<CharacterData>>> loader;
    private final LongSupplier clock;
    private final Consumer<Throwable> onError;

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Entry> onlineEntries = new ConcurrentHashMap<>();
    private final Set<UUID> offlineLoading = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Entry> offline = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
            return size() > OFFLINE_MAX;
        }
    };

    public CharacterCache(Function<UUID, CompletableFuture<Optional<CharacterData>>> loader, LongSupplier clock,
                          Consumer<Throwable> onError) {
        this.loader = loader;
        this.clock = clock;
        this.onError = onError;
    }

    /** Marks a player as online. Call before {@link #refresh} / {@link #put} for that player. */
    public void track(UUID uuid) {
        online.add(uuid);
    }

    public void untrack(UUID uuid) {
        online.remove(uuid);
        onlineEntries.remove(uuid);
    }

    /** Stores a character that was just read or created. Safe from any thread. */
    public void put(CharacterData data) {
        store(data.uuid(), new Entry(data, clock.getAsLong()));
    }

    /** Re-reads one character from the database in the background. Safe from any thread; never blocks. */
    public void refresh(UUID uuid) {
        load(uuid, false);
    }

    /** @return the cached entry, or null while nothing is known yet (a background load may have been started) */
    public @Nullable Entry get(UUID uuid) {
        if (online.contains(uuid)) {
            return onlineEntries.get(uuid);
        }
        Entry entry;
        synchronized (offline) {
            entry = offline.get(uuid);
        }
        if (entry == null || clock.getAsLong() - entry.loadedAtMs() > OFFLINE_TTL_MS) {
            load(uuid, true);
        }
        return entry;
    }

    public void clear() {
        online.clear();
        onlineEntries.clear();
        synchronized (offline) {
            offline.clear();
        }
    }

    private void load(UUID uuid, boolean dedupe) {
        if (dedupe && !offlineLoading.add(uuid)) {
            return;
        }
        CompletableFuture<Optional<CharacterData>> future;
        try {
            future = loader.apply(uuid);
        } catch (RuntimeException e) {
            offlineLoading.remove(uuid);
            onError.accept(e);
            return;
        }
        future.whenComplete((result, error) -> {
            if (dedupe) {
                offlineLoading.remove(uuid);
            }
            if (error != null) {
                onError.accept(error); // keep whatever was cached; never replace data with a failure
                return;
            }
            store(uuid, new Entry(result.orElse(null), clock.getAsLong()));
        });
    }

    private void store(UUID uuid, Entry entry) {
        if (online.contains(uuid)) {
            onlineEntries.put(uuid, entry);
        } else {
            synchronized (offline) {
                offline.put(uuid, entry);
            }
        }
    }
}
