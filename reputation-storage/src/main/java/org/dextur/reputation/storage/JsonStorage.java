package org.dextur.reputation.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import java.time.Instant;
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
    private ProtectionRules protectionRules;
    private org.dextur.reputation.domain.tier.TierRules tierRules;
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
        available();
        if(tierRules!=null) {
            var previous=next.tiers().isEmpty()?state.tiers():next.tiers();var index=new HashMap<String,org.dextur.reputation.domain.tier.TierState>();
            for(var row:previous.states())index.put(row.key(),row);
            var tiers=new ArrayList<org.dextur.reputation.domain.tier.TierState>();var profiles=new ArrayList<ReputationSnapshot>();
            for(var p:next.profiles()) {var row=TierSnapshot.reconcile(p,index.get(p.playerId()+"/"+p.scopeId()),tierRules);tiers.add(row);profiles.add(TierSnapshot.profile(p,row));}
            next=new StorageSnapshot(profiles,next.preferences(),next.votes(),next.protection(),new TierSnapshot(tiers));
        } else if(!state.tiers().isEmpty() && next.tiers().isEmpty()) {
            // Existing tier data cannot be discarded by a writer without its policy.
            throw new IOException("Tier configuration required for writes to Phase 4 data");
        }
        byte[] bytes=codec.encode(Math.addExact(generation,1),next);
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
        commit(new StorageSnapshot(profiles,state.preferences(),state.votes(),state.protection()));return JournalVoting.profile(state,id,scope,now);
    }
    @Override public synchronized PlayerLanguage language(UUID id,PlayerLanguage fallback) throws IOException {
        available();return state.preferences().stream().filter(p -> p.playerId().equals(id)).map(StorageSnapshot.Preference::language).findFirst().orElse(fallback);
    }
    @Override public synchronized void setLanguage(UUID id,PlayerLanguage language) throws Exception {
        available();Objects.requireNonNull(language);
        for(var p:state.preferences()) if(p.playerId().equals(id)&&p.language()==language) return;
        var preferences=new ArrayList<>(state.preferences());preferences.removeIf(p -> p.playerId().equals(id));
        preferences.add(new StorageSnapshot.Preference(id,language,StorageSnapshot.now()));commit(new StorageSnapshot(state.profiles(),preferences,state.votes(),state.protection()));
    }
    @Override public synchronized org.dextur.reputation.api.VoteEntry operation(UUID id) throws IOException {
        available();return state.votes().stream().filter(v -> v.operationId().equals(id)).findFirst().orElse(null);
    }
    @Override public synchronized org.dextur.reputation.api.VoteReceipt vote(String scope, org.dextur.reputation.api.VoteRequest request,
            org.dextur.reputation.domain.VoterFacts facts, org.dextur.reputation.domain.VotingRules rules, java.time.Instant now) throws Exception {
        available();
        if(protectionRules!=null) {
            var protectedResult=JournalProtection.apply(state,scope,request,facts,rules,protectionRules,now);
            if(!protectedResult.receipt().duplicate()) commit(protectedResult.state());return new VoteReceipt(protectedResult.receipt().entry(),protectedResult.receipt().duplicate(),JournalVoting.view(state,request.targetId(),scope,now));
        }
        if(!state.protection().isEmpty()) throw new IllegalStateException("Protection configuration required for voting");
        var result=JournalVoting.apply(state,scope,request,facts,rules,now);
        if(!result.receipt().duplicate()) commit(result.state());return new VoteReceipt(result.receipt().entry(),result.receipt().duplicate(),JournalVoting.view(state,request.targetId(),scope,now));
    }
    @Override public synchronized org.dextur.reputation.api.ReputationView votingView(UUID player,String scope) throws Exception {
        available();getOrCreate(player,scope);return JournalVoting.view(state,player,scope,StorageSnapshot.now());
    }
    @Override public synchronized org.dextur.reputation.api.VotePage votingHistory(UUID player,String scope,org.dextur.reputation.api.VotePage.Direction direction,int page,int size) throws Exception {
        available();return JournalVoting.history(state,player,scope,direction,page,size);
    }
    @Override public synchronized void protection(ProtectionRules rules) throws Exception { available();protectionRules=Objects.requireNonNull(rules); }
    @Override public synchronized VoteProcessingOutcome outcome(UUID id) throws Exception { available();return JournalProtection.outcome(state,id); }
    @Override public synchronized Trust inspectTrust(UUID id,String scope) throws Exception { available();return state.protection().trusts().stream().filter(t->t.player().equals(id)&&t.scope().equals(scope)).findFirst().orElse(null); }
    @Override public synchronized Audit inspectTransition(UUID id) throws Exception { available();return state.protection().audit().stream().filter(a->a.id().equals(id)).findFirst().orElse(null); }
    @Override public synchronized Assessment inspectVote(UUID id) throws Exception { available();return state.protection().assessments().stream().filter(t->t.voteId().equals(id)).findFirst().orElse(null); }
    @Override public synchronized List<Audit> audit(String scope,long after,int limit) throws Exception {
        available();if(after<0||limit<1||limit>100) throw new IllegalArgumentException("Audit page bounds");
        return state.protection().audit().stream().filter(a->a.scope().equals(scope)&&a.sequence()>after).limit(limit).toList();
    }
    @Override public synchronized ReputationView review(UUID transition,UUID id,UUID operator,String reason,Review action,long revision,Instant now) throws Exception {
        available();Objects.requireNonNull(protectionRules,"Protection not configured");
        var event=state.votes().stream().filter(v->v.operationId().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown vote"));
        var duplicate=state.protection().audit().stream().filter(a->a.id().equals(transition)).findFirst().orElse(null);
        if(duplicate!=null) { JournalProtection.duplicateReview(duplicate,id,operator,reason,action,revision);return JournalVoting.view(state,event.targetId(),event.scope(),now); }
        if(state.votes().stream().anyMatch(v->v.operationId().equals(transition))) throw new VoteRejectedException(VoteRejectedException.Code.ID_CONFLICT);
        var context=JournalProtection.context(state,event.voterId(),event.targetId(),event.scope(),protectionRules,now);
        var change=ProtectionEngine.review(transition,operator,reason,action,revision,event,JournalVoting.profile(state,event.targetId(),event.scope(),now),protectionRules,context,now);
        commit(JournalProtection.change(state,change,null));return JournalVoting.view(state,event.targetId(),event.scope(),now);
    }
    @Override public synchronized boolean refreshTrust(UUID player,String scope,long expected,org.dextur.reputation.domain.VoterFacts facts,Instant now) throws Exception {
        available();Objects.requireNonNull(protectionRules,"Protection not configured");var prior=inspectTrust(player,scope);
        if(prior==null&&expected==-1) {
            var rows=new ArrayList<>(state.profiles());var profile=JournalVoting.profile(state,player,scope,now);
            if(rows.stream().noneMatch(p->p.playerId().equals(player)&&p.scopeId().equals(scope))) rows.add(profile);
            var scopeState=state.protection().scopes().stream().filter(s->s.scope().equals(scope)).findFirst().orElse(ScopeState.empty(scope));
            var initial=TrustModel.initial(player,scope,facts,protectionRules,now,scopeState.revision());
            commit(new StorageSnapshot(rows,state.preferences(),state.votes(),state.protection().trust(initial)));return true;
        }
        if(prior==null||prior.revision()!=expected||now.isBefore(prior.calculatedAt())) return false;
        var context=JournalProtection.context(state,player,player,scope,protectionRules,now);
        var next=TrustModel.calculate(player,scope,facts,protectionRules,now,prior,context.scope().revision(),context.recent(),false);
        commit(new StorageSnapshot(state.profiles(),state.preferences(),state.votes(),state.protection().trust(next)));return true;
    }
    @Override public synchronized List<Trust> dueTrust(String scope,Instant before,int limit) throws Exception {
        available();if(limit<1||limit>100) throw new IllegalArgumentException("Trust batch bounds");
        return state.protection().trusts().stream().filter(t->t.scope().equals(scope)&&!t.calculatedAt().isAfter(before)).sorted(Comparator.comparing(Trust::calculatedAt).thenComparing(Trust::key)).limit(limit).toList();
    }
    @Override public synchronized boolean analyze(String scope,Instant now) throws Exception {
        available();Objects.requireNonNull(protectionRules,"Protection not configured");
        var latest=state.votes().reversed().stream().filter(v->v.scope().equals(scope)).findFirst().orElse(null);
        if(latest==null||latest.createdAt().isBefore(now.minusSeconds(protectionRules.lookbackDays()*86400L))) return false;
        var view=JournalProtection.context(state,latest.voterId(),latest.targetId(),scope,protectionRules,now);
        long before=view.score()==null?org.dextur.reputation.domain.protection.Fixed.units(JournalVoting.profile(state,latest.targetId(),scope,now).communityScore()):view.score().currentUnits();
        var change=JournalProtection.analysis(view,latest,before,protectionRules,now);if(change==null) return false;
        commit(new StorageSnapshot(state.profiles(),state.preferences(),state.votes(),state.protection().analysis(change.scope(),change.event())));return true;
    }
    @Override public synchronized void tiers(org.dextur.reputation.domain.tier.TierRules rules) throws Exception {
        available();tierRules=Objects.requireNonNull(rules);if(!state.profiles().isEmpty()) commit(state);
    }
    @Override public synchronized org.dextur.reputation.domain.tier.TierState tierState(UUID player,String scope) throws Exception {available();return state.tiers().get(player,scope);}
    @Override public synchronized List<org.dextur.reputation.domain.tier.TierState> pendingTierSyncAfter(String scope,String after,int limit) throws Exception {
        available();if(limit<1||limit>100) throw new IllegalArgumentException("Tier sync batch bounds");return state.tiers().states().stream().filter(s->s.scope().equals(scope)&&s.player().toString().compareTo(after)>0&&s.appliedRevision()<s.syncRevision()).limit(limit).toList();
    }
    @Override public synchronized boolean tierSynced(UUID player,String scope,long expected,List<String> owned) throws Exception {
        available();var old=state.tiers().get(player,scope);if(old==null||old.syncRevision()!=expected) return false;
        commit(new StorageSnapshot(state.profiles(),state.preferences(),state.votes(),state.protection(),state.tiers().put(old.synced(expected,owned))));return true;
    }
    @Override public synchronized long storageBytes() throws Exception { available();return Files.size(primary); }
    @Override public synchronized StorageSnapshot snapshot() throws IOException { available();return state; }
    @Override public synchronized void prepareMigration() throws IOException { available();AtomicFiles.replace(pending,new byte[]{1}); }
    @Override public synchronized void importEmpty(StorageSnapshot snapshot) throws Exception {
        available();if(!state.isEmpty()||!Files.exists(pending)) throw new IOException("Migration requires an empty, prepared target");commit(snapshot);
    }
    @Override public synchronized void finishMigration() throws IOException { available();Files.deleteIfExists(pending);AtomicFiles.syncDirectory(directory); }
    @Override public synchronized void close() throws IOException { if(!closed) { closed=true;lease.close(); } }
}
