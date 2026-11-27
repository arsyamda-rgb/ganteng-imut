package com.veyrion.character.economy;

import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/** Finds the player a command names without blocking (no network or disk profile lookups). */
public final class Targets {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,20}");

    private Targets() {
    }

    /** @return an online player, or a cached offline player who has played here; otherwise null */
    public static @Nullable OfflinePlayer resolve(String name) {
        if (!NAME.matcher(name).matches()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }
}
