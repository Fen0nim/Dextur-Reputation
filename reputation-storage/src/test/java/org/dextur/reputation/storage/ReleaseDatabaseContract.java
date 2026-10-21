package org.dextur.reputation.storage;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("database") abstract class ReleaseDatabaseContract {
    @TempDir Path directory;
    abstract JdbcDatabaseContainer<?> container();
    Settings.Database configuration(){var c=container();return new Settings.Database(c.getHost(),c.getMappedPort(3306),c.getDatabaseName(),c.getUsername(),"DEXTURREPUTATION_DB_PASSWORD","disable",4,2000,60000);}
    Map<String,String> environment(){return Map.of("DEXTURREPUTATION_DB_PASSWORD",container().getPassword());}
    SqlStorage open()throws Exception{return SqlStorage.mysql(configuration(),environment(),false);}
    @BeforeEach void reset()throws Exception {
        // Only this class's disposable container. Raw cleanup also works after an intentional crash/pending marker.
        try(var db=new Database()){db.open(configuration(),environment());try(var c=db.dataSource().getConnection()){
            var tables=new ArrayList<String>();try(var q=c.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=?")){q.setString(1,c.getCatalog());try(var r=q.executeQuery()){while(r.next()){String table=r.getString(1);if(table.matches("dr_[a-z_]+")&&!table.equals("dr_schema_history"))tables.add(table);}}}
            try(var q=c.createStatement()){for(String table:tables)q.executeUpdate("TRUNCATE TABLE "+table);}
        }}
        try(var b=open()){ReleaseStorageChecks.configured(b,directory);}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=InvestigationContract.Scenario.class,names={"ACTIVE","REPLACED","RESTORE","PENDING_APPROVE","PENDING_REJECT","PENDING_INVALIDATE_RESTORE","LEGACY_REVIEW_RESTORE","MIXED_REVIEW_RESTORE","LEGACY"})
    void indexedInspectionMatchesPortableModel(InvestigationContract.Scenario scenario)throws Exception {
        try(var b=open()){ReleaseStorageChecks.inspectionParity(b,directory,scenario);}
    }
    @Test void boundedMixedHighContention()throws Exception {try(var b=open()){ReleaseStorageChecks.stress(b,directory);}}
    @Test void tenThousandProfilesHundredThousandVotesAndBenchmarks()throws Exception {try(var b=open()){ReleaseBenchmarks.run(b,directory,container() instanceof MySQLContainer?"MYSQL":"MARIADB",true);}}
    @Test void consistentNativeDumpRestoresAllSchemaAndData()throws Exception {
        StorageSnapshot expected;try(var b=open()){expected=ReleaseStorageChecks.portable(b,directory);}
        String binary=container() instanceof MySQLContainer?"mysqldump":"mariadb-dump",client=container() instanceof MySQLContainer?"mysql":"mariadb";
        Path dump=directory.resolve("consistent-backup.sql");var command=new ProcessBuilder("docker","exec","-e","MYSQL_PWD",container().getContainerId(),binary,"-u",container().getUsername(),"--single-transaction","--skip-lock-tables","--no-tablespaces","--skip-comments",container().getDatabaseName());command.environment().put("MYSQL_PWD",container().getPassword());command.redirectOutput(dump.toFile()).redirectError(directory.resolve("dump-errors.log").toFile());var process=command.start();assertTrue(process.waitFor(60,TimeUnit.SECONDS));assertEquals(0,process.exitValue());assertTrue(Files.size(dump)>1000);
        reset();try(var b=open()){assertTrue(b.snapshot().isEmpty());}
        command=new ProcessBuilder("docker","exec","-i","-e","MYSQL_PWD",container().getContainerId(),client,"-u",container().getUsername(),container().getDatabaseName());command.environment().put("MYSQL_PWD",container().getPassword());command.redirectInput(dump.toFile()).redirectOutput(directory.resolve("restore.log").toFile()).redirectErrorStream(true);process=command.start();assertTrue(process.waitFor(60,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        try(var b=open()){assertEquals(expected,b.snapshot());try(var c=b.connect();var q=c.createStatement();var r=q.executeQuery("SELECT MAX(version) FROM dr_schema_history")){assertTrue(r.next());assertEquals(7,r.getInt(1));}}
    }
    @Test void interruptedOwnershipFailsClosedAndFreshOwnerPreservesData()throws Exception {
        StorageSnapshot expected;try(var b=open()){
            expected=ReleaseStorageChecks.portable(b,directory);
            var field=SqlStorage.class.getDeclaredField("database");field.setAccessible(true);var db=(Database)field.get(b);var ownerField=Database.class.getDeclaredField("ownership");ownerField.setAccessible(true);var owner=(Connection)ownerField.get(db);long id;
            try(var q=owner.createStatement();var r=q.executeQuery("SELECT CONNECTION_ID()")){assertTrue(r.next());id=r.getLong(1);}
            try(var c=b.connect();var q=c.createStatement()){q.execute("KILL CONNECTION "+id);}
            assertThrows(Exception.class,()->b.getOrCreate(UUID.randomUUID(),"global"));assertThrows(Exception.class,b::snapshot);
        }
        try(var b=open()){assertEquals(expected,b.snapshot());}
    }
    @Test void unavailableDatabaseNeverActivatesAnotherBackend()throws Exception {
        var d=configuration();try(var socket=new java.net.ServerSocket(0)){int port=socket.getLocalPort();socket.close();var invalid=new Settings.Database("127.0.0.1",port,d.schema(),d.username(),d.passwordEnvironment(),"disable",1,250,30000);assertThrows(Exception.class,()->SqlStorage.mysql(invalid,environment(),false));}
        assertFalse(Files.exists(directory.resolve("storage/json/state.json")));assertFalse(Files.exists(directory.resolve("storage/reputation.sqlite")));
    }
    @Test void killedMigrationChildLeavesUnverifiedTargetInactive()throws Exception {
        StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(StorageType.JSON));var d=configuration();String target=StorageFixtures.config(StorageType.MYSQL).replace("host: localhost","host: "+d.host()).replace("port: 3306","port: "+d.port()).replace("database: dexturreputation","database: "+d.schema()).replace("username: dexturreputation","username: "+d.username()).replace("ssl-mode: verify-full","ssl-mode: disable");Files.writeString(directory.resolve("target.yml"),target);
        StorageSnapshot expected;try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){expected=ReleaseStorageChecks.portable(b,directory);}
        byte[] before=Files.readAllBytes(directory.resolve("storage.yml"));ReleaseMigrationTest.crash(directory,environment());assertArrayEquals(before,Files.readAllBytes(directory.resolve("storage.yml")));assertThrows(Exception.class,this::open);
        try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){assertEquals(expected,b.snapshot());}
        assertThrows(Exception.class,()->new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("retry"),environment()));assertArrayEquals(before,Files.readAllBytes(directory.resolve("storage.yml")));
        // Remove only this test's pending marker so @BeforeEach may re-open the owned disposable schema.
        try(var db=new Database()){db.open(configuration(),environment());try(var c=db.dataSource().getConnection();var q=c.createStatement()){q.executeUpdate("UPDATE dr_storage_control SET pending=0");}}
    }
}
