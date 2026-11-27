package com.veyrion.character.npc;

import com.veyrion.character.awakening.PlayerDouble;
import com.veyrion.character.awakening.PlayerDoubleFactory;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Team;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Links to ProtocolLib (with {@link ProtocolPlayerDouble}). Created only if ProtocolLib is enabled. */
public final class ProtocolPlayerDoubleFactory implements PlayerDoubleFactory {

    private final Logger logger;

    private static final Pattern DOUBLE_TEAM = Pattern.compile("vd[0-9a-f]{1,6}");

    public ProtocolPlayerDoubleFactory(Logger logger) {
        this.logger = logger;
        sweepOrphanTeams();
    }

    /**
     * A double hides its name tag with a team on the main scoreboard, and the main scoreboard is saved with the
     * world. After a crash such a team stays. Nothing is shown yet when this runs, so every team of that name is
     * a leftover.
     */
    private void sweepOrphanTeams() {
        try {
            int removed = 0;
            for (Team team : java.util.List.copyOf(Bukkit.getScoreboardManager().getMainScoreboard().getTeams())) {
                if (DOUBLE_TEAM.matcher(team.getName()).matches()) {
                    team.unregister();
                    removed++;
                }
            }
            if (removed > 0) {
                logger.info("Removed " + removed + " leftover player-double team(s) from the main scoreboard.");
            }
        } catch (RuntimeException e) {
            logger.warning("Could not sweep leftover player-double teams: " + e);
        }
    }

    @Override
    public PlayerDouble create(Player source, Location at, boolean equipment) {
        return new ProtocolPlayerDouble(logger, source, at, equipment);
    }
}
