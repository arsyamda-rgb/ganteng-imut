package com.veyrion.character.economy;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The only door to an economy. The one real implementation talks to the Vault Economy API; nothing in this plugin
 * stores or computes balances itself. This interface has no Vault types, so classes that use it never need Vault.
 * Implementations are called on the Minecraft main thread only.
 */
public interface EconomyBridge {

    enum Status { AVAILABLE, NO_VAULT, NO_PROVIDER }

    record Result(boolean success, @Nullable String error) {
        public static Result ok() {
            return new Result(true, null);
        }

        public static Result fail(@Nullable String error) {
            return new Result(false, error);
        }
    }

    /** Looked up every time: an economy plugin may register or unregister after this plugin started. */
    Status status();

    String providerName();

    double balance(UUID player);

    boolean has(UUID player, double amount);

    Result deposit(UUID player, double amount);

    Result withdraw(UUID player, double amount);
}
