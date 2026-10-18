package org.dextur.reputation.storage;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class StorageMigrationTest {
    @TempDir Path directory;
    void setup(StorageType source,StorageType target) throws Exception {
        StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(source));
        Files.writeString(directory.resolve("target.yml"),StorageFixtures.config(target));
        try(var backend=StorageManager.create(directory,Settings.load(directory),Map.of(),true)) { backend.prepareMigration();backend.importEmpty(StorageFixtures.sample());backend.finishMigration(); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void bidirectionalMigrationPreservesAllFieldsAndBackups(StorageType from) throws Exception {
        StorageType to=from==StorageType.JSON?StorageType.SQLITE:StorageType.JSON;setup(from,to);
        byte[] original=Files.readAllBytes(directory.resolve("storage.yml"));
        var result=new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of());
        assertEquals(to,Settings.load(directory).storage().type());
        assertArrayEquals(original,Files.readAllBytes(result.backupDirectory().resolve("storage-before.yml")));
        assertEquals(StorageFixtures.sample(),new SnapshotCodec().decode(Files.readAllBytes(result.backupDirectory().resolve("source.json"))).snapshot());
        try(var backend=StorageManager.create(directory,Settings.load(directory),Map.of(),false)) { assertEquals(StorageFixtures.sample(),backend.snapshot()); }
        Path source=directory.resolve("source.yml");Files.write(source,original);
        try(var backend=StorageManager.create(directory,Settings.load(directory,source),Map.of(),false)) { assertEquals(StorageFixtures.sample(),backend.snapshot()); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void interruptionLeavesOriginalConfigAndBlocksDestination(StorageType source) throws Exception {
        StorageType target=source==StorageType.JSON?StorageType.SQLITE:StorageType.JSON;setup(source,target);
        String before=Files.readString(directory.resolve("storage.yml"));
        assertThrows(Exception.class,() -> new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of(),() -> { throw new java.io.IOException("crash before activation"); }));
        assertEquals(before,Files.readString(directory.resolve("storage.yml")));
        assertThrows(Exception.class,() -> StorageManager.create(directory,Settings.load(directory,directory.resolve("target.yml")),Map.of(),false));
    }
    @Test void nonemptyDestinationAndLiveOwnerNeverChangeConfiguration() throws Exception {
        setup(StorageType.JSON,StorageType.SQLITE);String before=Files.readString(directory.resolve("storage.yml"));
        StorageManager manager=new StorageManager();manager.open(directory,Settings.load(directory),Map.of());
        try { assertThrows(Exception.class,() -> new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of())); }
        finally { manager.close(); }
        var target=Settings.load(directory,directory.resolve("target.yml"));
        try(var backend=StorageManager.create(directory,target,Map.of(),false)) { backend.getOrCreate(UUID.randomUUID(),"global"); }
        assertThrows(Exception.class,() -> new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of()));
        assertEquals(before,Files.readString(directory.resolve("storage.yml")));
    }
    @Test void corruptJsonRequiresExplicitVerifiedRecoveryWithQuarantine() throws Exception {
        StorageFixtures.resources(directory);var settings=Settings.load(directory);
        try(var backend=StorageManager.create(directory,settings,Map.of(),false)) { backend.getOrCreate(UUID.randomUUID(),"global");backend.setLanguage(UUID.randomUUID(),org.dextur.reputation.domain.PlayerLanguage.en_US); }
        Path json=directory.resolve("storage/json");var old=new SnapshotCodec().decode(Files.readAllBytes(json.resolve("state.json.bak"))).snapshot();
        Files.writeString(json.resolve("state.json"),"corrupt");
        assertThrows(Exception.class,() -> StorageManager.create(directory,settings,Map.of(),false));
        Path backup=new StorageMigration().recoverJson(directory,settings,"backup");assertEquals("corrupt",Files.readString(backup.resolve("state.json")));
        try(var backend=StorageManager.create(directory,settings,Map.of(),false)) { assertEquals(old,backend.snapshot()); }
    }
    @Test void maintenanceRequiresExplicitConfirmationInBothLanguages() {
        assertEquals(2,StorageAdmin.run(new String[]{"migrate","--language","ru_RU"}));
        assertEquals(2,StorageAdmin.run(new String[]{"migrate","--language","en_US"}));
    }
}
