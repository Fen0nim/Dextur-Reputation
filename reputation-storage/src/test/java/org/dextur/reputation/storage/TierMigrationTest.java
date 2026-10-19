package org.dextur.reputation.storage;
import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
class TierMigrationTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void interruptedMigrationPreservesWholeTierStateAndNeverActivatesTarget(StorageType from) throws Exception {
        var to=from==StorageType.JSON?StorageType.SQLITE:StorageType.JSON;StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(from));Files.writeString(directory.resolve("target.yml"),StorageFixtures.config(to));StorageSnapshot before;
        try(var source=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){TierContract.hysteresis(source,directory,false);TierContract.pending(source,directory);before=source.snapshot();}
        assertThrows(Exception.class,()->new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of(),()->{throw new java.io.IOException("Simulated migration failure");}));
        assertEquals(from,Settings.load(directory).storage().type());try(var source=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){assertEquals(before,source.snapshot());}
        assertThrows(Exception.class,()->StorageManager.create(directory,Settings.load(directory,directory.resolve("target.yml")),Map.of(),false));
        try(var backups=Files.list(directory.resolve("backups"))){var path=backups.findFirst().orElseThrow().resolve("source.json");assertEquals(before,new SnapshotCodec().decode(Files.readAllBytes(path)).snapshot());}
    }
    @Test void explicitV4RecoveryRestoresACompleteTierSnapshotAndKeepsQuarantine() throws Exception {
        StorageFixtures.resources(directory);var settings=Settings.load(directory);StorageSnapshot backup;
        try(var backend=StorageManager.create(directory,settings,Map.of(),false)){TierContract.hysteresis(backend,directory,false);Path file=directory.resolve("storage/json/state.json.bak");backup=new SnapshotCodec().decode(Files.readAllBytes(file)).snapshot();assertFalse(backup.tiers().isEmpty());}
        Files.writeString(directory.resolve("storage/json/state.json"),"corrupt");assertThrows(Exception.class,()->StorageManager.create(directory,settings,Map.of(),false));
        var quarantine=new StorageMigration().recoverJson(directory,settings,"backup");assertEquals("corrupt",Files.readString(quarantine.resolve("state.json")));try(var backend=StorageManager.create(directory,settings,Map.of(),false)){assertEquals(backup,backend.snapshot());}
    }
}
