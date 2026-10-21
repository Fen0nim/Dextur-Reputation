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
import org.dextur.reputation.domain.tier.TierState;

/** A single owner-locked transaction; all reads share its generation/connection. */
abstract class InvestigationSession {
    final Rules rules;final ProtectionRules protection;final org.dextur.reputation.domain.tier.TierRules tiers;
    InvestigationSession(Rules rules,ProtectionRules protection,org.dextur.reputation.domain.tier.TierRules tiers){this.rules=Objects.requireNonNull(rules);this.protection=Objects.requireNonNull(protection);this.tiers=Objects.requireNonNull(tiers);}
    abstract ReputationSnapshot profile(UUID id,String scope)throws Exception;
    abstract ReputationSnapshot putProfile(ReputationSnapshot profile)throws Exception;
    abstract TierState tier(UUID id,String scope)throws Exception;
    abstract void putTier(TierState tier)throws Exception;
    abstract VoteStatistics statistics(UUID id,String scope)throws Exception;
    Map<String,String> voteSummary(UUID id,String scope)throws Exception {
        var values=new TreeMap<String,String>();
        for(String status:List.of("ACCEPTED","SUSPICIOUS","INVALIDATED","PENDING"))values.put("count_"+status.toLowerCase(Locale.ROOT),Long.toString(count(scope,new Filter(null,id,null,status,null,null,"","",null))));
        for(var type:VoteType.values())values.put(type==VoteType.POSITIVE?"positive":"negative",Long.toString(count(scope,new Filter(null,id,type,"ACCEPTED",null,null,"","",null))+count(scope,new Filter(null,id,type,"SUSPICIOUS",null,null,"","",null))));
        var statistics=statistics(id,scope);values.put("unique_voters",Long.toString(statistics.uniqueVoters()));values.put("active",Long.toString(statistics.positive()+statistics.negative()));return values;
    }
    abstract Trust trust(UUID id,String scope)throws Exception;
    abstract Score score(UUID id,String scope)throws Exception;
    abstract void putTrust(Trust trust)throws Exception;
    abstract AdministrationData.State administration(UUID id,String scope)throws Exception;
    abstract RecoveryData.State recovery(UUID id,String scope)throws Exception;
    abstract VoteEntry vote(UUID id)throws Exception;
    abstract Assessment assessment(UUID id)throws Exception;
    abstract View context(VoteEntry vote,Instant now)throws Exception;
    abstract VoteEntry latest(VoteEntry vote)throws Exception;
    abstract VoteEntry active(VoteEntry vote)throws Exception;
    abstract void change(Change change)throws Exception;
    abstract List<VoteEntry> select(String scope,Filter filter,int limit,long offset)throws Exception;
    abstract long count(String scope,Filter filter)throws Exception;
    abstract List<Audit> protectionAudit(String scope,UUID target,int limit,long offset)throws Exception;
    abstract Record record(Kind kind,UUID id)throws Exception;
    abstract List<Record> records(Kind kind,String scope,UUID target,UUID vote,int limit,long offset)throws Exception;
    abstract List<Record> filteredRecords(Kind kind,String scope,UUID target,Filter filter,int limit,long offset)throws Exception;
    abstract long countRecords(Kind kind,String scope,UUID target,Filter filter)throws Exception;
    abstract Report trustHistory(UUID player,String scope,int page,int size)throws Exception;
    abstract void put(Record record)throws Exception;
    abstract long recordCount()throws Exception;
    String status(VoteEntry vote)throws Exception {
        var record=record(Kind.MODERATION,vote.operationId());if(record!=null&&record.status().equals("INVALIDATED"))return "INVALIDATED";
        var a=assessment(vote.operationId());if(a!=null)return a.status().name();
        if(vote.action()==VoteEntry.Action.REVOKE)return "REVOKED";
        var active=active(vote);return active!=null&&active.operationId().equals(vote.operationId())?"ACCEPTED":"SUPERSEDED";
    }
    long contribution(VoteEntry vote)throws Exception {var a=assessment(vote.operationId());return a==null?org.dextur.reputation.domain.protection.Fixed.units(vote.contribution()):Math.multiplyExact(vote.type().sign(),a.weightUnits());}
    boolean recordSelected(Record r,Filter f){return (f.status().isEmpty()||r.status().equals(f.status()))&&(f.source().isEmpty()||f.source().equals(r.values().get("source")))&&(f.severity().isEmpty()||f.severity().equals(r.values().get("severity")))&&(f.search().isEmpty()||ProtectionCodec.encode(r).contains(f.search()))&&(f.before()==null||!r.at().isAfter(f.before()))&&(f.after()==null||!r.at().isBefore(f.after()));}
    boolean selected(VoteEntry v,Filter f)throws Exception {
        if(f.voter()!=null&&!v.voterId().equals(f.voter())||f.target()!=null&&!v.targetId().equals(f.target())||f.type()!=null&&v.type()!=f.type()||f.before()!=null&&v.createdAt().isAfter(f.before())||f.after()!=null&&v.createdAt().isBefore(f.after())||!f.status().isEmpty()&&!status(v).equals(f.status()))return false;
        if(!f.search().isEmpty()&&!v.operationId().toString().contains(f.search())&&!v.comment().contains(f.search())&&!v.reason().contains(f.search()))return false;
        var a=assessment(v.operationId());if(!f.severity().isEmpty()&&(a==null||!a.severity().name().equals(f.severity()))||!f.source().isEmpty()&&(a==null||!a.signals().contains(f.source())))return false;
        if(f.flag()!=null){if(a==null||a.signals().stream().noneMatch(signal->InvestigationEngine.flagId(a.voteId(),signal).equals(f.flag())))return false;}
        return true;
    }
}
