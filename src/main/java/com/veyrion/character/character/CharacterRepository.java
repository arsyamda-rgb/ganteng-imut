package com.veyrion.character.character;

import com.veyrion.character.database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Basic persistence operations for {@link CharacterData}. Every method runs on the database thread and
 * returns a future; callbacks therefore must not touch the Bukkit API directly.
 */
public final class CharacterRepository {

    private static final String SELECT_BY_UUID = """
            SELECT uuid, player_name, race, adventure_exp, adventure_rank, created_at, has_completed_gacha
            FROM characters WHERE uuid = ?
            """;

    private static final String INSERT_IF_ABSENT = """
            INSERT OR IGNORE INTO characters
                (uuid, player_name, race, adventure_exp, adventure_rank, created_at, has_completed_gacha)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /** Upsert that never overwrites {@code created_at}. */
    private static final String UPSERT = """
            INSERT INTO characters
                (uuid, player_name, race, adventure_exp, adventure_rank, created_at, has_completed_gacha)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = excluded.player_name,
                race = excluded.race,
                adventure_exp = excluded.adventure_exp,
                adventure_rank = excluded.adventure_rank,
                has_completed_gacha = excluded.has_completed_gacha
            """;

    private static final String UPDATE_NAME = "UPDATE characters SET player_name = ? WHERE uuid = ?";
    private static final String DELETE_BY_UUID = "DELETE FROM characters WHERE uuid = ?";

    private final DatabaseManager database;
    /**
     * Current rank table. When set, every character read from here has its rank derived from its EXP, so a
     * rank that was stored under an older rank table (config edited, ranks added or renamed) is never shown.
     */
    private volatile Supplier<RankTable> rankSource;

    public CharacterRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Makes {@link #find} and {@link #findOrCreate} return the rank that matches the EXP under the live table. */
    public void useRanks(Supplier<RankTable> ranks) {
        this.rankSource = Objects.requireNonNull(ranks, "ranks");
    }

    /** Pure: the character with its rank taken from {@code ranks} (same instance if already right). */
    public static CharacterData withCurrentRank(CharacterData data, RankTable ranks) {
        String expected = ranks.rankFor(data.adventureExp()).name();
        return expected.equals(data.adventureRank()) ? data : data.withRank(expected);
    }

    private CharacterData fresh(CharacterData data) {
        Supplier<RankTable> source = rankSource;
        return source == null ? data : withCurrentRank(data, source.get());
    }

    /**
     * Rewrites the stored rank of every character whose rank does not match its EXP under {@code ranks}.
     * Run at start-up and after a reload. Only {@code adventure_rank} is touched.
     *
     * @return how many rows were corrected
     */
    public CompletableFuture<Integer> reconcileRanks(RankTable ranks) {
        return database.inTransaction(connection -> {
            java.util.List<Object[]> fixes = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, adventure_exp, adventure_rank FROM characters");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String expected = ranks.rankFor(resultSet.getLong(2)).name();
                    if (!expected.equals(resultSet.getString(3))) {
                        fixes.add(new Object[] {expected, resultSet.getString(1)});
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE characters SET adventure_rank = ? WHERE uuid = ?")) {
                for (Object[] fix : fixes) {
                    update.setString(1, (String) fix[0]);
                    update.setString(2, (String) fix[1]);
                    update.executeUpdate();
                }
            }
            return fixes.size();
        });
    }

    /** Adds EXP and recalculates the rank in one transaction. Fails if the character does not exist. */
    public CompletableFuture<ExpResult> addExp(UUID uuid, long amount, RankTable ranks) {
        return database.inTransaction(connection -> applyExp(connection, uuid, amount, ranks));
    }

    /**
     * Adds EXP using an existing connection so callers (e.g. quest completion) can include it in their own
     * transaction. Must run on the database thread.
     */
    public static ExpResult applyExp(Connection connection, UUID uuid, long amount, RankTable ranks)
            throws SQLException {
        long oldExp;
        String oldRank;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT adventure_exp, adventure_rank FROM characters WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("No character exists for " + uuid);
                }
                oldExp = resultSet.getLong(1);
                // Not the stored rank: it may predate a change of the rank table, which made rank-ups misfire.
                oldRank = ranks.rankFor(oldExp).name();
            }
        }

        long newExp;
        try {
            newExp = Math.addExact(oldExp, amount);
        } catch (ArithmeticException e) {
            newExp = Long.MAX_VALUE;
        }
        String newRank = ranks.rankFor(newExp).name();

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE characters SET adventure_exp = ?, adventure_rank = ? WHERE uuid = ?")) {
            update.setLong(1, newExp);
            update.setString(2, newRank);
            update.setString(3, uuid.toString());
            update.executeUpdate();
        }
        return new ExpResult(oldExp, newExp, oldRank, newRank);
    }

    /** Uuid and last known name of a character. */
    public record Identity(UUID uuid, String name) {}

    /** @param invalid rows skipped because their uuid is malformed, the nil uuid, or a repeat of an earlier row */
    public record Identities(java.util.List<Identity> valid, int invalid) {}

    /** Every registered character's uuid and name (for the Veyra leaderboard). Bad rows are skipped, not fatal. */
    public CompletableFuture<Identities> identities() {
        return database.supply(connection -> {
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, player_name FROM characters");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new String[] {resultSet.getString(1), resultSet.getString(2)});
                }
            }
            return identitiesFrom(rows);
        });
    }

    /**
     * Turns raw {@code [uuid, name]} rows into identities. Pure, so it can be checked without a database. A row is
     * invalid if its uuid does not parse, is the nil uuid, or equals an earlier row's uuid once normalised (same
     * player written in a different case or format), so nobody is listed twice or under a made-up id.
     */
    public static Identities identitiesFrom(java.util.List<String[]> rows) {
        java.util.Map<UUID, Identity> unique = new java.util.LinkedHashMap<>();
        int invalid = 0;
        for (String[] row : rows) {
            UUID uuid;
            try {
                uuid = row[0] == null ? null : UUID.fromString(row[0].trim());
            } catch (IllegalArgumentException e) {
                uuid = null;
            }
            if (uuid == null || (uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0)
                    || unique.containsKey(uuid)) {
                invalid++;
                continue;
            }
            String name = row[1] == null || row[1].isBlank() ? uuid.toString().substring(0, 8) : row[1];
            unique.put(uuid, new Identity(uuid, name));
        }
        return new Identities(java.util.List.copyOf(unique.values()), invalid);
    }

    public CompletableFuture<Optional<CharacterData>> find(UUID uuid) {
        return database.supply(connection -> select(connection, uuid).map(this::fresh));
    }

    /**
     * Returns the stored character for {@code template.uuid()}, inserting {@code template} first if none
     * exists. An existing character is never overwritten, except that a changed player name is refreshed.
     */
    public CompletableFuture<CharacterData> findOrCreate(CharacterData template) {
        return database.supply(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_IF_ABSENT)) {
                bind(insert, template);
                insert.executeUpdate();
            }
            CharacterData stored = select(connection, template.uuid())
                    .map(this::fresh)
                    .orElseThrow(() -> new SQLException("Character row missing after insert: " + template.uuid()));

            if (stored.playerName().equals(template.playerName())) {
                return stored;
            }
            try (PreparedStatement update = connection.prepareStatement(UPDATE_NAME)) {
                update.setString(1, template.playerName());
                update.setString(2, stored.uuid().toString());
                update.executeUpdate();
            }
            return stored.withPlayerName(template.playerName());
        });
    }

    /** Inserts or updates a character. {@code created_at} of an existing row is preserved. */
    public CompletableFuture<Void> save(CharacterData data) {
        return database.supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                bind(statement, data);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** @return a future holding {@code true} if a character was deleted */
    public CompletableFuture<Boolean> delete(UUID uuid) {
        return database.supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_BY_UUID)) {
                statement.setString(1, uuid.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static Optional<CharacterData> select(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_UUID)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private static void bind(PreparedStatement statement, CharacterData data) throws SQLException {
        statement.setString(1, data.uuid().toString());
        statement.setString(2, data.playerName());
        if (data.race() == null) {
            statement.setNull(3, Types.VARCHAR);
        } else {
            statement.setString(3, data.race());
        }
        statement.setLong(4, data.adventureExp());
        statement.setString(5, data.adventureRank());
        statement.setLong(6, data.createdAt().toEpochMilli());
        statement.setInt(7, data.hasCompletedGacha() ? 1 : 0);
    }

    private static CharacterData map(ResultSet resultSet) throws SQLException {
        return new CharacterData(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("player_name"),
                resultSet.getString("race"),
                resultSet.getLong("adventure_exp"),
                resultSet.getString("adventure_rank"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                resultSet.getInt("has_completed_gacha") != 0);
    }
}
