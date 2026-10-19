package org.dextur.reputation.storage;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.JdbcDatabaseContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;

@Tag("database")
abstract class ProtectionDatabaseContract {
    @org.junit.jupiter.api.io.TempDir Path directory;
    abstract JdbcDatabaseContainer<?> container();
    Settings.Database configuration() { var db=container();return new Settings.Database(db.getHost(),db.getMappedPort(3306),db.getDatabaseName(),db.getUsername(),"DEXTURREPUTATION_DB_PASSWORD","disable",4,2000,60000); }
    Map<String,String> environment() { return Map.of("DEXTURREPUTATION_DB_PASSWORD",container().getPassword()); }
    StorageBackend open() throws Exception { return SqlStorage.mysql(configuration(),environment(),false); }
    Database database() throws Exception { var database=new Database();database.open(configuration(),environment());return database; }
    @BeforeEach void reset() throws Exception {
        try(var b=open()) { b.protection(rules(directory.resolve("reset"),false)); }
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()) {
            for(String table:SqlProtection.TABLES) s.executeUpdate("DELETE FROM "+table);
            for(String table:List.of("dr_active_votes","dr_votes","dr_profiles","dr_player_preferences","dr_storage_control")) s.executeUpdate("DELETE FROM "+table);
        }
    }
    @Test void fractionsAndRestart() throws Exception {
        var rules=rules(directory.resolve("rules"),false);StorageSnapshot before;
        try(var b=open()) { ProtectionContract.fractions(b,rules);before=b.snapshot(); }
        try(var b=open()) { assertEquals(before,b.snapshot());b.protection(rules);assertEquals(before,b.snapshot()); }
    }
    @Test void pendingAndConfirmedRestriction() throws Exception { try(var b=open()) { ProtectionContract.pending(b,rules(directory.resolve("rules"),true));b.snapshot(); } }
    @Test void pendingReplacementAndStaleApproval() throws Exception { try(var b=open()) { ProtectionContract.replacementPending(b,rules(directory.resolve("rules"),true));b.snapshot(); } }
    @Test void concurrentAndCas() throws Exception { try(var b=open()) { ProtectionContract.concurrent(b,rules(directory.resolve("rules"),false)); } }
    @Test void clustersAndVersionedBackgroundTransitionSurviveRestart() throws Exception {
        StorageSnapshot before;try(var b=open()) { ProtectionContract.clusters(b,defaults(directory.resolve("rules")));before=b.snapshot(); }
        try(var b=open()) { assertEquals(before,b.snapshot()); }
    }
    @Test void repeatAndReciprocity() throws Exception { try(var b=open()) { ProtectionContract.repeated(b,defaults(directory.resolve("rules"))); } }
    @Test void matureBurstFalsePositiveAndRapidImmatureGrowth() throws Exception { try(var b=open()) { ProtectionContract.bursts(b,defaults(directory.resolve("rules"))); } }
    @Test void phaseTwoHistoricalContributionsAreUnchanged() throws Exception { try(var b=open()) { ProtectionContract.historical(b,rules(directory.resolve("rules"),false)); } }
    @Test void voteAndReviewFailuresRollBackAllPrivateAndPublicState() throws Exception {
        var rules=rules(directory.resolve("rules"),true);
        try(var b=open();var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()) {
            b.protection(rules);var target=UUID.randomUUID();b.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"first"),young(),eligibility(),NOW);
            var pending=b.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"pending"),young(),eligibility(),NOW.plusSeconds(1));
            var positiveTarget=UUID.randomUUID();b.vote("global",vote(UUID.randomUUID(),positiveTarget,VoteType.POSITIVE,"first positive"),mature(),eligibility(),NOW);var before=b.snapshot();
            s.execute("ALTER TABLE dr_profiles ADD CONSTRAINT dr_phase3_failure CHECK (community_score=0)");
            try {
                assertThrows(Exception.class,()->b.review(UUID.randomUUID(),pending.entry().operationId(),UUID.randomUUID(),"QA rollback",Review.ACCEPT,1,NOW.plusSeconds(2)));assertEquals(before,b.snapshot());
                assertThrows(Exception.class,()->b.vote("global",vote(UUID.randomUUID(),positiveTarget,VoteType.POSITIVE,"failure"),mature(),eligibility(),NOW.plusSeconds(3)));assertEquals(before,b.snapshot());
            } finally { s.execute("ALTER TABLE dr_profiles DROP "+(container() instanceof org.testcontainers.containers.MySQLContainer?"CHECK":"CONSTRAINT")+" dr_phase3_failure"); }
        }
    }
    @Test void corruptedPrivateRecordsBlockStartup() throws Exception {
        try(var b=open()) { ProtectionContract.fractions(b,rules(directory.resolve("rules"),false)); }
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()) { s.executeUpdate("UPDATE dr_trust SET revision=revision+1"); }
        assertThrows(Exception.class,this::open);
        // Repair for subsequent fixture cleanup, not a production recovery path.
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()) { s.executeUpdate("UPDATE dr_trust SET revision=revision-1"); }
    }
    @Test void interruptedV3DdlRetriesAndTamperedChecksumIsRejected() throws Exception {
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()) {s.executeUpdate("DELETE FROM dr_schema_history WHERE version=3");}
        try(var b=open()) {b.protection(rules(directory.resolve("retry-rules"),false));}
        String checksum;
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT checksum FROM dr_schema_history WHERE version=3")) {assertTrue(r.next());checksum=r.getString(1);}
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()) {s.executeUpdate("UPDATE dr_schema_history SET checksum=REPEAT('0',64) WHERE version=3");}
        try {assertThrows(Exception.class,this::open);} finally {
            // Database.open validates migrations too: use a temporary direct JDBC connection to restore the fixture.
            var d=configuration();try(var c=java.sql.DriverManager.getConnection("jdbc:mariadb://"+d.host()+":"+d.port()+"/"+d.schema()+"?sslMode=disable",d.username(),container().getPassword());var s=c.prepareStatement("UPDATE dr_schema_history SET checksum=? WHERE version=3")) {s.setString(1,checksum);s.executeUpdate();}
        }
    }
    @Test void allSixOfflineMigrationDirectionsPreservePendingTrustAuditAndLanguages() throws Exception {
        for(var from:StorageType.values()) for(var to:StorageType.values()) if(from!=to) {
            reset();Path work=directory.resolve(from+"-"+to);StorageFixtures.resources(work);
            Files.writeString(work.resolve("storage.yml"),config(from));Files.writeString(work.resolve("target.yml"),config(to));StorageSnapshot before;
            try(var source=StorageManager.create(work,Settings.load(work),environment(),false)) {
                source.prepareMigration();source.importEmpty(StorageFixtures.sample());source.finishMigration();ProtectionContract.clusters(source,defaults(work.resolve("cluster-rules")));ProtectionContract.pending(source,rules(work.resolve("rules"),true));
                var target=UUID.randomUUID();source.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"first"),young(),eligibility(),NOW);
                source.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"pending"),young(),eligibility(),NOW.plusSeconds(1));before=source.snapshot();
                assertTrue(before.protection().assessments().stream().anyMatch(a->a.status()==Status.PENDING));
            }
            var receipt=new StorageMigration().migrate(work,work.resolve("target.yml"),work.resolve("backups"),environment());
            assertEquals(to,Settings.load(work).storage().type());assertNotNull(receipt);
            try(var target=StorageManager.create(work,Settings.load(work),environment(),false)) { assertEquals(before,target.snapshot()); }
            assertTrue(Files.exists(receipt.backupDirectory().resolve("VERIFIED.json")));
            if(from==StorageType.JSON) assertTrue(Files.exists(work.resolve("storage/json/state.json")));
            if(from==StorageType.SQLITE) assertTrue(Files.exists(work.resolve("storage/reputation.sqlite")));
        }
    }
    private String config(StorageType type) throws Exception {
        var d=configuration();return StorageFixtures.config(type).replace("host: localhost","host: "+d.host()).replace("port: 3306","port: "+d.port()).replace("database: dexturreputation","database: "+d.schema()).replace("username: dexturreputation","username: "+d.username()).replace("ssl-mode: verify-full","ssl-mode: disable");
    }
}
