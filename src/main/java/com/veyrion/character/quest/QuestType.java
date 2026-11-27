package com.veyrion.character.quest;

public enum QuestType {
    MAIN, SIDE, REPEATABLE, ONE_TIME;

    /** Only repeatable quests can be taken again (after their cooldown). All others once per character. */
    public boolean isRepeatable() {
        return this == REPEATABLE;
    }
}
