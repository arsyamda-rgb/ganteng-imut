package com.veyrion.character.npc;

import java.util.logging.Logger;
import org.bukkit.Location;

/** The only class (with {@link ProtocolNpcBody}) that links to ProtocolLib. Created only if ProtocolLib is enabled. */
public final class ProtocolNpcBodyFactory implements NpcBodyFactory {

    private final Logger logger;

    public ProtocolNpcBodyFactory(Logger logger) {
        this.logger = logger;
    }

    @Override
    public NpcBody create(NpcData data, Location at) {
        return new ProtocolNpcBody(logger, data, at);
    }
}
