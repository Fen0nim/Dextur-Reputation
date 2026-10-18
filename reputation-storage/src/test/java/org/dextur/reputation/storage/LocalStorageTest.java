package org.dextur.reputation.storage;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.dextur.reputation.application.StorageType;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class LocalStorageTest {
    @TempDir Path directory;
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void restartPreservesAllFieldsAndLanguageAndIdempotency(StorageType type) throws Exception {
        StorageSnapshot before;
        try(var backend=StorageFixtures.local(directory,type,false)) {
            UUID id=UUID.randomUUID();var first=backend.getOrCreate(id,"global");assertEquals(first,backend.getOrCreate(id,"global"));
            backend.setLanguage(id,PlayerLanguage.en_US);before=backend.snapshot();backend.setLanguage(id,PlayerLanguage.en_US);assertEquals(before,backend.snapshot());
            assertEquals(0,first.visibleScore());assertEquals("NEUTRAL",first.tier());
        }
        try(var backend=StorageFixtures.local(directory,type,false)) { assertEquals(before,backend.snapshot());assertEquals(PlayerLanguage.en_US,backend.language(before.profiles().getFirst().playerId(),PlayerLanguage.ru_RU)); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void concurrentProfileCreationIsUniqueAndScoped(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false);var executor=Executors.newFixedThreadPool(8)) {
            UUID id=UUID.randomUUID();List<Callable<Object>> work=new ArrayList<>();
            for(int n=0;n<40;n++) work.add(() -> backend.getOrCreate(id,"global"));
            var results=executor.invokeAll(work);Object first=results.getFirst().get();for(var r:results) assertEquals(first,r.get());
            backend.getOrCreate(id,"other");assertEquals(2,backend.snapshot().profiles().size());
        }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void fullPortableSnapshotAndPendingMigrationGuard(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,true)) {
            backend.prepareMigration();backend.importEmpty(StorageFixtures.sample());assertEquals(StorageFixtures.sample(),backend.snapshot());
        }
        assertThrows(Exception.class,() -> StorageFixtures.local(directory,type,false));
        try(var backend=StorageFixtures.local(directory,type,true)) { backend.finishMigration(); }
        try(var backend=StorageFixtures.local(directory,type,false)) { assertEquals(StorageFixtures.sample(),backend.snapshot()); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void corruptDataFailsClosed(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { backend.getOrCreate(UUID.randomUUID(),"global"); }
        if(type==StorageType.JSON) {
            Path file=directory.resolve("json/state.json");Files.writeString(file,Files.readString(file).replace("NEUTRAL","BROKEN"));
        } else Files.writeString(directory.resolve("test.sqlite"),"not a SQLite database");
        assertThrows(Exception.class,() -> StorageFixtures.local(directory,type,false));
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void exclusiveOwnershipPreventsTwoOwners(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { assertThrows(Exception.class,() -> StorageFixtures.local(directory,type,false)); }
    }
    @ParameterizedTest @EnumSource(JsonStorage.Point.class)
    void jsonFailedWritesNeverAcknowledgeOrContinueWithUncertainState(JsonStorage.Point point) throws Exception {
        try(var backend=new JsonStorage(directory,1048576,false,p -> { if(p==point) throw new java.io.IOException("simulated disk failure"); })) {
            assertThrows(Exception.class,() -> backend.getOrCreate(UUID.randomUUID(),"global"));
            assertThrows(Exception.class,backend::snapshot);
        }
        try(var backend=new JsonStorage(directory,1048576,false)) {
            assertEquals(point==JsonStorage.Point.AFTER_RENAME?1:0,backend.snapshot().profiles().size());
        }
    }
    @Test void sqliteWriteFailureRollsBackAndBusyTimeoutIsBounded() throws Exception {
        try(var backend=SqlStorage.sqlite(directory.resolve("db.sqlite"),250,false)) {
            UUID id=UUID.randomUUID();backend.getOrCreate(id,"global");
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("db.sqlite"));var s=c.createStatement()) {
                s.execute("CREATE TRIGGER reject_language BEFORE INSERT ON dr_player_preferences BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END");
                assertThrows(SQLException.class,() -> backend.setLanguage(id,PlayerLanguage.en_US));
                assertEquals(PlayerLanguage.ru_RU,backend.language(id,PlayerLanguage.ru_RU));s.execute("DROP TRIGGER reject_language");
                c.setAutoCommit(false);s.executeUpdate("UPDATE dr_profiles SET visible_score=visible_score");
                long start=System.nanoTime();assertThrows(SQLException.class,() -> backend.getOrCreate(UUID.randomUUID(),"global"));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)>=200);c.rollback();
            }
            backend.setLanguage(id,PlayerLanguage.en_US);assertEquals(PlayerLanguage.en_US,backend.language(id,PlayerLanguage.ru_RU));
        }
    }
    @Test void sqliteBulkImportRollsBackOnMidBatchFailure() throws Exception {
        try(var backend=SqlStorage.sqlite(directory.resolve("db.sqlite"),250,true);
            var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("db.sqlite"));var s=c.createStatement()) {
            backend.prepareMigration();s.execute("CREATE TRIGGER reject_other BEFORE INSERT ON dr_profiles WHEN NEW.scope_id='other' BEGIN SELECT RAISE(ABORT,'simulated failure'); END");
            assertThrows(SQLException.class,() -> backend.importEmpty(StorageFixtures.sample()));assertTrue(backend.snapshot().isEmpty());
        }
        assertThrows(Exception.class,() -> SqlStorage.sqlite(directory.resolve("db.sqlite"),250,false));
    }
    @Test void jsonRejectsDuplicateFieldsAndOversizeState() throws Exception {
        SnapshotCodec codec=new SnapshotCodec();byte[] valid=codec.encode(0,StorageFixtures.sample());
        String duplicate=new String(valid,java.nio.charset.StandardCharsets.UTF_8).replace("\"formatVersion\":2","\"formatVersion\":2,\"formatVersion\":2");
        assertThrows(Exception.class,() -> codec.decode(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.createDirectories(directory.resolve("json"));Files.write(directory.resolve("json/state.json"),valid);
        assertThrows(Exception.class,() -> new JsonStorage(directory.resolve("json"),100,false));
    }
    @Test void jsonDoesNotOverwriteExternalCorruptionOrResetMissingPrimary() throws Exception {
        try(var backend=new JsonStorage(directory,1048576,false)) {
            backend.getOrCreate(UUID.randomUUID(),"global");Files.writeString(directory.resolve("state.json"),"corrupt");
            assertThrows(Exception.class,() -> backend.setLanguage(UUID.randomUUID(),PlayerLanguage.en_US));
            assertEquals("corrupt",Files.readString(directory.resolve("state.json")));
        }
        Files.delete(directory.resolve("state.json"));
        assertThrows(Exception.class,() -> new JsonStorage(directory,1048576,false));
    }
}
