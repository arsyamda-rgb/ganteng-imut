package com.veyrion.character.awakening;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Creates {@link PlayerDouble}s. Implemented by the ProtocolLib backend; absent when ProtocolLib is missing. */
public interface PlayerDoubleFactory {

    /**
     * @param source    the player to copy (skin, and gear when {@code equipment} is true)
     * @param at        where the double stands (yaw and pitch are its facing)
     * @param equipment copy armor and held items
     * @return a double nobody can see yet
     */
    PlayerDouble create(Player source, Location at, boolean equipment);
}
