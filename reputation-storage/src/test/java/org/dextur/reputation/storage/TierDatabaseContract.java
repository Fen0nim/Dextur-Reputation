package org.dextur.reputation.storage;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.JdbcDatabaseContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;

@Tag("database")
abstract class TierDatabaseContract {
    @org.junit.jupiter.api.io.TempDir Path directory;
    abstract JdbcDatabaseContainer<?> container();
    Settings.Database configuration() {var db=container();return new Settings.Database(db.getHost(),db.getMappedPort(3306),db.getDatabaseName(),db.getUsername(),"DEXTURREPUTATION_DB_PASSWORD","disable",4,2000,60000);}
    Map<String,String> environment(){return Map.of("DEXTURREPUTATION_DB_PASSWORD",container().getPassword());}
    StorageBackend open() throws Exception{return SqlStorage.mysql(configuration(),environment(),false);}
    Database database() throws Exception{var db=new Database();db.open(configuration(),environment());return db;}
    @BeforeEach void reset() throws Exception {
        try(var b=open()){b.tiers(TierConfiguration.load(directory));}
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){
            var tables=new ArrayList<>(SqlProtection.TABLES);tables.addAll(List.of("dr_tiers","dr_active_votes","dr_votes","dr_profiles","dr_player_preferences","dr_storage_control"));for(String table:tables)s.executeUpdate("DELETE FROM "+table);
        }
    }
    @Test void reconciliationCrossesBatchBoundariesAndKeepsEveryScope() throws Exception {try(var backend=open()){TierContract.scale(backend,directory);}}
    @Test void positiveAndNegativeHysteresisSurviveRestart() throws Exception {
        StorageSnapshot before;UUID target;try(var b=open()){target=TierContract.hysteresis(b,directory,false);before=b.snapshot();}
        try(var b=open()){assertEquals(before,b.snapshot());b.tiers(TierConfiguration.load(directory));assertEquals(before,b.snapshot());assertEquals("RESPECTED",b.getOrCreate(target,"global").tier());}
        reset();try(var b=open()){TierContract.hysteresis(b,directory,true);}
    }
    @Test void pendingApprovalInvalidationAndConcurrentTransitions() throws Exception {try(var b=open()){TierContract.pending(b,directory);}reset();try(var b=open()){TierContract.concurrent(b,directory);}}
    @Test void tierFailureRollsBackVoteAndProfile() throws Exception {
        try(var b=open();var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()) {
            UUID target=TierContract.seed(b,99);b.tiers(TierConfiguration.load(directory));var before=b.snapshot();s.execute("ALTER TABLE dr_profiles ADD CONSTRAINT dr_phase4_failure CHECK (tier<>'RESPECTED')");
            try{assertThrows(Exception.class,()->b.vote("global",vote(UUID.randomUUID(),target,VoteType.POSITIVE,"failure"),mature(),eligibility(),NOW));assertEquals(before,b.snapshot());}
            finally{s.execute("ALTER TABLE dr_profiles DROP "+(container() instanceof org.testcontainers.containers.MySQLContainer?"CHECK":"CONSTRAINT")+" dr_phase4_failure");}
        }
    }
    @Test void corruptedTierPayloadBlocksStartup() throws Exception {
        try(var b=open()){TierContract.hysteresis(b,directory,false);}
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE dr_tiers SET revision=revision+1");}
        try{assertThrows(Exception.class,this::open);}finally{try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE dr_tiers SET revision=revision-1");}}
    }
    @Test void v4IsRepeatableAndOldMigrationChecksumsRemainUnchanged() throws Exception {
        Map<Integer,String> before=new TreeMap<>();try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT version,checksum FROM dr_schema_history")){while(r.next())before.put(r.getInt(1),r.getString(2));}
        assertEquals(Set.of(1,2,3,4),before.keySet());try(var b=open()){b.tiers(TierConfiguration.load(directory));}
        try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT version,checksum FROM dr_schema_history")){while(r.next())assertEquals(before.get(r.getInt(1)),r.getString(2));}
    }
    @Test void allSixOfflineDirectionsPreserveFullPhaseFourStateAndPendingGuard() throws Exception {
        for(var from:StorageType.values())for(var to:StorageType.values())if(from!=to) {
            reset();Path work=directory.resolve(from+"-"+to);StorageFixtures.resources(work);Files.writeString(work.resolve("storage.yml"),config(from));Files.writeString(work.resolve("target.yml"),config(to));StorageSnapshot before;
            try(var source=StorageManager.create(work,Settings.load(work),environment(),false)) {
                UUID id=TierContract.hysteresis(source,work,false);source.protection(rules(work.resolve("private"),true));TierContract.pending(source,work);
                source.setLanguage(id,org.dextur.reputation.domain.PlayerLanguage.en_US);var state=source.tierState(id,"global");source.tierSynced(id,"global",state.syncRevision(),List.of("reputation_owned"));
                // Keep another pending vote along with tier history and owned synchronization nodes.
                UUID target=UUID.randomUUID();source.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"a"),young(),eligibility(),NOW);source.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"pending"),young(),eligibility(),NOW.plusSeconds(1));before=source.snapshot();
                assertTrue(before.protection().assessments().stream().anyMatch(a->a.status()==org.dextur.reputation.domain.protection.ProtectionData.Status.PENDING));assertFalse(before.tiers().isEmpty());
            }
            var result=new StorageMigration().migrate(work,work.resolve("target.yml"),work.resolve("backups"),environment());assertTrue(Files.exists(result.backupDirectory().resolve("VERIFIED.json")));
            try(var target=StorageManager.create(work,Settings.load(work),environment(),false)){assertEquals(before,target.snapshot());}
        }
    }
    private String config(StorageType type) throws Exception{var d=configuration();return StorageFixtures.config(type).replace("host: localhost","host: "+d.host()).replace("port: 3306","port: "+d.port()).replace("database: dexturreputation","database: "+d.schema()).replace("username: dexturreputation","username: "+d.username()).replace("ssl-mode: verify-full","ssl-mode: disable");}
}
