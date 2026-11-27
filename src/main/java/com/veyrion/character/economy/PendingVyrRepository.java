package com.veyrion.character.economy;

import com.veyrion.character.database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Quest VYR rewards that were earned but not yet handed to the economy provider. This is a delivery ticket, not a
 * balance: a row exists only between "the quest was completed" and "the provider accepted the deposit", then it is
 * deleted. Every method runs on the database thread.
 *
 * <p>States: PENDING (safe to pay) and PAYING (a payout is in progress or its outcome is unknown after a crash;
 * never paid automatically, so a crash can never cause a double payment). A human resolves them with
 * {@code /veconomy pending}: {@code paid <id>} deletes the ticket (player was paid), {@code retry <id> confirm}
 * returns it to PENDING (player was not paid). Both only touch PAYING rows.
 */
public final class PendingVyrRepository {

    public record Pending(long id, UUID uuid, String questId, double amount) {}

    /** A ticket left in PAYING: the payout may or may not have reached the provider. */
    public record Review(long id, UUID uuid, String questId, double amount, long createdAt, long claimedAt) {}

    private final DatabaseManager database;

    public PendingVyrRepository(DatabaseManager database) {
        this.database = database;
    }

    /** Inserts a ticket inside the caller's transaction (the quest-completion transaction). Returns its id. */
    public static long insert(Connection connection, UUID uuid, String questId, double amount, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pending_vyr (uuid, quest_id, amount, state, created_at) VALUES (?, ?, ?, 'PENDING', ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, questId);
            statement.setDouble(3, amount);
            statement.setLong(4, now);
            statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT last_insert_rowid()")) {
            if (!resultSet.next()) {
                throw new SQLException("Could not read the id of the pending VYR reward");
            }
            return resultSet.getLong(1);
        }
    }

    /** PENDING -> PAYING. True only for the single caller that wins the claim. */
    public CompletableFuture<Boolean> claim(long id, long now) {
        return database.supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pending_vyr SET state = 'PAYING', claimed_at = ? WHERE id = ? AND state = 'PENDING'")) {
                statement.setLong(1, now);
                statement.setLong(2, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    /** The payout failed definitely (provider refused): PAYING -> PENDING so it is tried again later. */
    public CompletableFuture<Boolean> release(long id) {
        return database.supply(connection -> update(connection,
                "UPDATE pending_vyr SET state = 'PENDING' WHERE id = ? AND state = 'PAYING'", id) == 1);
    }

    /** Manual reconciliation: the admin verified the player WAS paid, so drop a PAYING ticket without paying. */
    public CompletableFuture<Boolean> settle(long id) {
        return database.supply(connection -> update(connection,
                "DELETE FROM pending_vyr WHERE id = ? AND state = 'PAYING'", id) == 1);
    }

    /** The provider accepted the deposit: remove the ticket. */
    public CompletableFuture<Boolean> delete(long id) {
        return database.supply(connection -> update(connection, "DELETE FROM pending_vyr WHERE id = ?", id) == 1);
    }

    public CompletableFuture<List<Pending>> listPending() {
        return database.supply(connection -> {
            List<Pending> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT id, uuid, quest_id, amount FROM pending_vyr WHERE state = 'PENDING' ORDER BY id")) {
                while (resultSet.next()) {
                    rows.add(new Pending(resultSet.getLong(1), UUID.fromString(resultSet.getString(2)),
                            resultSet.getString(3), resultSet.getDouble(4)));
                }
            }
            return rows;
        });
    }

    /** Tickets whose outcome is unknown (left in PAYING). Listed for review, never paid automatically. */
    public CompletableFuture<List<Review>> listPaying() {
        return database.supply(connection -> {
            List<Review> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT id, uuid, quest_id, amount, created_at, claimed_at FROM pending_vyr "
                                 + "WHERE state = 'PAYING' ORDER BY id")) {
                while (resultSet.next()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(resultSet.getString(2));
                    } catch (IllegalArgumentException e) {
                        uuid = new UUID(0, 0); // still listed so an admin can settle the row
                    }
                    rows.add(new Review(resultSet.getLong(1), uuid, resultSet.getString(3), resultSet.getDouble(4),
                            resultSet.getLong(5), resultSet.getLong(6)));
                }
            }
            return rows;
        });
    }

    public CompletableFuture<Integer> countPending() {
        return database.supply(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM pending_vyr WHERE state = 'PENDING'")) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        });
    }

    private static int update(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate();
        }
    }
}
