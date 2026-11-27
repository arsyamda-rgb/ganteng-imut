package com.veyrion.character.integration;

import com.veyrion.character.race.PopulationReport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the latest {@link PopulationReport} produced by the existing Population Tracker
 * ({@code RaceService#population()}). Reading is a volatile field access; the tracker is only asked again when
 * {@link #refresh()} is called. Refresh requests that arrive while one is running are merged into one re-run.
 */
public final class PopulationCache {

    private final Supplier<CompletableFuture<PopulationReport>> source;
    private final Consumer<Throwable> onError;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile @Nullable PopulationReport report;

    public PopulationCache(Supplier<CompletableFuture<PopulationReport>> source, Consumer<Throwable> onError) {
        this.source = source;
        this.onError = onError;
    }

    /** @return the last report, or null before the first refresh finished */
    public @Nullable PopulationReport report() {
        return report;
    }

    /** Asks the tracker for fresh numbers in the background. Safe from any thread; never blocks. */
    public void refresh() {
        if (!running.compareAndSet(false, true)) {
            dirty.set(true);
            return;
        }
        CompletableFuture<PopulationReport> future;
        try {
            future = source.get();
        } catch (RuntimeException e) {
            running.set(false);
            onError.accept(e);
            return;
        }
        future.whenComplete((result, error) -> {
            if (error != null) {
                onError.accept(error);
            } else if (result != null) {
                report = result;
            }
            running.set(false);
            if (dirty.getAndSet(false)) {
                refresh();
            }
        });
    }
}
