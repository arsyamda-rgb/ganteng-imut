package com.veyrion.character.api;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only snapshot of a built-in NPC. {@code displayName} and {@code dialogue} are MiniMessage text.
 *
 * @param questId the quest this NPC offers, or null
 * @param visible false = hidden from everyone except admins
 */
public record NpcInfo(String id, String displayName, String world, double x, double y, double z,
                      @Nullable String questId, boolean visible, List<String> dialogue) {

    public NpcInfo {
        dialogue = List.copyOf(dialogue);
    }
}
