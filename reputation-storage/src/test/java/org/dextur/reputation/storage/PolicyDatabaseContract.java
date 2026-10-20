package org.dextur.reputation.storage;
import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.containers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.RecoveryFixtures.*;
@Tag("database") abstract class PolicyDatabaseContract {
    @org.junit.jupiter.api.io.TempDir Path directory;
    abstract JdbcDatabaseContainer<?> container();
    Settings.Database configuration(){var c=container();return new Settings.Database(c.getHost(),c.getMappedPort(3306),c.getDatabaseName(),c.getUsername(),"DEXTURREPUTATION_DB_PASSWORD","disable",4,2000,60000);}
    Map<String,String> environment(){return Map.of("DEXTURREPUTATION_DB_PASSWORD",container().getPassword());}
    StorageBackend open()throws Exception{return SqlStorage.mysql(configuration(),environment(),false);}
    Database database()throws Exception{var db=new Database();db.open(configuration(),environment());return db;}
    @BeforeEach void reset()throws Exception{try(var b=open()){b.features(PolicyConfiguration.load(directory));}try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){var tables=new ArrayList<>(SqlProtection.TABLES);tables.addAll(List.of("dr_administration","dr_recovery","dr_tiers","dr_active_votes","dr_votes","dr_profiles","dr_player_preferences","dr_storage_control"));for(var table:tables)s.executeUpdate("DELETE FROM "+table);}}
    @ParameterizedTest @EnumSource(PolicyContract.Scenario.class)void equivalentContracts(PolicyContract.Scenario scenario)throws Exception{try(var b=open()){PolicyContract.run(scenario,b,directory);}}
    @Test void restartAndV6()throws Exception{StorageSnapshot before;try(var b=open()){PolicyContract.run(PolicyContract.Scenario.SEMANTICS,b,directory);before=b.snapshot();}try(var b=open()){assertEquals(before,b.snapshot());}try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT MAX(version) FROM dr_schema_history")){assertTrue(r.next());assertEquals(6,r.getInt(1));}}
    @Test void failedAdjustmentRollsBackAllAndCorruptionFailsStartup()throws Exception{try(var b=open();var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){seed(b,directory,rules(org.dextur.reputation.domain.recovery.RecoveryRules.Type.ACTIVE_PLAYTIME));PolicyContract.configured(b,directory);var before=b.snapshot();s.execute("ALTER TABLE dr_profiles ADD CONSTRAINT dr_admin_failure CHECK(system_score=0)");try{assertThrows(Exception.class,()->b.administer(PolicyContract.admin(PLAYER,org.dextur.reputation.domain.policy.AdministrationData.Type.SET,100,NOW.plusSeconds(100))));assertEquals(before,b.snapshot());}finally{s.execute("ALTER TABLE dr_profiles DROP "+(container() instanceof MySQLContainer?"CHECK":"CONSTRAINT")+" dr_admin_failure");}b.administer(PolicyContract.admin(PLAYER,org.dextur.reputation.domain.policy.AdministrationData.Type.ADD,1,NOW.plusSeconds(100)));s.executeUpdate("UPDATE dr_administration SET revision=revision+1");}assertThrows(Exception.class,this::open);try(var db=database();var c=db.dataSource().getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE dr_administration SET revision=revision-1");}}
    @Test void allSixMigrationDirectionsKeepCompleteSnapshot()throws Exception{for(var from:StorageType.values())for(var to:StorageType.values())if(from!=to){reset();Path work=directory.resolve(from+"-"+to);StorageFixtures.resources(work);Files.writeString(work.resolve("storage.yml"),config(from));Files.writeString(work.resolve("target.yml"),config(to));StorageSnapshot before;try(var b=StorageManager.create(work,Settings.load(work),environment(),false)){PolicyContract.run(PolicyContract.Scenario.SEMANTICS,b,work);b.administer(PolicyContract.admin(PLAYER,org.dextur.reputation.domain.policy.AdministrationData.Type.LOCK,0,NOW.plusSeconds(200)));b.administer(PolicyContract.override(PLAYER,"chat.send",org.dextur.reputation.domain.policy.AdministrationData.Override.FORCE_DENY,NOW.plusSeconds(200)));before=b.snapshot();}new StorageMigration().migrate(work,work.resolve("target.yml"),work.resolve("backups"),environment());try(var b=StorageManager.create(work,Settings.load(work),environment(),false)){assertEquals(before,b.snapshot());}}}
    String config(StorageType type)throws Exception{var d=configuration();return StorageFixtures.config(type).replace("host: localhost","host: "+d.host()).replace("port: 3306","port: "+d.port()).replace("database: dexturreputation","database: "+d.schema()).replace("username: dexturreputation","username: "+d.username()).replace("ssl-mode: verify-full","ssl-mode: disable");}
}
