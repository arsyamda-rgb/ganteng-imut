package com.veyrion.character.character;

/** Outcome of adding Adventure EXP to a character. */
public record ExpResult(long oldExp, long newExp, String oldRank, String newRank) {

    public boolean rankedUp() {
        return !oldRank.equals(newRank);
    }
}
