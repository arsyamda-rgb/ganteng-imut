package com.veyrion.character.api;

/** Outcome of {@link VeyrionCharacterAPI#acceptQuest}. {@link #ACCEPTED} means the quest is now active. */
public enum QuestResult {
    ACCEPTED, ALREADY_ACTIVE, ALREADY_COMPLETED, ON_COOLDOWN, PREREQUISITE_MISSING, RANK_TOO_LOW, NOT_LOADED,
    UNKNOWN_QUEST, ERROR
}
