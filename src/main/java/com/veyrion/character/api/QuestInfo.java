package com.veyrion.character.api;

import org.jetbrains.annotations.Nullable;

/**
 * A quest definition from quests/*.yml. {@code name} and {@code description} are MiniMessage text.
 *
 * @param requiredRank minimum Adventure Rank name, or null
 */
public record QuestInfo(String id, String name, String description, @Nullable String requiredRank, long expReward,
                        double vyrReward, int objectives, boolean repeatable) {}
