package org.dextur.reputation.storage;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.application.Settings;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.ProfileRepository;

/** Owns exactly one selected backend. No fallback or storage-dependent public API. */
public final class StorageManager implements ProfileRepository, org.dextur.reputation.domain.VotingRepository, AutoCloseable {
    private volatile StorageBackend backend;
    private StorageLease lease;
    public void open(Path directory,Settings settings,Map<String,String> environment) throws Exception {
        lease=new StorageLease(directory.resolve(".storage.lock"));
        try { backend=create(directory,settings,environment,false); }
        catch(Exception e) { lease.close();lease=null;throw e; }
    }
    public static StorageBackend create(Path directory,Settings settings,Map<String,String> environment,boolean migration) throws Exception {
        var storage=settings.storage();
        return switch(storage.type()) {
            case JSON -> new JsonStorage(AtomicFiles.resolve(directory,storage.jsonDirectory()),storage.maximumJsonBytes(),migration);
            case SQLITE -> SqlStorage.sqlite(AtomicFiles.resolve(directory,storage.sqliteFile()),storage.busyTimeoutMs(),migration);
            case MYSQL -> SqlStorage.mysql(settings.database(),environment,migration);
        };
    }
    public StorageBackend backend() { var value=backend;if(value==null) throw new IllegalStateException("Storage not open");return value; }
    @Override public ReputationSnapshot getOrCreate(UUID id,String scope) throws Exception { return backend().getOrCreate(id,scope); }
    @Override public PlayerLanguage language(UUID id,PlayerLanguage fallback) throws Exception { return backend().language(id,fallback); }
    @Override public void setLanguage(UUID id,PlayerLanguage language) throws Exception { backend().setLanguage(id,language); }
    @Override public org.dextur.reputation.api.VoteEntry operation(UUID id) throws Exception { return backend().operation(id); }
    @Override public org.dextur.reputation.api.VoteReceipt vote(String scope, org.dextur.reputation.api.VoteRequest request,
            org.dextur.reputation.domain.VoterFacts facts, org.dextur.reputation.domain.VotingRules rules, java.time.Instant now) throws Exception {
        return backend().vote(scope,request,facts,rules,now);
    }
    @Override public org.dextur.reputation.api.ReputationView votingView(UUID id,String scope) throws Exception { return backend().votingView(id,scope); }
    @Override public org.dextur.reputation.api.VotePage votingHistory(UUID id,String scope,org.dextur.reputation.api.VotePage.Direction direction,int page,int size) throws Exception {
        return backend().votingHistory(id,scope,direction,page,size);
    }
    @Override public void close() {
        try { if(backend!=null) { backend.close();backend=null; } }
        catch(Exception e) { throw new IllegalStateException("Storage close failed",e); }
        finally {
            if(lease!=null) { try { lease.close(); } catch(Exception e) { throw new IllegalStateException("Storage lease close failed",e); } finally { lease=null; } }
        }
    }
}
