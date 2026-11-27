package com.veyrion.character.npc;

import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The visible humanoid of an NPC. It exists only on the clients of the players it was shown to (no server entity),
 * so it costs the server nothing per tick. Main thread only. The one implementation uses ProtocolLib; this
 * interface has no ProtocolLib types, so the rest of the plugin never links to it.
 */
public interface NpcBody {

    /** Shows the body to {@code player}. Idempotent. */
    void show(Player player);

    /** Removes the body from {@code player}'s client. Idempotent. */
    void hide(Player player);

    /** Forgets a player whose client is gone (quit, world change, respawn) without sending anything. */
    void forget(Player player);

    boolean isShownTo(Player player);

    /** Players the body is currently shown to (a snapshot). */
    Collection<Player> viewers();

    /** Moves and turns the body for every viewer. */
    void move(Location to);

    /** Removes the body from every viewer and releases everything it holds. Idempotent. */
    void remove();
}
