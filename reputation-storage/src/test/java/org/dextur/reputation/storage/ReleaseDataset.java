package org.dextur.reputation.storage;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.VotingEngine;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;

/** Explicitly synthetic load data, not presented as a historical installation. */
final class ReleaseDataset {
    static final UUID HOT=new UUID(800,1);
    static final Instant TIME=Instant.parse("2026-01-01T00:00:00Z");
    static StorageSnapshot create(int players,int count,int commentLength){
        var votes=new ArrayList<VoteEntry>();var previous=new HashMap<UUID,UUID>();long score=0;String comment="Synthetic release benchmark — Юникод 😀 "+"x".repeat(commentLength);
        for(int i=0;i<count;i++){
            UUID voter=new UUID(800,2+i%(players-1)),operation=new UUID(801,i+1),prior=previous.put(voter,operation);
            var request=new VoteRequest(operation,voter,HOT,VoteRequest.Action.SUBMIT,VoteType.POSITIVE,"helpful",comment);long after=score+(prior==null?1:0);
            votes.add(new VoteEntry(operation,i+1,"global",voter,HOT,prior==null?VoteEntry.Action.CAST:VoteEntry.Action.REPLACE,VoteType.POSITIVE,"helpful",comment,TIME.plusSeconds(i),prior,1,1,1,VotingEngine.hash("global",request),score,after));score=after;
        }
        var profiles=new ArrayList<ReputationSnapshot>();for(int i=1;i<=players;i++){long value=i==1?score:0;profiles.add(new ReputationSnapshot(new UUID(800,i),"global",value,0,value,"NEUTRAL",i==1?count:0,TIME,TIME.plusSeconds(count)));}
        var records=new ArrayList<Record>();for(int i=0;i<3000;i++){
            UUID id=new UUID(802,i+1);VoteEntry vote=votes.get(i%votes.size());var fields=new TreeMap<String,String>();fields.put("action",i<500?"INVALIDATE":"RELEASE_DATASET");fields.put("reason","Explicit synthetic load fixture");fields.put("schema","1");fields.put("before_visible",Long.toString(score));fields.put("after_visible",Long.toString(score));
            records.add(new Record(id,Kind.AUDIT,"global","CONSOLE",HOT,vote.operationId(),"APPLIED",1,TIME.plusSeconds(count+i),fields,List.of(vote.operationId())));
            if(i<500&&count>players){records.add(new Record(vote.operationId(),Kind.MODERATION,"global","CONSOLE",HOT,vote.operationId(),"INVALIDATED",1,TIME.plusSeconds(count+i),Map.of("operation",id.toString(),"original_status","SUPERSEDED","original_active","false"),List.of(vote.operationId())));}
            if(i<1000)records.add(new Record(new UUID(803,i+1),Kind.FLAG,"global","SYSTEM",HOT,vote.operationId(),i%2==0?"SUSPICION":"FALSE_POSITIVE",1,TIME.plusSeconds(count+i),Map.of("source","SYNTHETIC_LOAD","severity","LOW","evidence","Synthetic correlation only"),List.of(vote.operationId())));
        }
        return new StorageSnapshot(profiles,List.of(),votes,ProtectionSnapshot.empty(),TierSnapshot.empty(),RecoverySnapshotData.empty(),AdministrationSnapshotData.empty(),new InvestigationSnapshotData(records));
    }
}
