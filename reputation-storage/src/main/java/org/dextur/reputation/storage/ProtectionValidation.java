package org.dextur.reputation.storage;

import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import static org.dextur.reputation.domain.protection.Fixed.*;

final class ProtectionValidation {
    private ProtectionValidation() { }
    static void validate(List<ReputationSnapshot> profiles,List<VoteEntry> votes,ProtectionSnapshot state) {
        var events=new HashMap<UUID,VoteEntry>();for(var vote:votes) events.put(vote.operationId(),vote);
        var players=new HashMap<String,ReputationSnapshot>();for(var p:profiles) players.put(p.playerId()+"/"+p.scopeId(),p);
        var trustKeys=new HashSet<String>();for(var t:state.trusts()) trustKeys.add(t.key());
        var scoreKeys=new HashSet<String>();for(var s:state.scores()) scoreKeys.add(s.key());
        var assessments=new HashMap<UUID,Assessment>();for(var a:state.assessments()) {
            var v=events.get(a.voteId());
            if(v==null||v.calculationVersion()!=2||!v.voterId().equals(a.voter())||!v.targetId().equals(a.target())||!v.scope().equals(a.scope())||v.sequence()!=a.voteSequence()) fail();
            if(!trustKeys.contains(a.voter()+"/"+a.scope())||!scoreKeys.contains(a.target()+"/"+a.scope())) fail();
            assessments.put(a.voteId(),a);
        }
        for(var v:votes) if(v.calculationVersion()==2&&!assessments.containsKey(v.operationId())) fail();
        for(var t:state.trusts()) if(!players.containsKey(t.key())||org.dextur.reputation.domain.protection.TrustModel.reproduce(t)!=t.score()||t.inputRevision()>state.scopes().stream().filter(s->s.scope().equals(t.scope())).mapToLong(ScopeState::revision).findFirst().orElse(0)) fail();
        var latest=new HashMap<String,VoteEntry>();for(var v:votes) latest.put(v.pairKey(),v);
        for(var pair:state.pairs()) {
            var last=latest.get(pair.key());if(last==null||!last.operationId().equals(pair.latestVote())) fail();
            if(!pair.activeVote().isEmpty()) {
                var active=events.get(UUID.fromString(pair.activeVote()));
                if(active==null||!active.pairKey().equals(pair.key())||active.action()==VoteEntry.Action.REVOKE) fail();
                var a=assessments.get(active.operationId());
                long expected=a==null?units(active.contribution()):a.contributionUnits();
                if(expected!=pair.contributionUnits()||(a!=null&&a.status()!=Status.ACCEPTED&&a.status()!=Status.SUSPICIOUS)) fail();
            } else if(pair.contributionUnits()!=0) fail();
        }
        for(var score:state.scores()) {
            var p=players.get(score.key());if(p==null||p.communityScore()!=display(score.currentUnits())||p.visibleScore()!=Math.addExact(p.communityScore(),p.systemScore())) fail();
        }
        Set<UUID> voteAudits=new HashSet<>();
        var firstScores=new HashMap<String,Long>();var finalPairs=new HashMap<String,Audit>();var scopes=new HashSet<String>();
        for(var meta:state.scopes()) scopes.add(meta.scope());
        for(var audit:state.audit()) {
            var v=events.get(audit.voteId());if(v==null||!v.scope().equals(audit.scope())||!v.voterId().equals(audit.voter())||!v.targetId().equals(audit.target())) fail();
            if(!scopes.contains(audit.scope())||audit.inputRevision()!=audit.sequence()-1) fail();
            firstScores.putIfAbsent(audit.target()+"/"+audit.scope(),audit.beforeUnits());
            if(audit.kind().equals("VOTE")) {
                if(!audit.id().equals(v.operationId())||!voteAudits.add(v.operationId())||v.communityBefore()!=display(audit.beforeUnits())||v.communityAfter()!=display(audit.afterUnits())) fail();
                var a=assessments.get(v.operationId());if(a==null||!a.factors().equals(audit.evidence())||!a.evaluatedAt().equals(v.createdAt())) fail();
                var f=a.factors();
                long raw=f.get("weight_enabled")==1?clamp(product(f.get("base"),f.get("trust_multiplier"),f.get("repeat_multiplier"),f.get("reciprocity_multiplier"),f.get("diversity_multiplier"),f.get("maturity_multiplier"),f.get("cluster_multiplier")),f.get("weight_min"),f.get("weight_max")):f.get("base");
                long ceiling=f.get("revoke")==1?f.get("ceiling_before"):Math.min(raw,f.get("ceiling_before"));
                if(raw!=f.get("unclamped_ceiling")||ceiling!=a.ceilingUnits()||a.weightUnits()!=(v.action()==VoteEntry.Action.REVOKE?0:ceiling)||a.baseUnits()!=f.get("base")) fail();
                if(v.baseWeight()!=Math.max(1,display(a.baseUnits()))||v.effectiveWeight()!=((audit.status()==Status.PENDING||audit.status()==Status.REVOKED)?0:display(a.weightUnits()))||a.inputRevision()!=audit.inputRevision()) fail();
            }
            if(audit.kind().equals("ANALYSIS")) { if(audit.beforeUnits()!=audit.afterUnits()||audit.removedUnits()!=0||audit.addedUnits()!=0) fail();continue; }
            var previous=finalPairs.put(v.pairKey(),audit);
            if(previous!=null&&(!previous.newActive().equals(audit.oldActive())||previous.addedUnits()!=audit.removedUnits())) fail();
            if(audit.newActive().isEmpty()) { if(audit.addedUnits()!=0) fail(); }
            else {
                var active=events.get(UUID.fromString(audit.newActive()));if(active==null||!active.pairKey().equals(v.pairKey())||active.action()==VoteEntry.Action.REVOKE) fail();
                var a=assessments.get(active.operationId());long contribution=a==null?units(active.contribution()):Math.multiplyExact(active.type().sign(),a.weightUnits());
                if(contribution!=audit.addedUnits()) fail();
            }
        }
        if(!voteAudits.equals(assessments.keySet())) fail();
        for(var score:state.scores()) if(!Objects.equals(firstScores.get(score.key()),score.baselineUnits())) fail();
        for(var pair:state.pairs()) { var last=finalPairs.remove(pair.key());if(last==null||!last.newActive().equals(pair.activeVote())||last.addedUnits()!=pair.contributionUnits()) fail(); }
        if(!finalPairs.isEmpty()) fail();
    }
    static Map<String,VoteEntry> active(List<VoteEntry> votes,ProtectionSnapshot state) {
        var byId=new HashMap<UUID,VoteEntry>();var active=new HashMap<String,VoteEntry>();
        for(var v:votes) { byId.put(v.operationId(),v);if(v.action()==VoteEntry.Action.REVOKE) active.remove(v.pairKey());else active.put(v.pairKey(),v); }
        for(var pair:state.pairs()) { active.remove(pair.key());if(!pair.activeVote().isEmpty()) active.put(pair.key(),byId.get(UUID.fromString(pair.activeVote()))); }
        return active;
    }
    private static void fail() { throw new IllegalArgumentException("Private protection/profile/journal mismatch"); }
}
