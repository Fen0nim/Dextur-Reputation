package org.dextur.reputation.storage;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseAdminTest {
    @TempDir Path directory;
    String[] arguments(String language){return new String[]{"verify","--data-directory",directory.toString(),"--confirm","STOPPED-ALL-WRITERS","--language",language};}
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void offlineVerificationPreservesCompleteSnapshotAndRejectsLiveWriter(StorageType type)throws Exception {
        StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(type));StorageSnapshot expected;
        try(var manager=new StorageManager()){manager.open(directory,Settings.load(directory),Map.of());expected=ReleaseStorageChecks.portable(manager.backend(),directory);assertEquals(1,StorageAdmin.run(arguments("ru_RU")));assertEquals(expected,manager.backend().snapshot());}
        assertEquals(0,StorageAdmin.run(arguments("ru_RU")));assertEquals(0,StorageAdmin.run(arguments("en_US")));
        try(var manager=new StorageManager()){manager.open(directory,Settings.load(directory),Map.of());assertEquals(expected,manager.backend().snapshot());}
    }
    @Test void verificationRequiresExplicitStoppedWritersAndRejectsCorruption()throws Exception {
        StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(StorageType.JSON));
        assertEquals(2,StorageAdmin.run(new String[]{"verify","--data-directory",directory.toString()}));
        try(var manager=new StorageManager()){manager.open(directory,Settings.load(directory),Map.of());manager.backend().getOrCreate(UUID.randomUUID(),"global");}
        Path state=directory.resolve("storage/json/state.json");Files.writeString(state,"corrupt");assertEquals(1,StorageAdmin.run(arguments("en_US")));assertEquals("corrupt",Files.readString(state));
    }
}
