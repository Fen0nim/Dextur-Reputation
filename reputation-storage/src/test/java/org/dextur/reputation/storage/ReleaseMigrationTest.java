package org.dextur.reputation.storage;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseMigrationTest {
    @TempDir Path directory;
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void killedChildKeepsSourceAndBlocksPartialTarget(StorageType source)throws Exception {
        StorageType target=source==StorageType.JSON?StorageType.SQLITE:StorageType.JSON;
        StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(source));Files.writeString(directory.resolve("target.yml"),StorageFixtures.config(target));
        StorageSnapshot expected;try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){expected=ReleaseStorageChecks.portable(b,directory);}
        byte[] before=Files.readAllBytes(directory.resolve("storage.yml"));crash(directory,Map.of());assertArrayEquals(before,Files.readAllBytes(directory.resolve("storage.yml")));
        try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){assertEquals(expected,b.snapshot());}
        assertThrows(Exception.class,()->StorageManager.create(directory,Settings.load(directory,directory.resolve("target.yml")),Map.of(),false));
        assertThrows(Exception.class,()->new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("retry-backups"),Map.of()));
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("storage.yml")));
        try(var paths=Files.list(directory.resolve("crash-backups"))){Path backup=paths.findFirst().orElseThrow();assertEquals(expected,new SnapshotCodec().decode(Files.readAllBytes(backup.resolve("source.json"))).snapshot());assertFalse(Files.exists(backup.resolve("VERIFIED.json")));}
    }
    static void crash(Path directory,Map<String,String> environment)throws Exception {
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx512m","-cp",System.getProperty("release.qa.classpath"),ReleaseMigrationCrash.class.getName(),directory.toString(),directory.resolve("target.yml").toString(),directory.resolve("crash-backups").toString()).redirectErrorStream(true).redirectOutput(directory.resolve("crashed-child.log").toFile());p.environment().putAll(environment);
        Process child=p.start();assertTrue(child.waitFor(60,TimeUnit.SECONDS));assertEquals(73,child.exitValue(),()->"Child did not reach crash checkpoint; see "+directory.resolve("crashed-child.log"));
    }
}
