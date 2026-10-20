package org.dextur.reputation.storage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.investigation.InvestigationData;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;

/** Bounded stable selection; each cursor advance and its mutations share one commit. */
final class InvestigationBulk {
    private final InvestigationSession s;
    InvestigationBulk(InvestigationSession s){this.s=s;}
    Plan plan(Action action,String scope,Filter filter,String actor,String reason,Instant now)throws Exception {
        if(actor.equals("CONSOLE")&&!s.rules.consoleAllowed())throw new IllegalStateException("INV_CONSOLE");
        if(!filter.scoped()||reason.isBlank())throw new IllegalArgumentException("INV_SCOPE");
        if(!Set.of(Action.INVALIDATE,Action.RESTORE,Action.RECALCULATE_ALL).contains(action))throw new IllegalArgumentException("INV_SCOPE");
        if(filter.flag()!=null){var flag=s.record(Kind.FLAG,filter.flag());if(flag==null||!flag.scope().equals(scope))throw new IllegalArgumentException("INV_UNKNOWN_FLAG");}
        var selected=s.select(scope,filter,s.rules.maximumBulk()+1,0);if(selected.size()>s.rules.maximumBulk())throw new IllegalStateException("INV_BULK_LIMIT");
        var subjects=new ArrayList<UUID>();var players=new TreeSet<UUID>();var exactDeltas=new TreeMap<UUID,Long>();var revisions=new ArrayList<String>();int pending=0;
        for(var v:selected){String status=s.status(v);var latest=s.latest(v);var active=s.active(v);var marker=s.record(Kind.MODERATION,v.operationId());
            if(action==Action.INVALIDATE&&status.equals("INVALIDATED"))continue;
            if(action==Action.RESTORE&&(!status.equals("INVALIDATED")||marker==null||!latest.operationId().equals(v.operationId())||Set.of("SUPERSEDED","REVOKED").contains(marker.values().get("original_status"))||v.action()==VoteEntry.Action.REVOKE||active!=null&&!active.operationId().equals(v.operationId())&&!marker.values().get("original_status").equals("PENDING")))continue;
            if(action!=Action.RECALCULATE_ALL)subjects.add(v.operationId());players.add(v.targetId());
            var p=s.profile(v.targetId(),scope);var a=s.assessment(v.operationId());revisions.add(v.operationId()+"/"+InvestigationEngine.moderationRevision(a,marker)+"/"+latest.operationId()+"/"+(active==null?"":active.operationId())+"/"+p.revision());
            long delta=action==Action.INVALIDATE&&active!=null&&active.operationId().equals(v.operationId())?Math.negateExact(s.contribution(v)):action==Action.RESTORE&&!marker.values().get("original_status").equals("PENDING")?s.contribution(v):0;
            exactDeltas.merge(v.targetId(),delta,Math::addExact);if(status.equals("PENDING")||action==Action.RESTORE&&marker.values().get("original_status").equals("PENDING"))pending++;
        }
        if(action==Action.RECALCULATE_ALL){if(filter.target()!=null)players.add(filter.target());subjects.addAll(players);}
        var deltas=new TreeMap<String,Long>();var tiers=new ArrayList<String>();
        for(UUID player:players){var p=s.profile(player,scope);if(p==null)throw new IllegalArgumentException("INV_UNKNOWN");var fake=InvestigationReports.contextVote(player,scope,now);long before=org.dextur.reputation.domain.protection.Fixed.units(p.communityScore());var precision=s.score(player,scope);if(precision!=null)before=precision.currentUnits();long community=org.dextur.reputation.domain.protection.Fixed.display(action==Action.RECALCULATE_ALL?new InvestigationEngine(s).expectedCommunityUnits(p):Math.addExact(before,exactDeltas.getOrDefault(player,0L)));long delta=Math.subtractExact(community,p.communityScore());deltas.put(player.toString(),delta);var after=new ReputationSnapshot(player,scope,community,p.systemScore(),Math.addExact(community,p.systemScore()),p.tier(),p.revision(),p.createdAt(),p.updatedAt());tiers.add(player+":"+p.tier()+"->"+TierSnapshot.reconcile(after,s.tier(player,scope),s.tiers).tier());var trust=s.trust(player,scope);revisions.add(player+"/"+p.revision()+"/"+(trust==null?0:trust.revision()));}
        if(filter.flag()!=null){var flag=s.record(Kind.FLAG,filter.flag());revisions.add(flag==null?"missing flag":flag.id()+"/"+flag.revision()+"/"+flag.status());}
        String fingerprint=InvestigationEngine.hash(List.of(action,scope,filter,actor,reason,subjects,revisions,s.rules));
        return new Plan(action,scope,filter,actor,reason,subjects,deltas,List.copyOf(players),tiers,pending,fingerprint);
    }
    Record begin(UUID operation,Plan plan,Instant now)throws Exception {
        var previous=s.record(Kind.BULK,operation);String encoded=ProtectionCodec.encode(plan);
        if(previous!=null){if(!previous.values().get("plan").equals(encoded))throw new IllegalStateException("INV_CONFLICT");return previous;}
        if(s.record(Kind.AUDIT,operation)!=null||s.vote(operation)!=null)throw new IllegalStateException("INV_CONFLICT");
        if(!plan.fingerprint().equals(plan(plan.action(),plan.scope(),plan.filter(),plan.actor(),plan.reason(),now).fingerprint()))throw new IllegalStateException("INV_STALE");
        if(plan.subjects().size()>s.rules.maximumBulk()||s.recordCount()+plan.subjects().size()*3+4>s.rules.maximumRecords())throw new IllegalStateException("INV_CAPACITY");
        var record=new Record(operation,Kind.BULK,plan.scope(),plan.actor(),InvestigationData.NONE,InvestigationData.NONE,plan.subjects().isEmpty()?"COMPLETED":"RUNNING",1,now,Map.of("plan",encoded,"cursor","0","reason",plan.reason(),"action",plan.action().name(),"error",""),plan.subjects());s.put(record);audit(record,"BULK_STARTED",now);return record;
    }
    Batch advance(UUID operation,String actor,boolean cancel,Instant now)throws Exception {
        var record=s.record(Kind.BULK,operation);if(record==null)throw new IllegalArgumentException("INV_UNKNOWN");if(!record.actor().equals(actor))throw new IllegalStateException("INV_ACTOR");
        if(Set.of("COMPLETED","CANCELLED").contains(record.status()))return new Batch(record,List.of());
        if(cancel){var cancelled=update(record,"CANCELLED",Integer.parseInt(record.values().get("cursor")),now,"");s.put(cancelled);audit(cancelled,"BULK_CANCELLED",now);return new Batch(cancelled,List.of());}
        var plan=ProtectionCodec.decode(record.values().get("plan"),Plan.class);int cursor=Integer.parseInt(record.values().get("cursor")),end=Math.min(record.related().size(),cursor+s.rules.batchSize());var mutations=new ArrayList<Mutation>();
        for(int i=cursor;i<end;i++){
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("INV_CANCELLED");
            UUID id=UUID.nameUUIDFromBytes((operation+"/"+i).getBytes(StandardCharsets.UTF_8));
            mutations.add(new InvestigationEngine(s).mutate(new Request(id,plan.scope(),plan.action(),record.related().get(i),actor,plan.reason(),-1,"",now)));
        }
        var next=update(record,end==record.related().size()?"COMPLETED":"RUNNING",end,now,"");s.put(next);if(next.status().equals("COMPLETED"))audit(next,"BULK_COMPLETED",now);return new Batch(next,mutations);
    }
    static Record update(Record old,String status,int cursor,Instant now,String error){var values=new TreeMap<>(old.values());values.put("cursor",Integer.toString(cursor));values.put("error",error);return new Record(old.id(),old.kind(),old.scope(),old.actor(),old.target(),old.vote(),status,old.revision()+1,now,values,old.related());}
    private void audit(Record r,String action,Instant now)throws Exception{UUID id=UUID.nameUUIDFromBytes((r.id()+"/"+action).getBytes(StandardCharsets.UTF_8));if(s.record(Kind.AUDIT,id)==null)s.put(new Record(id,Kind.AUDIT,r.scope(),r.actor(),InvestigationData.NONE,InvestigationData.NONE,"APPLIED",1,now,Map.of("action",action,"operation",r.id().toString(),"reason",r.values().get("reason"),"cursor",r.values().get("cursor"),"schema","1"),r.related()));}
}
