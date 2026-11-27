package com.veyrion.character.listener;

import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.integration.CharacterCache;
import com.veyrion.character.integration.PopulationCache;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Makes sure every player who joins has a character row, and keeps the integration cache in step. */
public final class PlayerJoinListener implements Listener {

    private final VeyrionCharacterPlugin plugin;
    private final CharacterRepository repository;
    private final CharacterCache cache;
    private final PopulationCache population;

    public PlayerJoinListener(VeyrionCharacterPlugin plugin, CharacterRepository repository, CharacterCache cache,
                              PopulationCache population) {
        this.plugin = plugin;
        this.repository = repository;
        this.cache = cache;
        this.population = population;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        cache.track(uuid);
        var template = plugin.configManager().settings().defaultCharacter().create(uuid, name);
        // Completes on the database thread: only log here, never touch the Bukkit API.
        repository.findOrCreate(template).whenComplete((character, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load or create character for " + name + " (" + uuid + ")", error);
            } else {
                cache.put(character);
                population.refresh(); // a new character raises the total / unassigned counts
                plugin.debug("Character ready for " + name + ": rank=" + character.adventureRank()
                        + ", exp=" + character.adventureExp());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.untrack(event.getPlayer().getUniqueId());
    }
}
