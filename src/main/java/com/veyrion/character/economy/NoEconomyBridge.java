package com.veyrion.character.economy;

import java.util.UUID;

/** Used when Vault is not installed (or could not be linked). Every operation fails cleanly. */
public final class NoEconomyBridge implements EconomyBridge {

    public static final NoEconomyBridge INSTANCE = new NoEconomyBridge();

    private NoEconomyBridge() {
    }

    @Override
    public Status status() {
        return Status.NO_VAULT;
    }

    @Override
    public String providerName() {
        return "none";
    }

    @Override
    public double balance(UUID player) {
        throw new IllegalStateException("No economy available");
    }

    @Override
    public boolean has(UUID player, double amount) {
        return false;
    }

    @Override
    public Result deposit(UUID player, double amount) {
        return Result.fail("No economy available");
    }

    @Override
    public Result withdraw(UUID player, double amount) {
        return Result.fail("No economy available");
    }
}
