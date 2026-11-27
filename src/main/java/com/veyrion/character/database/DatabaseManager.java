package com.veyrion.character.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the SQLite database file and runs all database work on a dedicated single thread.
 *
 * <p>SQLite allows one writer at a time, so a single worker thread both avoids lock contention and keeps
 * every query off the Minecraft main thread. Each unit of work gets its own short-lived connection.
 */
public final class DatabaseManager implements AutoCloseable {

    /** Current schema version, stored in SQLite's {@code user_version} pragma for future migrations. */
    private static final int SCHEMA_VERSION = 3;
    private static final long INIT_TIMEOUT_SECONDS = 15;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    /** A database operation that receives a connection and may throw {@link SQLException}. */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final Logger logger;
    private final Path databaseFile;
    private final String jdbcUrl;
    private final int busyTimeoutMs;
    private final ExecutorService executor;
    private volatile boolean closed;

    public DatabaseManager(Logger logger, Path databaseFile, int busyTimeoutMs) {
        this.logger = logger;
        this.databaseFile = databaseFile.toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        this.busyTimeoutMs = busyTimeoutMs;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VeyrionCharacter-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Verifies the JDBC driver and creates/validates the schema. Intended for plugin startup only: it blocks
     * the calling thread until the schema is ready, because the plugin cannot operate without it.
     *
     * @throws DatabaseException if the driver is missing or the schema cannot be prepared
     */
    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new DatabaseException("SQLite JDBC driver (org.sqlite.JDBC) is not available on this server", e);
        }

        try {
            supply(connection -> {
                createSchema(connection);
                return null;
            }).get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DatabaseException databaseException) {
                throw databaseException;
            }
            throw new DatabaseException("Database initialization failed", cause);
        } catch (TimeoutException e) {
            throw new DatabaseException("Database initialization timed out after " + INIT_TIMEOUT_SECONDS + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("Interrupted while initializing the database", e);
        }
    }

    /**
     * Runs {@code work} on the database thread with a fresh connection that is always closed afterwards.
     * The returned future completes exceptionally with a {@link DatabaseException} on failure.
     */
    public <T> CompletableFuture<T> supply(SqlFunction<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new DatabaseException("Database manager is closed"));
            return future;
        }
        try {
            executor.execute(() -> {
                try (Connection connection = openConnection()) {
                    future.complete(work.apply(connection));
                } catch (SQLException e) {
                    future.completeExceptionally(new DatabaseException("Database operation failed", e));
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new DatabaseException("Database manager is shutting down", e));
        }
        return future;
    }

    /**
     * Like {@link #supply} but runs {@code work} in one transaction: everything commits together or is
     * rolled back if {@code work} throws.
     */
    public <T> CompletableFuture<T> inTransaction(SqlFunction<T> work) {
        return supply(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        });
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + busyTimeoutMs);
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // WAL is persistent in the database file and improves read/write concurrency.
            statement.execute("PRAGMA journal_mode = WAL");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS characters (
                        uuid                TEXT PRIMARY KEY NOT NULL,
                        player_name         TEXT NOT NULL,
                        race                TEXT,
                        adventure_exp       INTEGER NOT NULL DEFAULT 0,
                        adventure_rank      TEXT NOT NULL DEFAULT 'Novice',
                        created_at          INTEGER NOT NULL,
                        has_completed_gacha INTEGER NOT NULL DEFAULT 0
                            CHECK (has_completed_gacha IN (0, 1))
                    )
                    """);

            int version;
            try (ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
                version = resultSet.next() ? resultSet.getInt(1) : 0;
            }
            if (version > SCHEMA_VERSION) {
                throw new SQLException("Database schema version " + version
                        + " is newer than this plugin supports (" + SCHEMA_VERSION + "). Refusing to continue.");
            }
            if (version > 0 && version < SCHEMA_VERSION) {
                backupBeforeMigration(statement, version);
            }
            // Migrations are idempotent (IF NOT EXISTS) and user_version is bumped last, so a crash
            // mid-migration simply re-runs it on the next start. Existing data is never touched.
            if (version < 2) {
                createQuestTables(statement);
            }
            if (version < 3) {
                createPendingVyrTable(statement);
            }
            if (version < SCHEMA_VERSION) {
                statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
        }
        logger.info("Database ready (schema version " + SCHEMA_VERSION + ").");
    }

    private static void createQuestTables(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS quest_progress (
                    uuid             TEXT NOT NULL,
                    quest_id         TEXT NOT NULL,
                    status           TEXT NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED')),
                    accepted_at      INTEGER NOT NULL,
                    completed_at     INTEGER NOT NULL DEFAULT 0,
                    completion_count INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, quest_id),
                    FOREIGN KEY (uuid) REFERENCES characters (uuid) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS quest_objectives (
                    uuid         TEXT NOT NULL,
                    quest_id     TEXT NOT NULL,
                    objective_id TEXT NOT NULL,
                    progress     INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, quest_id, objective_id),
                    FOREIGN KEY (uuid) REFERENCES characters (uuid) ON DELETE CASCADE
                )
                """);
    }

    /** Delivery tickets for quest VYR rewards (not a balance; see PendingVyrRepository). */
    private static void createPendingVyrTable(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS pending_vyr (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid       TEXT NOT NULL,
                    quest_id   TEXT NOT NULL,
                    amount     REAL NOT NULL CHECK (amount > 0),
                    state      TEXT NOT NULL CHECK (state IN ('PENDING', 'PAYING')),
                    created_at INTEGER NOT NULL,
                    claimed_at INTEGER NOT NULL DEFAULT 0
                )
                """);
        // Tables made by an earlier build of this stage lack claimed_at; CREATE IF NOT EXISTS would leave them as is.
        ensureColumn(statement, "pending_vyr", "claimed_at", "INTEGER NOT NULL DEFAULT 0");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_pending_vyr_state ON pending_vyr (state)");
    }

    private static void ensureColumn(Statement statement, String table, String column, String definition)
            throws SQLException {
        boolean present = false;
        try (ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    present = true;
                }
            }
        }
        if (!present) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    /**
     * Copies the database file once per old version before an upgrade, e.g. {@code characters.db.v2.bak}. An existing
     * copy is never overwritten. A failed copy only logs: the v3 upgrade only adds a table and never rewrites data.
     */
    private void backupBeforeMigration(Statement statement, int fromVersion) {
        Path backup = databaseFile.resolveSibling(databaseFile.getFileName() + ".v" + fromVersion + ".bak");
        if (Files.exists(backup)) {
            return;
        }
        try {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)"); // fold the WAL into the main file first
            Files.copy(databaseFile, backup);
            logger.info("Database backed up to " + backup.getFileName() + " before upgrading from schema "
                    + fromVersion + ".");
        } catch (SQLException | IOException e) {
            logger.log(Level.WARNING, "Could not back up the database before upgrading; continuing.", e);
        }
    }

    /** Stops accepting work, lets queued operations finish, then shuts the worker thread down. */
    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                int dropped = executor.shutdownNow().size();
                logger.warning("Database worker did not finish in " + SHUTDOWN_TIMEOUT_SECONDS
                        + "s; " + dropped + " queued operation(s) were discarded.");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while closing the database worker", e);
        }
    }
}
