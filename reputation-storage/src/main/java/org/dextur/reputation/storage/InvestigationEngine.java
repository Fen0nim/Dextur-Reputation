package org.dextur.reputation.storage;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.VoterFacts;
import org.dextur.reputation.domain.investigation.InvestigationData;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.investigation.InvestigationData.Record;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import static org.dextur.reputation.domain.protection.Fixed.*;

/** Shared moderation semantics; SQL and JSON supply transaction-local indexed access. */
final class InvestigationEngine {
    private final InvestigationSession s;
    InvestigationEngine(InvestigationSession session){s=session;}
    static UUID flagId(UUID vote,String source){return UUID.nameUUIDFromBytes((vote+"/flag/"+source).getBytes(StandardCharsets.UTF_8));}
    static String hash(Object value){return SqlTiers.digest(ProtectionCodec.encode(value));}
    private static String requestHash(Request r){return hash(List.of(r.scope(),r.action(),r.subject(),r.actor(),r.reason(),r.expectedRevision(),r.resolution()));}
    private static IllegalStateException error(String code){return new IllegalStateException("INV_"+code);}
    private void allowed(String actor,String reason){if(actor.equals("CONSOLE")&&!s.rules.consoleAllowed())throw error("CONSOLE");if(s.rules.requireReason()&&reason.isBlank())throw error("REASON");}
    private ReputationSnapshot required(UUID id,String scope)throws Exception{var p=s.profile(id,scope);if(p==null)throw error("UNKNOWN");return p;}
    private static Instant at(Instant requested,ReputationSnapshot p){return requested.isBefore(p.updatedAt())?p.updatedAt():requested;}
    Mutation mutate(Request r)throws Exception {
        allowed(r.actor(),r.reason());var duplicate=s.record(Kind.AUDIT,r.operation());
        if(duplicate!=null){if(!duplicate.scope().equals(r.scope())||!requestHash(r).equals(duplicate.values().get("request_hash")))throw error("CONFLICT");return new Mutation(r.operation(),duplicate.target(),required(duplicate.target(),r.scope()),duplicate.status(),true,duplicate.related());}
        if(s.vote(r.operation())!=null)throw error("CONFLICT");for(String source:List.of("legacy-admin","legacy-recovery","legacy-protection"))if(s.record(Kind.AUDIT,InvestigationLegacy.id(source,r.operation()))!=null)throw error("CONFLICT");
        if(s.recordCount()>=s.rules.maximumRecords()-3)throw error("CAPACITY");
        VoteEntry vote=InvestigationData.voteAction(r.action())?s.vote(r.subject()):null;
        if(InvestigationData.voteAction(r.action())&&(vote==null||!vote.scope().equals(r.scope())))throw error("UNKNOWN");
        Record flag=r.action()==Action.RESOLVE_FLAG?s.record(Kind.FLAG,r.subject()):null;
        if(r.action()==Action.RESOLVE_FLAG&&flag==null)throw error("UNKNOWN_FLAG");
        UUID target=vote!=null?vote.targetId():flag!=null?flag.target():r.subject();
        var old=required(target,r.scope());String beforeStatus=vote==null?flag==null?"":flag.status():s.status(vote);var oldPrecision=s.score(target,r.scope());long beforeUnits=oldPrecision==null?units(old.communityScore()):oldPrecision.currentUnits();var before=values(old,s.trust(target,r.scope()));
        String result="APPLIED";List<UUID> related=vote==null?List.of():List.of(vote.operationId());
        Record marker=null;
        if(vote!=null){
            var prior=s.record(Kind.MODERATION,vote.operationId());var assessment=s.assessment(vote.operationId());
            long revision=moderationRevision(assessment,prior);
            if(r.expectedRevision()>=0&&r.expectedRevision()!=revision)throw error("STALE");
            String previous=s.status(vote);boolean restoring=r.action()==Action.RESTORE;
            if(restoring&&(prior==null||!previous.equals("INVALIDATED")))throw error("RESTORE");
            if((r.action()==Action.APPROVE||r.action()==Action.REJECT)&&!previous.equals("PENDING"))throw error("PENDING");
            if(previous.equals("INVALIDATED")&&!restoring)result="NO_CHANGE";
            else {
                String original=restoring?prior.values().get("original_status"):previous;
                var activeBefore=s.active(vote);
                var change=voteChange(r,vote,old,original,restoring);
                s.change(change);
                var fields=new TreeMap<String,String>();fields.put("operation",r.operation().toString());fields.put("original_status",original);fields.put("original_active",Boolean.toString(activeBefore!=null&&activeBefore.operationId().equals(vote.operationId())));
                String status=restoring?original.equals("PENDING")?"PENDING":change.assessments().stream().filter(a->a.voteId().equals(vote.operationId())).map(a->a.status().name()).findFirst().orElse("ACCEPTED"):r.action()==Action.APPROVE?"ACCEPTED":"INVALIDATED";
                marker=new Record(vote.operationId(),Kind.MODERATION,r.scope(),r.actor(),target,vote.operationId(),status,prior==null?1:prior.revision()+1,at(r.at(),old),fields,List.of(vote.operationId()));
            }
        }else if(flag!=null){
            if(!flag.scope().equals(r.scope())||r.expectedRevision()>=0&&r.expectedRevision()!=flag.revision())throw error("STALE");
            var fields=new TreeMap<>(flag.values());fields.put("operation",r.operation().toString());fields.put("reason",r.reason());
            marker=new Record(flag.id(),Kind.FLAG,r.scope(),r.actor(),target,flag.vote(),r.resolution(),flag.revision()+1,at(r.at(),old),fields,flag.related());related=flag.related();
        }else{
            long expectedCurrent=r.action()==Action.RECALCULATE_TRUST?s.trust(target,r.scope())==null?0:s.trust(target,r.scope()).revision():old.revision();if(r.expectedRevision()>=0&&r.expectedRevision()!=expectedCurrent)throw error("STALE");
            if(r.action()==Action.RECALCULATE_REPUTATION||r.action()==Action.RECALCULATE_ALL)reputation(old,r.at());
            if(r.action()==Action.RECALCULATE_TRUST||r.action()==Action.RECALCULATE_ALL)recalculateTrust(target,r.scope(),r.at());
            // All recalculations reconcile configured tier/hysteresis and desired LP state, in this same transaction.
            s.putProfile(required(target,r.scope()));
            if(r.action()==Action.RECALCULATE_TIER||r.action()==Action.RECALCULATE_ALL){var tier=s.tier(target,r.scope());if(tier!=null)s.putTier(new org.dextur.reputation.domain.tier.TierState(tier.player(),tier.scope(),tier.tier(),tier.policyHash(),tier.profileRevision(),tier.revision(),tier.history(),tier.desiredGroup(),tier.syncRevision()+1,tier.appliedRevision(),tier.ownedGroups()));}
        }
        var profile=required(target,r.scope());var fields=new TreeMap<String,String>();fields.put("action",r.action().name());fields.put("reason",r.reason());fields.put("request_hash",requestHash(r));fields.put("schema","1");fields.put("subject",r.subject().toString());fields.put("expected_revision",Long.toString(r.expectedRevision()));fields.put("resolution",r.resolution());if(vote!=null||flag!=null){fields.put("before_status",beforeStatus);fields.put("after_status",marker==null?beforeStatus:marker.status());}if(vote!=null){var precision=s.score(target,r.scope());fields.put("before_community_units",Long.toString(beforeUnits));fields.put("after_community_units",Long.toString(precision==null?units(profile.communityScore()):precision.currentUnits()));}
        before.forEach((k,v)->fields.put("before_"+k,v));values(profile,s.trust(target,r.scope())).forEach((k,v)->fields.put("after_"+k,v));
        s.put(new Record(r.operation(),Kind.AUDIT,r.scope(),r.actor(),target,vote==null?InvestigationData.NONE:vote.operationId(),result,1,at(r.at(),profile),fields,related));
        if(marker!=null)s.put(marker);
        return new Mutation(r.operation(),target,profile,result,false,related);
    }
    static long moderationRevision(Assessment a,Record r){return Math.addExact(a==null?0:a.revision(),Math.multiplyExact(r==null?0:r.revision(),1000000));}
    private Change voteChange(Request r,VoteEntry v,ReputationSnapshot p,String original,boolean restoring)throws Exception {
        var view=s.context(v,r.at());var latest=s.latest(v);var active=s.active(v);var a=s.assessment(v.operationId());
        if(latest==null)throw error("UNKNOWN");
        if(restoring&&(!latest.operationId().equals(v.operationId())||Set.of("SUPERSEDED","REVOKED").contains(original)||v.action()==VoteEntry.Action.REVOKE))throw error("RESTORE");
        if(r.action()==Action.APPROVE&&!latest.operationId().equals(v.operationId()))throw error("STALE");
        String oldActive=active==null?"":active.operationId().toString();long removed=active==null?0:s.contribution(active),added=removed;String newActive=oldActive;
        boolean pending=original.equals("PENDING");
        Status status=restoring?(pending?Status.PENDING:original.equals("SUSPICIOUS")?Status.SUSPICIOUS:Status.ACCEPTED):r.action()==Action.APPROVE?Status.ACCEPTED:Status.INVALIDATED;
        if(restoring&&!pending||r.action()==Action.APPROVE){
            if(restoring&&active!=null&&!active.operationId().equals(v.operationId()))throw error("RESTORE");
            added=s.contribution(v);newActive=v.operationId().toString();
        }else if(!restoring&&oldActive.equals(v.operationId().toString())){added=0;newActive="";}
        long before=view.score()==null?units(p.communityScore()):view.score().currentUnits();long after=Math.addExact(Math.subtractExact(before,removed),added);
        var updates=new ArrayList<Assessment>();
        if(a!=null)updates.add(new Assessment(a.voteId(),a.voteSequence(),a.scope(),a.voter(),a.target(),status,a.baseUnits(),a.weightUnits(),status==Status.ACCEPTED||status==Status.SUSPICIOUS?Math.multiplyExact(v.type().sign(),a.weightUnits()):0,a.ceilingUnits(),a.calculationVersion(),a.evaluatedAt(),a.inputRevision(),a.revision()+1,a.factors(),a.signals(),a.severity(),a.policyHash()));
        if(r.action()==Action.APPROVE&&view.active()!=null&&!view.active().voteId().equals(v.operationId()))updates.add(view.active().status(Status.SUPERSEDED));
        var meta=view.scope();var pair=view.pair();var now=at(r.at(),p);
        var audit=new Audit(r.operation(),meta.nextAudit(),r.scope(),v.operationId(),v.voterId(),v.targetId(),restoring?"RESTORE":r.action()==Action.APPROVE?"ACCEPT":"INVALIDATE",status,before,after,removed,added,oldActive,newActive,now,r.actor(),r.reason(),meta.revision(),Map.of("expected_revision",r.expectedRevision(),"moderation_model",1L),List.of());
        var profile=new ReputationSnapshot(p.playerId(),p.scopeId(),display(after),p.systemScore(),Math.addExact(display(after),p.systemScore()),p.tier(),p.revision()+1,p.createdAt(),now);
        return new Change(null,profile,null,new Pair(v.scope(),v.voterId(),v.targetId(),latest.operationId(),newActive,added,pair==null?units(Math.max(1,v.baseWeight())):pair.ceilingUnits(),pair==null?1:pair.revision()+1),new Score(p.playerId(),p.scopeId(),view.score()==null?before:view.score().baselineUnits(),after,view.score()==null?1:view.score().revision()+1),updates,audit,new ScopeState(meta.scope(),meta.revision()+1,meta.nextAudit()+1,meta.graphAt().equals(Instant.EPOCH)?now:meta.graphAt(),meta.graphRevision(),meta.clusters(),meta.graphComplete(),meta.graphWork()));
    }
    long expectedCommunityUnits(ReputationSnapshot profile)throws Exception {
        var records=s.protectionAudit(profile.scopeId(),profile.playerId(),s.rules.recalculationEvents()+1,0);
        if(records.size()>s.rules.recalculationEvents())throw error("BUDGET");
        long expected=units(profile.communityScore());
        if(!records.isEmpty()){
            expected=records.getFirst().beforeUnits();
            for(var record:records)expected=Math.addExact(Math.subtractExact(expected,record.removedUnits()),record.addedUnits());
        }else{
            var votes=s.select(profile.scopeId(),new Filter(null,profile.playerId(),null,"",null,null,"","",null),s.rules.recalculationEvents()+1,0);
            if(votes.size()>s.rules.recalculationEvents())throw error("BUDGET");
            // Phase 1 profiles may have an imported baseline without vote history. Preserve that baseline.
            if(!votes.isEmpty()){long baseline=units(votes.getLast().communityBefore());var pairs=new HashMap<String,VoteEntry>();for(var v:votes.reversed())pairs.put(v.pairKey(),v);expected=baseline;for(var v:pairs.values())expected=Math.addExact(expected,s.contribution(v));}
        }
        return expected;
    }
    private void reputation(ReputationSnapshot profile,Instant now)throws Exception {
        long community=display(expectedCommunityUnits(profile));if(community!=profile.communityScore()||profile.visibleScore()!=Math.addExact(community,profile.systemScore()))s.putProfile(new ReputationSnapshot(profile.playerId(),profile.scopeId(),community,profile.systemScore(),Math.addExact(community,profile.systemScore()),profile.tier(),profile.revision()+1,profile.createdAt(),at(now,profile)));
    }
    private void recalculateTrust(UUID player,String scope,Instant now)throws Exception {
        var previous=s.trust(player,scope);if(previous!=null&&previous.modelVersion()!=1)throw error("MODEL");
        var p=required(player,scope);var stamp=at(now,p);if(previous!=null&&stamp.isBefore(previous.calculatedAt()))stamp=previous.calculatedAt();
        var fake=new VoteEntry(InvestigationData.NONE,1,scope,player,player.equals(InvestigationData.NONE)?new UUID(0,1):InvestigationData.NONE,VoteEntry.Action.CAST,VoteType.POSITIVE,"diagnostic","",stamp,null,1,1,1,"0".repeat(64),0,1);
        var view=s.context(fake,stamp);var facts=previous==null?new VoterFacts(false,true,0,p.createdAt(),true,true,true):new VoterFacts(false,true,previous.playtimeSeconds(),previous.firstJoined(),true,true,true);
        if(previous!=null){long invalid=s.count(scope,new Filter(player,null,null,"INVALIDATED",null,null,"","",null));previous=new Trust(previous.player(),scope,previous.score(),1,previous.calculatedAt(),previous.revision(),previous.inputRevision(),previous.playtimeSeconds(),previous.firstJoined(),previous.factsAt(),previous.submitted(),Math.min(previous.submitted(),invalid),previous.confirmed(),previous.restrictedUntil(),previous.explanation());}
        var next=TrustModel.calculate(player,scope,facts,s.protection,stamp,previous,view.scope().revision(),view.recent(),false);s.putTrust(next);
    }
    static Map<String,String> values(ReputationSnapshot p,Trust t){var m=new TreeMap<String,String>();m.put("visible",Long.toString(p.visibleScore()));m.put("community",Long.toString(p.communityScore()));m.put("system",Long.toString(p.systemScore()));m.put("tier",p.tier());m.put("revision",Long.toString(p.revision()));m.put("trust",t==null?"—":decimal(t.score()));m.put("trust_revision",t==null?"0":Long.toString(t.revision()));return m;}
    static String decimal(long units){return java.math.BigDecimal.valueOf(units,6).stripTrailingZeros().toPlainString();}
}
