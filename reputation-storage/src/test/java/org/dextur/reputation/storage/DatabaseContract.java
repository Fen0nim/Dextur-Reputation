package org.dextur.reputation.storage;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.dextur.reputation.application.ReputationApplication;
import org.dextur.reputation.application.Settings;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;
import static org.junit.jupiter.api.Assertions.*;

@Tag("database")
abstract class DatabaseContract {
    abstract JdbcDatabaseContainer<?> container();
    Settings.Database configuration() {
        var db = container();
        return new Settings.Database(db.getHost(), db.getMappedPort(3306), db.getDatabaseName(),
                db.getUsername(), "TEST_DB_PASSWORD", "disable", 4, 2000, 60000);
    }
    Database open() throws Exception {
        Database database = new Database();
        database.open(configuration(), Map.of("TEST_DB_PASSWORD", container().getPassword()));
        return database;
    }
    @Test void zeroProfileIsIdempotentScopedAndUtc() throws Exception {
        try (Database database = open()) {
            JdbcProfiles profiles = new JdbcProfiles(database::dataSource);
            UUID id = UUID.randomUUID();
            var first = profiles.getOrCreate(id, "global");
            assertEquals(first, profiles.getOrCreate(id, "global"));
            assertEquals(0, first.communityScore()); assertEquals(0, first.systemScore());
            assertEquals(0, first.visibleScore()); assertEquals(0, first.revision());
            assertEquals("NEUTRAL", first.tier());
            assertTrue(Math.abs(java.time.Duration.between(first.createdAt(), java.time.Instant.now()).toSeconds()) < 30);
            assertEquals("other", profiles.getOrCreate(id, "other").scopeId());
        }
    }
    @Test void concurrentCreationProducesExactlyOneProfile() throws Exception {
        try (Database database = open(); var workers = Executors.newFixedThreadPool(8)) {
            JdbcProfiles profiles = new JdbcProfiles(database::dataSource);
            UUID id = UUID.randomUUID();
            List<Callable<Object>> work = new ArrayList<>();
            for (int n = 0; n < 40; n++) work.add(() -> profiles.getOrCreate(id, "global"));
            var results = workers.invokeAll(work);
            Object expected = results.getFirst().get();
            for (var result : results) assertEquals(expected, result.get());
            try (var connection = database.dataSource().getConnection();
                    var query = connection.prepareStatement("SELECT COUNT(*) FROM dr_profiles WHERE player_uuid=?")) {
                query.setString(1, id.toString());
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            }
        }
    }
    @Test void languageAndProfileSurvivePoolAndApplicationRestart() throws Exception {
        UUID id = UUID.randomUUID();
        org.dextur.reputation.api.ReputationSnapshot first;
        Settings appSettings = new Settings("global", PlayerLanguage.ru_RU, 10, 30, 2, 32, 10, false, configuration());
        Database database = open();
        try (var app = new ReputationApplication(new JdbcProfiles(database::dataSource), appSettings, database::close)) {
            first = app.getProfile(id).toCompletableFuture().join();
            app.setLanguage(id, PlayerLanguage.en_US).toCompletableFuture().join();
            app.setLanguage(id, PlayerLanguage.en_US).toCompletableFuture().join();
        }
        Database restarted = open();
        try (var app = new ReputationApplication(new JdbcProfiles(restarted::dataSource), appSettings, restarted::close)) {
            assertEquals(first, app.getProfile(id).toCompletableFuture().join());
            assertEquals(PlayerLanguage.en_US, app.language(id).toCompletableFuture().join());
        }
    }
    @Test void migrationIsRepeatableAndDetectsTamperingWithoutDataLoss() throws Exception {
        try (Database database = open()) {
            new SchemaMigrator().migrate(database.dataSource());
            UUID id = UUID.randomUUID();
            var profiles = new JdbcProfiles(database::dataSource);
            var before = profiles.getOrCreate(id, "global");
            String checksum;
            try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT checksum FROM dr_schema_history WHERE version=1")) {
                assertTrue(rows.next()); checksum = rows.getString(1);
            }
            try {
                try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE dr_schema_history SET checksum=REPEAT('0',64) WHERE version=1");
                }
                assertThrows(SQLException.class, () -> new SchemaMigrator().migrate(database.dataSource()));
                assertEquals(before, profiles.getOrCreate(id, "global"));
            } finally {
                try (var connection = database.dataSource().getConnection();
                        var statement = connection.prepareStatement("UPDATE dr_schema_history SET checksum=? WHERE version=1")) {
                    statement.setString(1, checksum); statement.executeUpdate();
                }
            }
        }
    }
    @Test void missingCredentialAndDisconnectedPoolFailExplicitly() throws Exception {
        try (Database database = new Database()) {
            assertThrows(IllegalArgumentException.class, () -> database.open(configuration(), Map.of()));
        }
        Database database = open();
        var source = database.dataSource();
        database.close();
        assertThrows(SQLException.class, () -> source.getConnection());
    }
    @Test void unavailableDatabaseFailsStartupAndClosesPool() throws Exception {
        int unused;
        try (var socket = new java.net.ServerSocket(0)) { unused = socket.getLocalPort(); }
        Settings.Database failed = new Settings.Database("127.0.0.1", unused, "reputation", "reputation",
                "TEST_DB_PASSWORD", "disable", 1, 250, 60000);
        try (Database database = new Database()) {
            assertThrows(SQLException.class, () -> database.open(failed, Map.of("TEST_DB_PASSWORD", "isolated-test-only")));
            assertThrows(IllegalStateException.class, database::dataSource);
        }
    }
    @Test void databaseConstraintsRejectInvalidLanguageAndRollbackTransaction() throws Exception {
        try (Database database = open(); var connection = database.dataSource().getConnection()) {
            UUID id = UUID.randomUUID();
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("INSERT INTO dr_player_preferences(player_uuid,language) VALUES(?,?)")) {
                statement.setString(1, id.toString()); statement.setString(2, "en_US"); statement.executeUpdate();
                statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, "xx_XX");
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
            assertEquals(PlayerLanguage.ru_RU, new JdbcProfiles(database::dataSource).language(id, PlayerLanguage.ru_RU));
        }
    }
}
