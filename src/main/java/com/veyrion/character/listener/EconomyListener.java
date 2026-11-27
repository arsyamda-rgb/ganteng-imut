package com.veyrion.character.listener;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.economy.EconomyService;
import com.veyrion.character.economy.VyrPayouts;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Retries undelivered VYR quest rewards when a player joins and drops cached economy data when they leave. */
public final class EconomyListener implements Listener {

    private final VeyrionCharacterPlugin plugin;
    private final EconomyService economy;
    private final VyrPayouts payouts;

    public EconomyListener(VeyrionCharacterPlugin plugin, EconomyService economy, VyrPayouts payouts) {
        this.plugin = plugin;
        this.economy = economy;
        this.payouts = payouts;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // A short delay lets the economy plugin finish creating the player's account first.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            economy.balance(uuid);
            payouts.flush(uuid);
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        economy.forget(event.getPlayer().getUniqueId());
    }
}
