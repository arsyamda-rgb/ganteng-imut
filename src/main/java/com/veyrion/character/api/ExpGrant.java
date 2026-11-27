package com.veyrion.character.api;

/** Result of {@link VeyrionCharacterAPI#grantExp}. */
public record ExpGrant(long gained, long oldExp, long newExp, String oldRank, String newRank) {

    public boolean rankedUp() {
        return !oldRank.equals(newRank);
    }
}
