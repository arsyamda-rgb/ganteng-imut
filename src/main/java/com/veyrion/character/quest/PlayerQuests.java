package com.veyrion.character.quest;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** In-memory quest state of one online player. Only touched on the main server thread. */
public final class PlayerQuests {

    /** Last completion time (epoch millis) and total completion count of a quest. */
    public record Completion(long lastCompletedAt, int count) {}

    public static final class ActiveQuest {
        private final String questId;
        private final long acceptedAt;
        private final Map<String, Integer> progress;
        private boolean completing;

        ActiveQuest(String questId, long acceptedAt, Map<String, Integer> initialProgress) {
            this.questId = questId;
            this.acceptedAt = acceptedAt;
            this.progress = new HashMap<>(initialProgress);
        }

        public String questId() {
            return questId;
        }

        public long acceptedAt() {
            return acceptedAt;
        }

        public int progress(String objectiveId) {
            return progress.getOrDefault(objectiveId, 0);
        }

        void setProgress(String objectiveId, int value) {
            progress.put(objectiveId, value);
        }

        boolean completing() {
            return completing;
        }

        void completing(boolean value) {
            this.completing = value;
        }
    }

    private final Map<String, ActiveQuest> active = new LinkedHashMap<>();
    private final Map<String, Completion> completed = new HashMap<>();
    private final Set<String> pending = new HashSet<>();

    public Collection<ActiveQuest> active() {
        return Collections.unmodifiableCollection(active.values());
    }

    public boolean isActive(String questId) {
        return active.containsKey(questId);
    }

    public @Nullable ActiveQuest activeQuest(String questId) {
        return active.get(questId);
    }

    public @Nullable Completion completion(String questId) {
        return completed.get(questId);
    }

    void addActive(String questId, long acceptedAt, Map<String, Integer> progress) {
        active.put(questId, new ActiveQuest(questId, acceptedAt, progress));
    }

    void removeActive(String questId) {
        active.remove(questId);
    }

    void setCompletion(String questId, long lastCompletedAt, int count) {
        completed.put(questId, new Completion(lastCompletedAt, count));
    }

    void completeActive(String questId, long now) {
        active.remove(questId);
        Completion previous = completed.get(questId);
        completed.put(questId, new Completion(now, previous == null ? 1 : previous.count() + 1));
    }

    void clear(String questId) {
        active.remove(questId);
        completed.remove(questId);
    }

    /** @return false if an accept for this quest is already in flight */
    boolean markPending(String questId) {
        return pending.add(questId);
    }

    void clearPending(String questId) {
        pending.remove(questId);
    }
}
