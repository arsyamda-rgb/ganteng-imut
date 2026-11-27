package com.veyrion.character.awakening;

/**
 * Everything needed to put a player back after the cinematic camera: game mode, flight and where they stood. Plain
 * values (no Bukkit types) so it can be written to awakening-pending.yml and survive a crash.
 */
public record CameraSnapshot(String gameMode, String world, double x, double y, double z, float yaw, float pitch,
                             boolean allowFlight, boolean flying) {

    /** A game mode name is only trusted if it is one of the known four. */
    public static boolean knownMode(String name) {
        return switch (name) {
            case "SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR" -> true;
            default -> false;
        };
    }

    public boolean valid() {
        return knownMode(gameMode) && world != null && !world.isBlank()
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Float.isFinite(yaw) && Float.isFinite(pitch);
    }
}
