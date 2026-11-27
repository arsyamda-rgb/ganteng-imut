package com.veyrion.character.quest;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/** One objective of a quest. Fields not relevant to {@code type} are null/zero. */
public record QuestObjective(
        String id,
        ObjectiveType type,
        String description,
        int amount,
        @Nullable EntityType entity,
        @Nullable Material material,
        @Nullable String npcId,
        @Nullable String world,
        double x,
        double y,
        double z,
        double radius,
        @Nullable String customKey) {
}
