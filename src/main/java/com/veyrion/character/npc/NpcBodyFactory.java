package com.veyrion.character.npc;

import org.bukkit.Location;

/** Creates bodies. Implemented by the ProtocolLib backend; absent when ProtocolLib is not installed. */
public interface NpcBodyFactory {

    /**
     * @param at where the body stands (yaw and pitch are its facing)
     * @return a body nobody can see yet
     */
    NpcBody create(NpcData data, Location at);
}
