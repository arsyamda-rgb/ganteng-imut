package com.veyrion.character.quest;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * An immutable quest definition loaded from {@code quests/*.yml}.
 *
 * @param cooldownSeconds minimum time between completions (repeatable quests)
 * @param requiredRank minimum Adventure Rank name, or null
 * @param vyrReward Veyra (VYR) paid through Vault
 * @param area optional place name shown on the quest scoreboard (for example "Ancient Ruins"); null = the quest name
 */
public record Quest(
        String id,
        String name,
        String description,
        QuestType type,
        long cooldownSeconds,
        @Nullable String requiredRank,
        List<String> prerequisites,
        long expReward,
        double vyrReward,
        List<QuestObjective> objectives,
        @Nullable String area) {

    /** Source-compatible constructor of earlier stages (no area). */
    public Quest(String id, String name, String description, QuestType type, long cooldownSeconds,
                 @Nullable String requiredRank, List<String> prerequisites, long expReward, double vyrReward,
                 List<QuestObjective> objectives) {
        this(id, name, description, type, cooldownSeconds, requiredRank, prerequisites, expReward, vyrReward,
                objectives, null);
    }

    public Quest {
        prerequisites = List.copyOf(prerequisites);
        objectives = List.copyOf(objectives);
    }
}
