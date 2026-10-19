package org.dextur.reputation.domain.protection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import static org.dextur.reputation.domain.protection.Fixed.*;

/** Atomic change planner; all private factors and the exact accumulator stay behind the storage port. */
public final class ProtectionEngine {
    private ProtectionEngine() { }
    public static Change vote(String scope,VoteRequest request,VoterFacts facts,VotingRules voting,ProtectionRules rules,
            Instant now,VotingEngine.Context eligibility,View view) {
        if(view.trust()!=null&&now.isBefore(view.trust().restrictedUntil()))
            throw new VoteRejectedException(VoteRejectedException.Code.TEMPORARILY_RESTRICTED,Math.max(1,Duration.between(now,view.trust().restrictedUntil()).toSeconds()));
        var nominal=VotingEngine.evaluate(scope,request,facts,voting,now,eligibility);
        boolean revoke=request.action()==VoteRequest.Action.REVOKE;
        var pair=view.pair();
        if(revoke&&pair!=null&&pair.activeVote().isEmpty()&&(view.latest()==null||view.latest().status()!=Status.PENDING)) throw new VoteRejectedException(VoteRejectedException.Code.NO_ACTIVE_VOTE);
        var trust=TrustModel.calculate(request.voterId(),scope,facts,rules,now,view.trust(),view.scope().revision()+1,view.recent(),!revoke);
        var analysis=AbuseAnalysis.evaluate(request,trust,rules,view,now,eligibility.sequence());
        long base=rules.weight().enabled()?(nominal.entry().type()==VoteType.POSITIVE?rules.weight().positive():rules.weight().negative()):units(nominal.entry().baseWeight());
        long trustMultiplier=rules.weight().trust()&&rules.trust().enabled()?interpolate(rules.weight().trustMin(),rules.weight().trustMax(),trust.score()):SCALE;
        long raw=rules.weight().enabled()?clamp(product(base,trustMultiplier,analysis.repeated(),analysis.reciprocal(),analysis.diversity(),analysis.maturity(),analysis.cluster()),rules.weight().min(),rules.weight().max()):base;
        long ceiling=pair==null?raw:Math.min(pair.ceilingUnits(),raw);
        if(revoke) ceiling=pair==null?units(nominal.entry().baseWeight()):pair.ceilingUnits();
        long weight=revoke?0:ceiling;
        Status status=revoke?Status.REVOKED:analysis.pending()?Status.PENDING:analysis.signals().isEmpty()?Status.ACCEPTED:Status.SUSPICIOUS;
        long old=pair==null?(eligibility.lastPair()==null||eligibility.lastPair().action()==VoteEntry.Action.REVOKE?0:units(eligibility.lastPair().contribution())):pair.contributionUnits();
        String oldActive=pair==null?(eligibility.lastPair()==null||eligibility.lastPair().action()==VoteEntry.Action.REVOKE?"":eligibility.lastPair().operationId().toString()):pair.activeVote();
        long contribution=status==Status.PENDING?old:revoke?0:Math.multiplyExact(nominal.entry().type().sign(),weight);
        String active=status==Status.PENDING?oldActive:revoke?"":request.operationId().toString();
        long before=view.score()==null?units(eligibility.target().profile().communityScore()):view.score().currentUnits();
        long after=Math.addExact(Math.subtractExact(before,old),contribution);
        long baseline=view.score()==null?before:view.score().baselineUnits();
        var oldProfile=eligibility.target().profile();
        var profile=profile(oldProfile,after,now);
        var e=nominal.entry();
        var entry=new VoteEntry(e.operationId(),e.sequence(),scope,e.voterId(),e.targetId(),e.action(),e.type(),e.reason(),e.comment(),now,e.previousOperationId(),
                Math.max(1,display(base)),status==Status.PENDING||revoke?0:display(weight),2,e.requestHash(),oldProfile.communityScore(),profile.communityScore());
        var factors=new TreeMap<>(analysis.evidence());factors.put("base",base);factors.put("trust_multiplier",trustMultiplier);factors.put("trust_score",trust.score());
        factors.put("maturity",trust.explanation().get("maturity"));factors.put("repeat_multiplier",analysis.repeated());factors.put("reciprocity_multiplier",analysis.reciprocal());
        factors.put("diversity_multiplier",analysis.diversity());factors.put("maturity_multiplier",analysis.maturity());factors.put("cluster_multiplier",analysis.cluster());
        factors.put("unclamped_ceiling",raw);factors.put("weight_min",rules.weight().min());factors.put("weight_max",rules.weight().max());
        factors.put("weight_enabled",rules.weight().enabled()?1L:0L);factors.put("ceiling_before",revoke?ceiling:pair==null?raw:pair.ceilingUnits());factors.put("revoke",revoke?1L:0L);
        var assessment=new Assessment(entry.operationId(),entry.sequence(),scope,entry.voterId(),entry.targetId(),status,base,weight,
                status==Status.PENDING||revoke?0:contribution,ceiling,2,now,view.scope().revision(),1,factors,analysis.signals(),analysis.severity(),policyHash(rules));
        var updates=new LinkedHashMap<UUID,Assessment>();
        if(view.latest()!=null&&view.latest().status()==Status.PENDING) updates.put(view.latest().voteId(),view.latest().status(Status.SUPERSEDED));
        if(status!=Status.PENDING&&view.active()!=null) updates.put(view.active().voteId(),view.active().status(Status.SUPERSEDED));
        updates.put(assessment.voteId(),assessment);
        var nextPair=new Pair(scope,request.voterId(),request.targetId(),request.operationId(),active,contribution,ceiling,pair==null?1:pair.revision()+1);
        var score=new Score(request.targetId(),scope,baseline,after,view.score()==null?1:view.score().revision()+1);
        var audit=new Audit(request.operationId(),view.scope().nextAudit(),scope,request.operationId(),request.voterId(),request.targetId(),"VOTE",status,
                before,after,old,contribution,oldActive,active,now,request.voterId().toString(),"Versioned vote evaluation",view.scope().revision(),factors,analysis.memo().clustered());
        var memo=analysis.memo();var nextScope=new ScopeState(scope,view.scope().revision()+1,view.scope().nextAudit()+1,memo.graphAt(),memo.graphRevision(),memo.clusters(),memo.graphComplete(),memo.graphWork());
        return new Change(entry,profile,trust,nextPair,score,List.copyOf(updates.values()),audit,nextScope);
    }
    public static Change review(UUID id,UUID operator,String reason,Review action,long expectedRevision,VoteEntry entry,
            ReputationSnapshot oldProfile,ProtectionRules rules,View view,Instant now) {
        Objects.requireNonNull(operator);if(reason==null||reason.isBlank()||reason.length()>1000) throw new IllegalArgumentException("Audit reason required");
        var evaluation=view.latest();var pair=view.pair();
        if(evaluation==null||evaluation.revision()!=expectedRevision||pair==null||!pair.latestVote().equals(entry.operationId())) throw new IllegalStateException("Stale review revision");
        if(action==Review.ACCEPT&&evaluation.status()!=Status.PENDING) throw new IllegalStateException("Only pending votes can be accepted");
        if(action!=Review.ACCEPT&&evaluation.status()!=Status.PENDING&&evaluation.status()!=Status.ACCEPTED&&evaluation.status()!=Status.SUSPICIOUS) throw new IllegalStateException("Vote cannot be invalidated twice");
        long old=pair.contributionUnits(),added=old;String active=pair.activeVote();
        Status status=action==Review.ACCEPT?Status.ACCEPTED:Status.INVALIDATED;
        if(action==Review.ACCEPT) { added=Math.multiplyExact(entry.type().sign(),evaluation.weightUnits());active=entry.operationId().toString(); }
        else if(pair.activeVote().equals(entry.operationId().toString())) { added=0;active=""; }
        long before=view.score().currentUnits(),after=Math.addExact(Math.subtractExact(before,old),added);
        var next=new Assessment(evaluation.voteId(),evaluation.voteSequence(),evaluation.scope(),evaluation.voter(),evaluation.target(),status,
                evaluation.baseUnits(),evaluation.weightUnits(),status==Status.ACCEPTED?added:0,evaluation.ceilingUnits(),evaluation.calculationVersion(),evaluation.evaluatedAt(),
                evaluation.inputRevision(),evaluation.revision()+1,evaluation.factors(),evaluation.signals(),evaluation.severity(),evaluation.policyHash());
        var assessments=new LinkedHashMap<UUID,Assessment>();if(action==Review.ACCEPT&&view.active()!=null&&!view.active().voteId().equals(next.voteId())) assessments.put(view.active().voteId(),view.active().status(Status.SUPERSEDED));assessments.put(next.voteId(),next);
        var trust=view.trust();
        if(trust!=null&&action!=Review.ACCEPT) {
            long invalidated=Math.min(trust.submitted(),trust.invalidated()+1),confirmed=trust.confirmed()+(action==Review.CONFIRM_MANIPULATION?1:0);
            var updated=new Trust(trust.player(),trust.scope(),trust.score(),trust.modelVersion(),trust.calculatedAt(),trust.revision(),trust.inputRevision(),trust.playtimeSeconds(),trust.firstJoined(),trust.factsAt(),
                    trust.submitted(),invalidated,confirmed,action==Review.CONFIRM_MANIPULATION?now.plusSeconds(rules.trust().restrictionMinutes()*60L):trust.restrictedUntil(),trust.explanation());
            var facts=new VoterFacts(false,true,updated.playtimeSeconds(),updated.firstJoined(),true,true,true);
            trust=TrustModel.calculate(trust.player(),trust.scope(),facts,rules,now,updated,view.scope().revision()+1,view.recent(),false);
        }
        var scope=view.scope();var audit=new Audit(id,scope.nextAudit(),scope.scope(),entry.operationId(),entry.voterId(),entry.targetId(),action.name(),status,before,after,old,added,pair.activeVote(),active,now,operator.toString(),reason,scope.revision(),Map.of("expected_revision",expectedRevision),List.of());
        return new Change(null,profile(oldProfile,after,now),trust,new Pair(pair.scope(),pair.voter(),pair.target(),pair.latestVote(),active,added,pair.ceilingUnits(),pair.revision()+1),
                new Score(view.score().player(),view.score().scope(),view.score().baselineUnits(),after,view.score().revision()+1),List.copyOf(assessments.values()),audit,
                new ScopeState(scope.scope(),scope.revision()+1,scope.nextAudit()+1,scope.graphAt(),scope.graphRevision(),scope.clusters(),scope.graphComplete(),scope.graphWork()));
    }
    private static ReputationSnapshot profile(ReputationSnapshot old,long units,Instant now) {
        long community=display(units);return new ReputationSnapshot(old.playerId(),old.scopeId(),community,old.systemScore(),Math.addExact(community,old.systemScore()),old.tier(),Math.incrementExact(old.revision()),old.createdAt(),now.isBefore(old.updatedAt())?old.updatedAt():now);
    }
    private static String policyHash(ProtectionRules rules) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rules.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(Exception error) { throw new IllegalStateException(error); }
    }
}
