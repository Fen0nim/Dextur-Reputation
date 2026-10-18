package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.application.StorageType;
import org.dextur.reputation.domain.PlayerLanguage;

public final class JsonStorage implements StorageBackend {
    public enum Point { BEFORE_WRITE, BEFORE_RENAME, AFTER_RENAME }
    @FunctionalInterface public interface Fault { void at(Point point) throws IOException; }
    private final Path directory,primary,pending;
    private final int maximum;
    private final SnapshotCodec codec=new SnapshotCodec();
    private final StorageLease lease;
    private final Fault fault;
    private StorageSnapshot state;
    private long generation;
    private boolean poisoned,closed;
    public JsonStorage(Path directory,int maximum,boolean migration) throws Exception { this(directory,maximum,migration,p -> {}); }
    public JsonStorage(Path directory,int maximum,boolean migration,Fault fault) throws Exception {
        this.directory=directory;this.maximum=maximum;this.fault=fault;
        AtomicFiles.directories(directory); lease=new StorageLease(directory.resolve("owner.lock"));
        primary=directory.resolve("state.json");pending=directory.resolve("migration.pending");
        try {
            if(!migration&&Files.exists(pending)) throw new IOException("Unverified migration blocks JSON startup");
            if(Files.exists(primary)) {
                var document=codec.decode(read(primary));state=document.snapshot();generation=document.generation();
            } else {
                if(Files.exists(directory.resolve("state.json.bak"))||Files.exists(directory.resolve("state.json.tmp"))) throw new IOException("Missing primary; explicit JSON recovery required");
                state=StorageSnapshot.empty();generation=0;AtomicFiles.replace(primary,codec.encode(generation,state));
            }
        } catch(Exception e) { lease.close();throw e; }
    }
    private byte[] read(Path path) throws IOException {
        if(Files.isSymbolicLink(path)||Files.size(path)>maximum) throw new IOException("Unsafe or oversized JSON file");
        return Files.readAllBytes(path);
    }
    @Override public StorageType type() { return StorageType.JSON; }
    private void available() throws IOException { if(closed||poisoned) throw new IOException("JSON storage closed or uncertain write; restart and verify files"); }
    private void commit(StorageSnapshot next) throws Exception {
        available();byte[] bytes=codec.encode(Math.addExact(generation,1),next);
        if(bytes.length>maximum) throw new IOException("JSON size limit reached");
        try {
            fault.at(Point.BEFORE_WRITE);
            byte[] previous=read(primary);
            var disk=codec.decode(previous);
            if(disk.generation()!=generation||!disk.snapshot().equals(state)) throw new IOException("JSON changed outside its owner; refusing overwrite");
            AtomicFiles.replace(directory.resolve("state.json.bak"),previous);
            AtomicFiles.write(directory.resolve("state.json.tmp"),bytes);
            fault.at(Point.BEFORE_RENAME);
            Files.move(directory.resolve("state.json.tmp"),primary,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            fault.at(Point.AFTER_RENAME);AtomicFiles.syncDirectory(directory);
            state=next;generation++;
        } catch(Exception e) { poisoned=true;throw e; }
    }
    @Override public synchronized ReputationSnapshot getOrCreate(UUID id,String scope) throws Exception {
        available();for(var p:state.profiles()) if(p.playerId().equals(id)&&p.scopeId().equals(scope)) return p;
        var now=StorageSnapshot.now(); var profile=new ReputationSnapshot(id,scope,0,0,0,"NEUTRAL",0,now,now);
        var profiles=new ArrayList<>(state.profiles());profiles.add(profile);
        commit(new StorageSnapshot(profiles,state.preferences()));return profile;
    }
    @Override public synchronized PlayerLanguage language(UUID id,PlayerLanguage fallback) throws IOException {
        available();return state.preferences().stream().filter(p -> p.playerId().equals(id)).map(StorageSnapshot.Preference::language).findFirst().orElse(fallback);
    }
    @Override public synchronized void setLanguage(UUID id,PlayerLanguage language) throws Exception {
        available();Objects.requireNonNull(language);
        for(var p:state.preferences()) if(p.playerId().equals(id)&&p.language()==language) return;
        var preferences=new ArrayList<>(state.preferences());preferences.removeIf(p -> p.playerId().equals(id));
        preferences.add(new StorageSnapshot.Preference(id,language,StorageSnapshot.now()));commit(new StorageSnapshot(state.profiles(),preferences));
    }
    @Override public synchronized StorageSnapshot snapshot() throws IOException { available();return state; }
    @Override public synchronized void prepareMigration() throws IOException { available();AtomicFiles.replace(pending,new byte[]{1}); }
    @Override public synchronized void importEmpty(StorageSnapshot snapshot) throws Exception {
        available();if(!state.isEmpty()||!Files.exists(pending)) throw new IOException("Migration requires an empty, prepared target");commit(snapshot);
    }
    @Override public synchronized void finishMigration() throws IOException { available();Files.deleteIfExists(pending);AtomicFiles.syncDirectory(directory); }
    @Override public synchronized void close() throws IOException { if(!closed) { closed=true;lease.close(); } }
}
