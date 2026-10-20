package org.dextur.reputation.storage;

import java.nio.file.Path;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;

import java.util.Map;
import java.util.UUID;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.application.Settings;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.ProfileRepository;

/** Owns exactly one selected backend. No fallback or storage-dependent public API. */
public final class StorageManager implements org.dextur.reputation.domain.investigation.InvestigationRepository, org.dextur.reputation.domain.policy.PolicyRepository, org.dextur.reputation.domain.recovery.RecoveryRepository, org.dextur.reputation.domain.tier.TierRepository, ProfileRepository, org.dextur.reputation.domain.VotingRepository, org.dextur.reputation.domain.protection.ProtectionRepository, AutoCloseable {
    private volatile StorageBackend backend;
    private org.dextur.reputation.domain.policy.PolicyRules featuresRules;
    private StorageLease lease;
    private Rules investigationRules;
    private org.dextur.reputation.domain.recovery.RecoveryRules recoveryRules;
    private org.dextur.reputation.domain.tier.TierRules tierRules;
    private org.dextur.reputation.domain.protection.ProtectionRules protectionRules;
    public void open(Path directory,Settings settings,Map<String,String> environment) throws Exception {
        lease=new StorageLease(directory.resolve(".storage.lock"));
        try { backend=create(directory,settings,environment,false);if(protectionRules!=null) backend.protection(protectionRules);if(tierRules!=null) backend.tiers(tierRules);if(recoveryRules!=null)backend.recovery(recoveryRules);if(featuresRules!=null)backend.features(featuresRules);if(investigationRules!=null)backend.investigation(investigationRules); }
        catch(Exception e) { if(backend!=null) { backend.close();backend=null; }lease.close();lease=null;throw e; }
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
    @Override public void protection(org.dextur.reputation.domain.protection.ProtectionRules rules) throws Exception { protectionRules=java.util.Objects.requireNonNull(rules);if(backend!=null) backend.protection(rules); }
    @Override public org.dextur.reputation.api.VoteProcessingOutcome outcome(UUID id) throws Exception { return backend().outcome(id); }
    @Override public org.dextur.reputation.domain.protection.ProtectionData.Trust inspectTrust(UUID id,String scope) throws Exception { return backend().inspectTrust(id,scope); }
    @Override public org.dextur.reputation.domain.protection.ProtectionData.Audit inspectTransition(UUID id) throws Exception { return backend().inspectTransition(id); }
    @Override public org.dextur.reputation.domain.protection.ProtectionData.Assessment inspectVote(UUID id) throws Exception { return backend().inspectVote(id); }
    @Override public java.util.List<org.dextur.reputation.domain.protection.ProtectionData.Audit> audit(String scope,long after,int limit) throws Exception { return backend().audit(scope,after,limit); }
    @Override public org.dextur.reputation.api.ReputationView review(UUID transition,UUID vote,UUID operator,String reason,org.dextur.reputation.domain.protection.ProtectionData.Review action,long expected,java.time.Instant now) throws Exception { return backend().review(transition,vote,operator,reason,action,expected,now); }
    @Override public boolean refreshTrust(UUID player,String scope,long expected,org.dextur.reputation.domain.VoterFacts facts,java.time.Instant now) throws Exception { return backend().refreshTrust(player,scope,expected,facts,now); }
    @Override public java.util.List<org.dextur.reputation.domain.protection.ProtectionData.Trust> dueTrust(String scope,java.time.Instant before,int limit) throws Exception { return backend().dueTrust(scope,before,limit); }
    @Override public boolean analyze(String scope,java.time.Instant now) throws Exception { return backend().analyze(scope,now); }
    @Override public long storageBytes() throws Exception { return backend().storageBytes(); }
    @Override public void tiers(org.dextur.reputation.domain.tier.TierRules rules) throws Exception { tierRules=java.util.Objects.requireNonNull(rules);if(backend!=null) backend.tiers(rules); }
    @Override public org.dextur.reputation.domain.tier.TierState tierState(UUID id,String scope) throws Exception { return backend().tierState(id,scope); }
    @Override public java.util.List<org.dextur.reputation.domain.tier.TierState> pendingTierSyncAfter(String scope,String after,int limit) throws Exception {return backend().pendingTierSyncAfter(scope,after,limit);}
    @Override public boolean tierSynced(UUID id,String scope,long expected,java.util.List<String> owned) throws Exception {return backend().tierSynced(id,scope,expected,owned);}
    @Override public void recovery(org.dextur.reputation.domain.recovery.RecoveryRules rules)throws Exception{recoveryRules=java.util.Objects.requireNonNull(rules);if(backend!=null)backend.recovery(rules);}
    @Override public org.dextur.reputation.domain.recovery.RecoveryRules recoveryDefinition(UUID player,String scope)throws Exception{return backend().recoveryDefinition(player,scope);}
    @Override public org.dextur.reputation.domain.recovery.RecoveryData.Change recover(org.dextur.reputation.domain.recovery.RecoveryData.Request request)throws Exception{return backend().recover(request);}
    @Override public void features(org.dextur.reputation.domain.policy.PolicyRules rules)throws Exception{featuresRules=java.util.Objects.requireNonNull(rules);if(backend!=null)backend.features(rules);}
    @Override public org.dextur.reputation.domain.policy.PolicyEngine.Input policyInput(UUID player,String scope)throws Exception{return backend().policyInput(player,scope);}
    @Override public org.dextur.reputation.domain.policy.AdministrationData.Change administer(org.dextur.reputation.domain.policy.AdministrationData.Request request)throws Exception{return backend().administer(request);}
    @Override public void investigation(Rules rules)throws Exception{investigationRules=rules;if(backend!=null)backend.investigation(rules);}
    @Override public Report investigate(Query query)throws Exception{return backend().investigate(query);}
    @Override public Mutation moderate(Request request)throws Exception{return backend().moderate(request);}
    @Override public Plan plan(Action action,String scope,Filter filter,String actor,String reason,java.time.Instant now)throws Exception{return backend().plan(action,scope,filter,actor,reason,now);}
    @Override public Record begin(UUID operation,Plan plan,java.time.Instant now)throws Exception{return backend().begin(operation,plan,now);}
    @Override public Batch advance(UUID operation,String actor,boolean cancel,java.time.Instant now)throws Exception{return backend().advance(operation,actor,cancel,now);}
    @Override public void close() {
        try { if(backend!=null) { backend.close();backend=null; } }
        catch(Exception e) { throw new IllegalStateException("Storage close failed",e); }
        finally {
            if(lease!=null) { try { lease.close(); } catch(Exception e) { throw new IllegalStateException("Storage lease close failed",e); } finally { lease=null; } }
        }
    }
}
