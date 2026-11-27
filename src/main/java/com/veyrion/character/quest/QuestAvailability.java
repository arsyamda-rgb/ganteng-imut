package com.veyrion.character.quest;

/**
 * Whether a player can take a quest. As the result of {@code QuestService#accept}, {@link #AVAILABLE}
 * means the quest was accepted.
 */
public enum QuestAvailability {
    AVAILABLE("quest.accepted"),
    ACTIVE("quest.blocked.active"),
    COMPLETED("quest.blocked.completed"),
    ON_COOLDOWN("quest.blocked.cooldown"),
    LOCKED_PREREQUISITE("quest.blocked.prerequisite"),
    LOCKED_RANK("quest.blocked.rank"),
    NOT_LOADED("quest.state-loading"),
    ERROR("quest.error");

    private final String messageKey;

    QuestAvailability(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
