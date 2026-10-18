package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.dextur.reputation.application.Settings;

/** Offline only. Acknowledged configuration activation is the LAST operation. Old data is retained. */
public final class StorageMigration {
    @FunctionalInterface public interface Checkpoint { void afterImport() throws Exception; }
    public record Result(Path backupDirectory,int profiles,int preferences,String checksum) { }
    private final SnapshotCodec codec=new SnapshotCodec();
    public Result migrate(Path directory,Path targetConfig,Path backups,Map<String,String> environment) throws Exception {
        return migrate(directory,targetConfig,backups,environment,() -> {});
    }
    public Result migrate(Path directory,Path targetConfig,Path backups,Map<String,String> environment,Checkpoint checkpoint) throws Exception {
        Path active=directory.resolve("storage.yml");
        byte[] before=Files.readAllBytes(active), targetBytes=Files.readAllBytes(targetConfig);
        Settings from=Settings.load(directory), to=Settings.load(directory,targetConfig);
        if(identity(directory,from).equals(identity(directory,to))) throw new IOException("Source and target storage are identical");
        try(var lease=new StorageLease(directory.resolve(".storage.lock"));
            var source=StorageManager.create(directory,from,environment,false);
            var target=StorageManager.create(directory,to,environment,true)) {
            StorageSnapshot original=source.snapshot(), previous=target.snapshot();
            if(!previous.isEmpty()) throw new IOException("Target must be empty; no existing data will be overwritten");
            Path backup=backups.resolve(Instant.now().toString().replace(':','-')+"-"+UUID.randomUUID());
            AtomicFiles.directories(backup);
            AtomicFiles.replace(backup.resolve("source.json"),codec.encode(0,original));
            AtomicFiles.replace(backup.resolve("target-before.json"),codec.encode(0,previous));
            AtomicFiles.replace(backup.resolve("storage-before.yml"),before);
            AtomicFiles.replace(backup.resolve("storage-target.yml"),targetBytes);
            if(!codec.decode(Files.readAllBytes(backup.resolve("source.json"))).snapshot().equals(original)
                    ||!codec.decode(Files.readAllBytes(backup.resolve("target-before.json"))).snapshot().equals(previous)
                    ||!Arrays.equals(before,Files.readAllBytes(backup.resolve("storage-before.yml")))
                    ||!Arrays.equals(targetBytes,Files.readAllBytes(backup.resolve("storage-target.yml")))) throw new IOException("Backup verification failed");
            target.prepareMigration();
            boolean activated=false;
            try {
                target.importEmpty(original);checkpoint.afterImport();
                if(!target.snapshot().equals(original)||!source.snapshot().equals(original)) throw new IOException("Post-migration verification mismatch; source must remain offline");
                if(!Arrays.equals(before,Files.readAllBytes(active))||!Arrays.equals(targetBytes,Files.readAllBytes(targetConfig))) throw new IOException("Configuration changed during migration");
                AtomicFiles.replace(backup.resolve("VERIFIED.json"),codec.encode(0,target.snapshot()));
                target.finishMigration();
                AtomicFiles.replace(active,targetBytes);
                activated=true;
                return new Result(backup,original.profiles().size(),original.preferences().size(),codec.checksum(original));
            } finally {
                if(!activated) {
                    // If config rename happened but directory fsync failed, restore its previous contents.
                    // The pending marker still blocks the destination even if restoration itself fails.
                    target.prepareMigration();
                    if(!Arrays.equals(before,Files.readAllBytes(active))) AtomicFiles.replace(active,before);
                }
            }
        }
    }
    static String identity(Path directory,Settings settings) throws IOException {
        return switch(settings.storage().type()) {
            case JSON -> "JSON:"+AtomicFiles.resolve(directory,settings.storage().jsonDirectory());
            case SQLITE -> "SQLITE:"+AtomicFiles.resolve(directory,settings.storage().sqliteFile());
            case MYSQL -> "MYSQL:"+settings.database().host().toLowerCase(Locale.ROOT)+":"+settings.database().port()+"/"+settings.database().schema();
        };
    }
    public Path recoverJson(Path directory,Settings settings,String candidate) throws Exception {
        if(settings.storage().type()!=org.dextur.reputation.application.StorageType.JSON) throw new IOException("JSON recovery requires selected JSON backend");
        if(!Set.of("backup","temporary").contains(candidate)) throw new IOException("Choose backup or temporary");
        Path json=AtomicFiles.resolve(directory,settings.storage().jsonDirectory());
        try(var owner=new StorageLease(directory.resolve(".storage.lock"));var lock=new StorageLease(json.resolve("owner.lock"))) {
            if(Files.exists(json.resolve("migration.pending"))) throw new IOException("Recovery cannot clear a pending migration; use a fresh empty migration target");
            Path selected=json.resolve(candidate.equals("backup")?"state.json.bak":"state.json.tmp");
            if(Files.isSymbolicLink(selected)||Files.size(selected)>settings.storage().maximumJsonBytes()) throw new IOException("Invalid recovery candidate");
            byte[] bytes=Files.readAllBytes(selected);codec.decode(bytes);
            Path quarantine=json.resolve("recovery-"+UUID.randomUUID());AtomicFiles.directories(quarantine);
            for(String file:List.of("state.json","state.json.bak","state.json.tmp")) {
                Path path=json.resolve(file);if(Files.exists(path)) {
                    if(Files.isSymbolicLink(path)) throw new IOException("Symlink recovery file unsupported");
                    AtomicFiles.replace(quarantine.resolve(file),Files.readAllBytes(path));
                }
            }
            AtomicFiles.replace(json.resolve("state.json"),bytes);return quarantine;
        }
    }
}
