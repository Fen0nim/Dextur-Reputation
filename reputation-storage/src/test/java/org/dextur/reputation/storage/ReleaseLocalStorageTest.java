package org.dextur.reputation.storage;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseLocalStorageTest {
    @TempDir Path directory;
    @AfterEach void removeLinksWithoutTraversingTargets() throws Exception {PortableTestFiles.cleanupLinks();}
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void boundedMixedHighContention(StorageType type)throws Exception {try(var b=StorageFixtures.local(directory,type,false)){ReleaseStorageChecks.stress(b,directory);}}
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void stoppedPhysicalBackupRestoresCompleteState(StorageType type)throws Exception {
        StorageSnapshot expected;try(var b=StorageFixtures.local(directory,type,false)){expected=ReleaseStorageChecks.portable(b,directory);}
        Path backup=directory.resolve("external-backup"),restored=directory.resolve("restored");Files.createDirectories(backup);Files.createDirectories(restored);
        if(type==StorageType.JSON){for(String name:List.of("state.json","state.json.bak"))Files.copy(directory.resolve("json").resolve(name),backup.resolve(name));Files.createDirectories(restored.resolve("json"));for(String name:List.of("state.json","state.json.bak"))Files.copy(backup.resolve(name),restored.resolve("json").resolve(name));}
        else {Files.copy(directory.resolve("test.sqlite"),backup.resolve("test.sqlite"));Files.copy(backup.resolve("test.sqlite"),restored.resolve("test.sqlite"));}
        try(var b=StorageFixtures.local(restored,type,false)){assertEquals(expected,b.snapshot());}
        assertEquals(expected,new SnapshotCodec().decode(new SnapshotCodec().encode(0,expected)).snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=InvestigationContract.Scenario.class,names={"ACTIVE","REPLACED","RESTORE","PENDING_APPROVE","PENDING_REJECT","PENDING_INVALIDATE_RESTORE","LEGACY_REVIEW_RESTORE","MIXED_REVIEW_RESTORE","LEGACY"})
    void indexedInspectionMatchesPortableModel(InvestigationContract.Scenario scenario)throws Exception {
        try(var b=StorageFixtures.local(directory,StorageType.SQLITE,false)){ReleaseStorageChecks.inspectionParity(b,directory,scenario);}
    }
    @Test void sqliteBusyTimeoutRollsBackThenRecovers()throws Exception {
        try(var b=SqlStorage.sqlite(directory.resolve("locked.sqlite"),250,false)){
            b.getOrCreate(new UUID(1,1),"global");var before=b.snapshot();
            try(var locker=b.connect();var statement=locker.createStatement()){
                statement.execute("BEGIN IMMEDIATE");long start=System.nanoTime();assertThrows(SQLException.class,()->b.getOrCreate(new UUID(1,2),"global"));assertTrue((System.nanoTime()-start)/1e6>=150);statement.execute("ROLLBACK");
            }
            assertEquals(before,b.snapshot());assertNotNull(b.getOrCreate(new UUID(1,2),"global"));
        }
    }
    @Test void sqliteCheckpointBackupIncludesCommittedWal()throws Exception {
        Path file=directory.resolve("live.sqlite"),backup=directory.resolve("backup.sqlite");
        try(var b=SqlStorage.sqlite(file,250,false);var anchor=b.connect()){
            for(int i=0;i<20;i++)b.getOrCreate(new UUID(3,i),"global");assertTrue(Files.size(directory.resolve("live.sqlite-wal"))>0);
            try(var statement=anchor.createStatement();var r=statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            Files.copy(file,backup);try(var restored=SqlStorage.sqlite(backup,250,false)){assertEquals(b.snapshot(),restored.snapshot());}
        }
    }
    @Test void jsonLimitRetainsLastGenerationAndRecoveryCopy()throws Exception {
        Path json=directory.resolve("limited");StorageSnapshot last;byte[] bytes;
        try(var b=new JsonStorage(json,4096,false)){
            while(b.storageBytes()<3072)b.getOrCreate(UUID.randomUUID(),"global");assertTrue(b.storageBytes()>=4096*0.75);
            boolean refused=false;while(!refused){last=b.snapshot();bytes=Files.readAllBytes(json.resolve("state.json"));try{b.getOrCreate(UUID.randomUUID(),"global");}catch(JsonStorage.LimitException failure){assertEquals(4096,failure.maximumBytes);assertTrue(failure.getMessage().contains("SQLite"));assertEquals(last,b.snapshot());assertArrayEquals(bytes,Files.readAllBytes(json.resolve("state.json")));refused=true;}}
            last=b.snapshot();assertTrue(Files.isRegularFile(json.resolve("state.json.bak")));
        }
        try(var b=new JsonStorage(json,4096,false)){assertEquals(last,b.snapshot());}
    }
    @ParameterizedTest @EnumSource(value=JsonStorage.Point.class)
    void crashPointsNeverExposePartialGeneration(JsonStorage.Point point)throws Exception {
        Path json=directory.resolve("fault");StorageSnapshot before;try(var b=new JsonStorage(json,1048576,false)){b.getOrCreate(new UUID(1,1),"global");before=b.snapshot();}
        try(var b=new JsonStorage(json,1048576,false,p->{if(p==point)throw new java.io.IOException("simulated");})){assertThrows(Exception.class,()->b.getOrCreate(new UUID(2,2),"global"));assertThrows(Exception.class,b::snapshot);}
        try(var b=new JsonStorage(json,1048576,false)){assertTrue(b.snapshot().equals(before)||b.snapshot().profiles().size()==2);}
    }
    @ParameterizedTest @ValueSource(strings={"json","sqlite","backup","lock"})
    void rejectsSymlinkAncestorsAndLeavesForeignFilesUntouched(String kind)throws Exception {
        Path outside=directory.resolve("foreign"),link=directory.resolve("link");Files.createDirectories(outside);Files.writeString(outside.resolve("sentinel"),"unchanged");PortableTestFiles.directoryLink(link,outside);
        switch(kind){case "json"->assertThrows(Exception.class,()->new JsonStorage(link.resolve("nested/json"),1048576,false));case "sqlite"->assertThrows(Exception.class,()->SqlStorage.sqlite(link.resolve("nested/test.sqlite"),250,false));case "backup"->assertThrows(Exception.class,()->AtomicFiles.replace(link.resolve("sentinel"),new byte[]{1}));default->assertThrows(Exception.class,()->new StorageLease(link.resolve("owner.lock")));}
        assertEquals("unchanged",Files.readString(outside.resolve("sentinel")));assertFalse(Files.exists(outside.resolve("nested")));
    }
    @Test void newerSchemaAndSnapshotAreRejectedWithoutDowngrade()throws Exception {
        Path file=directory.resolve("newer.sqlite");try(var b=SqlStorage.sqlite(file,250,false);var c=b.connect();var q=c.createStatement()){q.execute("PRAGMA user_version=99");}
        assertThrows(Exception.class,()->SqlStorage.sqlite(file,250,false));try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var q=c.createStatement();var r=q.executeQuery("PRAGMA user_version")){assertTrue(r.next());assertEquals(99,r.getInt(1));}
        byte[] snapshot=new SnapshotCodec().encode(0,StorageSnapshot.empty());String text=new String(snapshot,java.nio.charset.StandardCharsets.UTF_8).replaceFirst("\"formatVersion\":[0-9]+","\"formatVersion\":99");assertTrue(text.contains("\"formatVersion\":99"));assertThrows(Exception.class,()->new SnapshotCodec().decode(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
