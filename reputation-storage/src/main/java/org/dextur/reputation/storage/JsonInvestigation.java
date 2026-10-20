package org.dextur.reputation.storage;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import org.dextur.reputation.domain.policy.AdministrationData;
import org.dextur.reputation.domain.recovery.RecoveryData;
import org.dextur.reputation.domain.tier.*;

final class JsonInvestigation extends InvestigationSession {
    StorageSnapshot state;
    JsonInvestigation(StorageSnapshot state,Rules rules,ProtectionRules protection,TierRules tiers){super(rules,protection,tiers);this.state=state;}
    @Override ReputationSnapshot profile(UUID id,String scope){return state.profiles().stream().filter(p->p.playerId().equals(id)&&p.scopeId().equals(scope)).findFirst().orElse(null);}
    private void replace(List<ReputationSnapshot> profiles,ProtectionSnapshot protection,InvestigationSnapshotData investigation){state=new StorageSnapshot(profiles,state.preferences(),state.votes(),protection,state.tiers(),state.recovery(),state.administration(),investigation);}
    @Override ReputationSnapshot putProfile(ReputationSnapshot p){var tier=TierSnapshot.reconcile(p,state.tiers().get(p.playerId(),p.scopeId()),tiers);p=TierSnapshot.profile(p,tier);var profiles=new ArrayList<>(state.profiles());UUID id=p.playerId();String scope=p.scopeId();profiles.removeIf(row->row.playerId().equals(id)&&row.scopeId().equals(scope));profiles.add(p);var nextTiers=state.tiers().put(tier);state=new StorageSnapshot(profiles,state.preferences(),state.votes(),state.protection(),nextTiers,state.recovery(),state.administration(),state.investigation());return p;}
    @Override TierState tier(UUID id,String scope){return state.tiers().get(id,scope);}
    @Override void putTier(TierState tier){state=new StorageSnapshot(state.profiles(),state.preferences(),state.votes(),state.protection(),state.tiers().put(tier),state.recovery(),state.administration(),state.investigation());}
    @Override VoteStatistics statistics(UUID id,String scope){return JournalVoting.view(state,id,scope,Instant.EPOCH).statistics();}
    @Override Trust trust(UUID id,String scope){return state.protection().trusts().stream().filter(t->t.player().equals(id)&&t.scope().equals(scope)).findFirst().orElse(null);}
    @Override Score score(UUID id,String scope){return state.protection().scores().stream().filter(t->t.player().equals(id)&&t.scope().equals(scope)).findFirst().orElse(null);}
    @Override void putTrust(Trust trust){replace(state.profiles(),state.protection().trust(trust),state.investigation());}
    @Override AdministrationData.State administration(UUID id,String scope){return state.administration().get(id,scope);}
    @Override RecoveryData.State recovery(UUID id,String scope){return state.recovery().get(id,scope);}
    @Override VoteEntry vote(UUID id){return state.votes().stream().filter(v->v.operationId().equals(id)).findFirst().orElse(null);}
    @Override Assessment assessment(UUID id){return state.protection().assessments().stream().filter(a->a.voteId().equals(id)).findFirst().orElse(null);}
    @Override View context(VoteEntry v,Instant now){return JournalProtection.context(state,v.voterId(),v.targetId(),v.scope(),protection,now);}
    @Override VoteEntry latest(VoteEntry v){return state.votes().reversed().stream().filter(row->row.pairKey().equals(v.pairKey())).findFirst().orElse(null);}
    @Override VoteEntry active(VoteEntry v){return ProtectionValidation.active(state.votes(),state.protection()).get(v.pairKey());}
    @Override void change(Change change){var previous=state.tiers();state=JournalProtection.change(state,change,null);var p=change.profile();var next=TierSnapshot.reconcile(p,previous.get(p.playerId(),p.scopeId()),tiers);p=TierSnapshot.profile(p,next);var profiles=new ArrayList<>(state.profiles());UUID id=p.playerId();String scope=p.scopeId();profiles.removeIf(row->row.playerId().equals(id)&&row.scopeId().equals(scope));profiles.add(p);state=new StorageSnapshot(profiles,state.preferences(),state.votes(),state.protection(),previous.put(next),state.recovery(),state.administration(),state.investigation());}
    @Override List<VoteEntry> select(String scope,Filter filter,int limit,long offset)throws Exception{var result=new ArrayList<VoteEntry>();long skip=0;for(var vote:state.votes().reversed())if(vote.scope().equals(scope)&&selected(vote,filter)){if(skip++<offset)continue;result.add(vote);if(result.size()==limit)break;}return result;}
    @Override long count(String scope,Filter filter)throws Exception{long n=0;for(var vote:state.votes())if(vote.scope().equals(scope)&&selected(vote,filter))n++;return n;}
    @Override List<Audit> protectionAudit(String scope,UUID target,int limit,long offset){return state.protection().audit().stream().filter(a->a.scope().equals(scope)&&(target==null||a.target().equals(target))).skip(offset).limit(limit).toList();}
    @Override Record record(Kind kind,UUID id){return state.investigation().get(kind,id);}
    @Override List<Record> records(Kind kind,String scope,UUID target,UUID vote,int limit,long offset){return state.investigation().records().stream().filter(r->r.kind()==kind&&r.scope().equals(scope)&&(target==null||r.target().equals(target))&&(vote==null||r.vote().equals(vote))).sorted(Comparator.comparing(Record::at).reversed().thenComparing(r->r.id().toString())).skip(offset).limit(limit).toList();}
    @Override List<Record> filteredRecords(Kind kind,String scope,UUID target,Filter filter,int limit,long offset){return state.investigation().records().stream().filter(r->r.kind()==kind&&r.scope().equals(scope)&&(target==null||r.target().equals(target))&&recordSelected(r,filter)).sorted(Comparator.comparing(Record::at).reversed().thenComparing(r->r.id().toString())).skip(offset).limit(limit).toList();}
    @Override long countRecords(Kind kind,String scope,UUID target,Filter filter){return state.investigation().records().stream().filter(r->r.kind()==kind&&r.scope().equals(scope)&&(target==null||r.target().equals(target))&&recordSelected(r,filter)).count();}
    @Override Report trustHistory(UUID player,String scope,int page,int size){var records=new ArrayList<InvestigationReports.History>();for(var a:state.protection().audit())if(a.scope().equals(scope)&&a.voter().equals(player)&&!a.kind().equals("ANALYSIS"))records.add(new InvestigationReports.History(a.createdAt(),InvestigationReports.trustAudit(a)));for(var r:state.investigation().records())if(r.kind()==Kind.AUDIT&&r.scope().equals(scope)&&r.target().equals(player)&&Set.of("RECALCULATE_TRUST","RECALCULATE_ALL").contains(r.values().get("action")))records.add(new InvestigationReports.History(r.at(),InvestigationReports.record(r)));records.sort(Comparator.comparing(InvestigationReports.History::time).reversed().thenComparing(v->v.row().id().toString()));var rows=records.stream().skip(((long)page-1)*size).limit(size).map(InvestigationReports.History::row).toList();return new Report(Map.of("uuid",player.toString(),"page",Integer.toString(page)),rows,records.size(),InvestigationEngine.hash(rows));}
    @Override void put(Record record){replace(state.profiles(),state.protection(),state.investigation().put(record));}
    @Override long recordCount(){return state.investigation().records().size();}
}
