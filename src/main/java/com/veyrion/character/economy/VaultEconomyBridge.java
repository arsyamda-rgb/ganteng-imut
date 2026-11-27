package com.veyrion.character.economy;

import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.Nullable;

/**
 * Vault Economy API access. References Vault classes, so only instantiate it after checking that Vault is
 * enabled (see the plugin's startup code). The provider is taken from the Bukkit ServicesManager on every call;
 * Vault being installed does not mean an economy plugin is registered.
 */
public final class VaultEconomyBridge implements EconomyBridge {

    private final ServicesManager services;

    public VaultEconomyBridge(ServicesManager services) {
        this.services = services;
    }

    private @Nullable Economy provider() {
        RegisteredServiceProvider<Economy> registration = services.getRegistration(Economy.class);
        if (registration == null) {
            return null;
        }
        Economy economy = registration.getProvider();
        return economy.isEnabled() ? economy : null;
    }

    private Economy require() {
        Economy economy = provider();
        if (economy == null) {
            throw new IllegalStateException("No Vault economy provider is registered");
        }
        return economy;
    }

    @Override
    public Status status() {
        return provider() == null ? Status.NO_PROVIDER : Status.AVAILABLE;
    }

    @Override
    public String providerName() {
        Economy economy = provider();
        return economy == null ? "none" : economy.getName();
    }

    @Override
    public double balance(UUID player) {
        return require().getBalance(Bukkit.getOfflinePlayer(player));
    }

    @Override
    public boolean has(UUID player, double amount) {
        return require().has(Bukkit.getOfflinePlayer(player), amount);
    }

    @Override
    public Result deposit(UUID player, double amount) {
        return toResult(require().depositPlayer(Bukkit.getOfflinePlayer(player), amount));
    }

    @Override
    public Result withdraw(UUID player, double amount) {
        return toResult(require().withdrawPlayer(Bukkit.getOfflinePlayer(player), amount));
    }

    private static Result toResult(@Nullable EconomyResponse response) {
        if (response == null) {
            return Result.fail("provider returned no response");
        }
        return response.transactionSuccess() ? Result.ok() : Result.fail(response.errorMessage);
    }
}
