package com.veyrion.character.quest;

import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.character.ExpResult;
import com.veyrion.character.character.RankTable;
import com.veyrion.character.database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/** SQLite persistence for quest progress. Every method runs on the database thread. */
public final class QuestRepository {

    public record QuestRow(String questId, QuestStatus status, long acceptedAt, long completedAt, int completionCount) {}

    public record PlayerQuestRecord(List<QuestRow> rows, Map<String, Map<String, Integer>> objectives) {}

    /** {@code claimed} is false if the quest was no longer ACTIVE, so no reward may be given.
     * {@code pendingVyrId} is the VYR delivery ticket written in the same transaction, or 0 if the quest pays no VYR. */
    public record CompletionResult(boolean claimed, @Nullable ExpResult exp, long pendingVyrId) {}

    private final DatabaseManager database;

    public QuestRepository(DatabaseManager database) {
        this.database = database;
    }

    public CompletableFuture<PlayerQuestRecord> load(UUID uuid) {
        return database.supply(connection -> {
            List<QuestRow> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT quest_id, status, accepted_at, completed_at, completion_count "
                            + "FROM quest_progress WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new QuestRow(resultSet.getString(1), QuestStatus.valueOf(resultSet.getString(2)),
                                resultSet.getLong(3), resultSet.getLong(4), resultSet.getInt(5)));
                    }
                }
            }
            Map<String, Map<String, Integer>> objectives = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT quest_id, objective_id, progress FROM quest_objectives WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        objectives.computeIfAbsent(resultSet.getString(1), key -> new HashMap<>())
                                .put(resultSet.getString(2), resultSet.getInt(3));
                    }
                }
            }
            return new PlayerQuestRecord(rows, objectives);
        });
    }

    /** Starts (or restarts, for repeatable quests) a quest. Keeps completion history; clears old progress. */
    public CompletableFuture<Void> accept(UUID uuid, String questId, long now) {
        return database.inTransaction(connection -> {
            update(connection, """
                    INSERT INTO quest_progress (uuid, quest_id, status, accepted_at, completed_at, completion_count)
                    VALUES (?, ?, 'ACTIVE', ?, 0, 0)
                    ON CONFLICT(uuid, quest_id) DO UPDATE SET status = 'ACTIVE', accepted_at = excluded.accepted_at
                    """, uuid.toString(), questId, now);
            update(connection, "DELETE FROM quest_objectives WHERE uuid = ? AND quest_id = ?", uuid.toString(), questId);
            return null;
        });
    }

    public CompletableFuture<Void> saveProgress(UUID uuid, String questId, String objectiveId, int progress) {
        return database.supply(connection -> {
            update(connection, """
                    INSERT INTO quest_objectives (uuid, quest_id, objective_id, progress) VALUES (?, ?, ?, ?)
                    ON CONFLICT(uuid, quest_id, objective_id) DO UPDATE SET progress = excluded.progress
                    """, uuid.toString(), questId, objectiveId, progress);
            return null;
        });
    }

    /** A never-completed quest row is removed; a previously completed one goes back to COMPLETED. */
    public CompletableFuture<Void> abandon(UUID uuid, String questId) {
        return database.inTransaction(connection -> {
            update(connection, "UPDATE quest_progress SET status = 'COMPLETED' "
                    + "WHERE uuid = ? AND quest_id = ? AND status = 'ACTIVE' AND completion_count > 0",
                    uuid.toString(), questId);
            update(connection, "DELETE FROM quest_progress "
                    + "WHERE uuid = ? AND quest_id = ? AND status = 'ACTIVE' AND completion_count = 0",
                    uuid.toString(), questId);
            update(connection, "DELETE FROM quest_objectives WHERE uuid = ? AND quest_id = ?", uuid.toString(), questId);
            return null;
        });
    }

    /**
     * Completes a quest and pays its EXP in ONE transaction. The ACTIVE -> COMPLETED update is the claim:
     * only the call that flips the status gets {@code claimed = true}, so rewards can never be paid twice.
     */
    public CompletableFuture<CompletionResult> complete(UUID uuid, String questId, long now, long expReward,
                                                        double vyrReward, RankTable ranks) {
        return database.inTransaction(connection -> {
            int updated = update(connection, "UPDATE quest_progress SET status = 'COMPLETED', completed_at = ?, "
                    + "completion_count = completion_count + 1 "
                    + "WHERE uuid = ? AND quest_id = ? AND status = 'ACTIVE'", now, uuid.toString(), questId);
            if (updated == 0) {
                return new CompletionResult(false, null, 0);
            }
            update(connection, "DELETE FROM quest_objectives WHERE uuid = ? AND quest_id = ?", uuid.toString(), questId);
            ExpResult exp = expReward > 0 ? CharacterRepository.applyExp(connection, uuid, expReward, ranks) : null;
            // The VYR ticket is written in this same transaction, so claim + EXP + ticket commit together or not at all.
            long ticket = vyrReward > 0
                    ? com.veyrion.character.economy.PendingVyrRepository.insert(connection, uuid, questId, vyrReward, now)
                    : 0;
            return new CompletionResult(true, exp, ticket);
        });
    }

    /** Forgets every trace of a quest for a player (also resets cooldown and one-time completion). */
    public CompletableFuture<Void> reset(UUID uuid, String questId) {
        return database.inTransaction(connection -> {
            update(connection, "DELETE FROM quest_objectives WHERE uuid = ? AND quest_id = ?", uuid.toString(), questId);
            update(connection, "DELETE FROM quest_progress WHERE uuid = ? AND quest_id = ?", uuid.toString(), questId);
            return null;
        });
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }
}
