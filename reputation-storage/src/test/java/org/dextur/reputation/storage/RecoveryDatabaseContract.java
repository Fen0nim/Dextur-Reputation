package org.dextur.reputation.storage;
import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.recovery.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.RecoveryFixtures.*;
@Tag("database")
abstract class RecoveryDatabaseContract {
 @org.junit.jupiter.api.io.TempDir Path directory;
 abstract JdbcDatabaseContainer<?> container();
 Settings.Database configuration(){var c=container();return new Settings.Database(c.getHost(),c.getMappedPort(3306),c.getDatabaseName(),c.getUsername(),"DEXTURREPUTATION_DB_PASSWORD","disable",4,2000,60000);}
 Map<String,String> environment(){return Map.of("DEXTURREPUTATION_DB_PASSWORD",container().getPassword());}
 StorageBackend open()throws Exception{return SqlStorage.mysql(configuration(),environment(),false);}
 Database database()throws Exception{var db=new Database();db.open(configuration(),environment());return db;}
 @BeforeEach void reset()throws Exception{try(var b=open()){b.recovery(rules(RecoveryRules.Type.ACTIVE_PLAYTIME));}try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){var tables=new ArrayList<>(SqlProtection.TABLES);tables.addAll(List.of("dr_recovery","dr_tiers","dr_active_votes","dr_votes","dr_profiles","dr_player_preferences","dr_storage_control"));for(var table:tables)s.executeUpdate("DELETE FROM "+table);}}
 @Test void lifecycleCapsTrustTierAndNoFakeVotes()throws Exception{try(var b=open()){RecoveryContract.lifecycle(b,directory);}}
 @Test void concurrentEnrollmentAndReward()throws Exception{try(var b=open()){RecoveryContract.concurrency(b,directory);}}
 @Test void pauseResumeAndAdministrativeLock()throws Exception{try(var b=open()){RecoveryContract.pause(b,directory);}}
 @Test void externalDuplicateUnavailableAndConflict()throws Exception{try(var b=open()){RecoveryContract.provider(b,directory);}}
 @Test void daysSessionsAndExpiry()throws Exception{try(var b=open()){RecoveryContract.daysAndReplay(b,directory);}}
 @Test void frozenDefinition()throws Exception{try(var b=open()){RecoveryContract.frozen(b,directory);}}
 @Test void restartPersistenceAndV5()throws Exception{StorageSnapshot before;try(var b=open()){before=complete(b,directory);}try(var b=open()){assertEquals(before,b.snapshot());}try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT MAX(version) FROM dr_schema_history")){assertTrue(r.next());assertEquals(5,r.getInt(1));}}
 @Test void failedRewardRollsBackAllAndCorruptionBlocksStartup()throws Exception{try(var b=open();var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){seed(b,directory,rules(RecoveryRules.Type.ACTIVE_PLAYTIME));b.recover(request(org.dextur.reputation.domain.recovery.RecoveryData.Command.START,NOW));b.recover(sample(NOW,0,0));var before=b.snapshot();s.execute("ALTER TABLE dr_profiles ADD CONSTRAINT dr_recovery_failure CHECK(system_score=0)");try{assertThrows(Exception.class,()->b.recover(sample(NOW.plusSeconds(10),10,10)));assertEquals(before,b.snapshot());}finally{s.execute("ALTER TABLE dr_profiles DROP "+(container() instanceof MySQLContainer?"CHECK":"CONSTRAINT")+" dr_recovery_failure");}s.executeUpdate("UPDATE dr_recovery SET revision=revision+1");}assertThrows(Exception.class,this::open);try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE dr_recovery SET revision=revision-1");}}
 @Test void sixMigrationDirectionsPreserveCompleteSnapshot()throws Exception{for(var from:StorageType.values())for(var to:StorageType.values())if(from!=to){reset();Path work=directory.resolve(from+"-"+to);StorageFixtures.resources(work);Files.writeString(work.resolve("storage.yml"),config(from));Files.writeString(work.resolve("target.yml"),config(to));StorageSnapshot before;try(var b=StorageManager.create(work,Settings.load(work),environment(),false)){before=complete(b,work);}new StorageMigration().migrate(work,work.resolve("target.yml"),work.resolve("backups"),environment());try(var b=StorageManager.create(work,Settings.load(work),environment(),false)){assertEquals(before,b.snapshot());}}}

 @Test void disabledRecoveryPausesOfflineProgramsBeforeReadiness()throws Exception{try(var b=open()){RecoveryContract.disabled(b,directory);}}
 @Test void automaticEnrollmentUsesSameAtomicEligibility()throws Exception{try(var b=open()){RecoveryContract.automatic(b,directory);}}

 @Test void unfinishedProgressSurvivesRestartAndNewSession()throws Exception{StorageSnapshot before;try(var b=open()){seed(b,directory,rules(RecoveryRules.Type.ACTIVE_PLAYTIME));b.recover(request(org.dextur.reputation.domain.recovery.RecoveryData.Command.START,NOW));b.recover(sample(NOW,0,0));b.recover(sample(NOW.plusSeconds(4),4,4));before=b.snapshot();}try(var b=open()){assertEquals(before,b.snapshot());b.tiers(TierConfiguration.load(directory));b.recovery(rules(RecoveryRules.Type.ACTIVE_PLAYTIME));UUID session=UUID.randomUUID();b.recover(request(UUID.randomUUID(),org.dextur.reputation.domain.recovery.RecoveryData.Command.SAMPLE,NOW.plusSeconds(5),0,0,session,true,ZERO,"",0,Set.of()));var done=b.recover(request(UUID.randomUUID(),org.dextur.reputation.domain.recovery.RecoveryData.Command.SAMPLE,NOW.plusSeconds(11),6,6,session,false,ZERO,"",0,Set.of()));assertEquals(5,done.profile().systemScore());assertEquals(1,done.state().rewards().size());}}
 String config(StorageType type)throws Exception{var d=configuration();return StorageFixtures.config(type).replace("host: localhost","host: "+d.host()).replace("port: 3306","port: "+d.port()).replace("database: dexturreputation","database: "+d.schema()).replace("username: dexturreputation","username: "+d.username()).replace("ssl-mode: verify-full","ssl-mode: disable");}
}
