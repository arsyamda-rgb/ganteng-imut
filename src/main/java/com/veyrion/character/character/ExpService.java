package com.veyrion.character.character;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Single entry point for adding Adventure EXP. Rank is recalculated from the current rank table. */
public final class ExpService {

    private final CharacterRepository repository;
    private final Supplier<RankTable> ranks;

    public ExpService(CharacterRepository repository, Supplier<RankTable> ranks) {
        this.repository = repository;
        this.ranks = ranks;
    }

    /** Adds {@code amount} (must be positive). The future fails if the character does not exist. */
    public CompletableFuture<ExpResult> grant(UUID uuid, long amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("amount must be positive: " + amount));
        }
        return repository.addExp(uuid, amount, ranks.get());
    }

    public RankTable ranks() {
        return ranks.get();
    }
}
