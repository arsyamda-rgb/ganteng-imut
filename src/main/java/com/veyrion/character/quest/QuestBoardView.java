package com.veyrion.character.quest;

import com.veyrion.character.quest.PlayerQuests.ActiveQuest;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * What the quest scoreboard shows for one player: the tracked quest, its place name, the objective being worked on
 * and that objective's progress. Text is raw (MiniMessage allowed, like quest files). Immutable.
 *
 * <p>The tracked quest is the most recently accepted active quest; its objective is the first one that is not
 * finished yet (the last one if all are). Main thread only (reads {@link PlayerQuests}).
 */
public record QuestBoardView(String questId, String area, String objective, int progress, int amount) {

    /** @return the view for the player's tracked quest, or null if there is none */
    public static @Nullable QuestBoardView of(@Nullable PlayerQuests state, QuestRegistry registry) {
        return of(state, registry::get);
    }

    /** Same, with any quest lookup (a check can pass a map). */
    public static @Nullable QuestBoardView of(@Nullable PlayerQuests state, Function<String, Quest> quests) {
        if (state == null) {
            return null;
        }
        ActiveQuest best = null;
        Quest bestQuest = null;
        for (ActiveQuest active : state.active()) {
            Quest quest = quests.apply(active.questId());
            if (quest == null || quest.objectives().isEmpty()) {
                continue;
            }
            // '>=' so that among equal timestamps the later entry (insertion order) wins.
            if (best == null || active.acceptedAt() >= best.acceptedAt()) {
                best = active;
                bestQuest = quest;
            }
        }
        if (best == null) {
            return null;
        }
        QuestObjective shown = bestQuest.objectives().get(bestQuest.objectives().size() - 1);
        for (QuestObjective objective : bestQuest.objectives()) {
            if (best.progress(objective.id()) < objective.amount()) {
                shown = objective;
                break;
            }
        }
        String area = bestQuest.area() != null ? bestQuest.area() : bestQuest.name();
        int value = Math.max(0, Math.min(shown.amount(), best.progress(shown.id())));
        return new QuestBoardView(bestQuest.id(), area, shown.description(), value, shown.amount());
    }
}
