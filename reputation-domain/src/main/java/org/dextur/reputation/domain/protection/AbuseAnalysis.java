package org.dextur.reputation.domain.protection;

import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import static org.dextur.reputation.domain.protection.Fixed.*;

/** Bounded, deterministic relationship evidence. Density alone never confirms manipulation. */
public final class AbuseAnalysis {
    public record Result(long repeated,long reciprocal,long diversity,long maturity,long cluster,Severity severity,
            boolean pending,List<String> signals,Map<String,Long> evidence,ScopeState memo) { }
    private AbuseAnalysis() { }
    public static Result evaluate(VoteRequest request,Trust trust,ProtectionRules rules,View view,Instant now,long candidateSequence) {
        var signals=new ArrayList<String>();var evidence=new TreeMap<String,Long>();
        long repeats=1,reciprocals=1;boolean reverse=false;
        Set<UUID> incoming=new HashSet<>(),weakNegative=new HashSet<>(),negative=new HashSet<>(),weakGrowth=new HashSet<>(),growth=new HashSet<>();
        long immature=rules.burst().immatureThreshold();
        for(var s:view.recent()) {
            var v=s.vote();if(v.action()==VoteEntry.Action.REVOKE) continue;
            if(v.voterId().equals(request.voterId())&&v.targetId().equals(request.targetId())&&!v.createdAt().isBefore(now.minusSeconds(rules.repeated().days()*86400L))) repeats++;
            boolean forward=v.voterId().equals(request.voterId())&&v.targetId().equals(request.targetId());
            boolean backward=v.voterId().equals(request.targetId())&&v.targetId().equals(request.voterId());
            if((forward||backward)&&!v.createdAt().isBefore(now.minusSeconds(rules.reciprocity().days()*86400L))) { reciprocals++;reverse|=backward; }
            if(v.targetId().equals(request.targetId())) {
                incoming.add(v.voterId());
                boolean weak=s.factsKnown()&&(s.maturity()<immature||s.trust()<rules.trust().initial());
                if(v.type()==VoteType.NEGATIVE&&!v.createdAt().isBefore(now.minusSeconds(rules.burst().minutes()*60L))) { negative.add(v.voterId());if(weak) weakNegative.add(v.voterId()); }
                if(!v.createdAt().isBefore(now.minusSeconds(rules.growth().minutes()*60L))) { growth.add(v.voterId());if(weak) weakGrowth.add(v.voterId()); }
            }
        }
        incoming.add(request.voterId());
        long maturity=trust.explanation().get("maturity");boolean weak=maturity<immature||trust.score()<rules.trust().initial();
        if(request.action()==VoteRequest.Action.SUBMIT) {
            growth.add(request.voterId());if(weak) weakGrowth.add(request.voterId());
            if(request.type()==VoteType.NEGATIVE) { negative.add(request.voterId());if(weak) weakNegative.add(request.voterId()); }
        }
        ScopeState memo=graph(view,request,rules,now,candidateSequence);
        boolean clustered=memo.sameCluster(request.voterId(),request.targetId());
        long repeat=rules.repeated().enabled()?rules.step(rules.repeated().multipliers(),repeats):SCALE;
        long reciprocal=rules.reciprocity().enabled()&&reverse&&reciprocals>=rules.reciprocity().evidence()?rules.step(rules.reciprocity().multipliers(),reciprocals-rules.reciprocity().evidence()+2):SCALE;
        long diversity=rules.weight().diversity()?interpolate(rules.weight().diversityMin(),rules.weight().diversityMax(),clamp(ratio(incoming.size(),rules.weight().diversityFull()),0,SCALE)):SCALE;
        long maturityFactor=rules.weight().maturity()?interpolate(rules.weight().maturityMin(),rules.weight().maturityMax(),maturity):SCALE;
        if(repeat<SCALE) signals.add("REPEATED_TARGET");if(reciprocal<SCALE) signals.add("RECIPROCITY");if(clustered) signals.add("DENSE_RECIPROCAL_GROUP");
        boolean burst=rules.burst().enabled()&&request.type()==VoteType.NEGATIVE&&negative.size()>=rules.burst().count();
        boolean rapid=rules.growth().enabled()&&growth.size()>=rules.growth().count();
        long weakNegativeFraction=ratio(weakNegative.size(),Math.max(1,negative.size())),weakGrowthFraction=ratio(weakGrowth.size(),Math.max(1,growth.size()));
        if(burst&&rules.burst().mark()) signals.add("NEGATIVE_SPIKE");if(rapid) signals.add("RAPID_CHANGE");
        boolean high=(burst&&(weakNegativeFraction>=rules.burst().weakFraction()||clustered))||(rapid&&(weakGrowthFraction>=rules.growth().weakFraction()||clustered));
        boolean incomplete=!view.complete()||!memo.graphComplete();if(incomplete) signals.add("ANALYSIS_CAPACITY");
        boolean pending=request.action()==VoteRequest.Action.SUBMIT&&(high||incomplete)&&rules.burst().pending();
        if(incomplete&&!rules.burst().pending()&&request.action()==VoteRequest.Action.SUBMIT) throw new VoteRejectedException(VoteRejectedException.Code.PROCESSING_BUSY);
        Severity severity=high?Severity.HIGH:clustered||reciprocal<SCALE?Severity.MEDIUM:Severity.LOW;
        evidence.put("repeat_interactions",repeats);evidence.put("reciprocal_interactions",reciprocals);evidence.put("independent_support_upper_bound",(long)incoming.size());
        evidence.put("negative_voters",(long)negative.size());evidence.put("weak_negative_fraction",weakNegativeFraction);evidence.put("growth_voters",(long)growth.size());
        evidence.put("weak_growth_fraction",weakGrowthFraction);evidence.put("graph_work",memo.graphWork());evidence.put("graph_revision",memo.graphRevision());
        return new Result(repeat,reciprocal,diversity,maturityFactor,clustered?rules.analysis().clusterMultiplier():SCALE,severity,pending,List.copyOf(signals),Map.copyOf(evidence),memo);
    }
    public static ScopeState backgroundGraph(View view,VoteEntry latest,ProtectionRules rules,Instant now) {
        var request=new VoteRequest(latest.operationId(),latest.voterId(),latest.targetId(),VoteRequest.Action.SUBMIT,latest.type(),latest.reason(),latest.comment());
        return graph(view,request,rules,now,latest.sequence());
    }
    private static ScopeState graph(View view,VoteRequest request,ProtectionRules rules,Instant now,long candidateSequence) {
        var config=rules.analysis();var old=view.scope();
        if(!config.enabled()) return new ScopeState(old.scope(),old.revision(),old.nextAudit(),now,candidateSequence,List.of(),true,0);
        if(!old.graphAt().equals(Instant.EPOCH)&&now.isBefore(old.graphAt().plusSeconds(config.intervalSeconds()))) return old;
        long started=System.nanoTime(),work=0;Set<String> edges=new HashSet<>();var nodes=new TreeSet<UUID>();boolean complete=view.complete();
        for(var sample:view.recent()) {
            if(sample.vote().createdAt().isBefore(now.minusSeconds(config.days()*86400L))||sample.vote().action()==VoteEntry.Action.REVOKE) continue;
            var vote=sample.vote();nodes.add(vote.voterId());nodes.add(vote.targetId());edges.add(vote.voterId()+"/"+vote.targetId());
            if(nodes.size()>config.maxNodes()||edges.size()>config.maxEdges()) { complete=false;break; }
        }
        nodes.add(request.voterId());nodes.add(request.targetId());if(request.action()==VoteRequest.Action.SUBMIT) edges.add(request.voterId()+"/"+request.targetId());
        var clustered=new ArrayList<List<UUID>>();
        if(complete) for(UUID center:nodes) {
            var local=new TreeSet<UUID>();local.add(center);
            for(UUID peer:nodes) { if(++work>config.maxWork()) { complete=false;break; }if(edges.contains(center+"/"+peer)&&edges.contains(peer+"/"+center)) local.add(peer); }
            if(!complete) break;
            if(local.size()<config.clusterMinimum()) continue;
            long directed=0,mutual=0;
            relationships: for(UUID a:local) for(UUID b:local) if(!a.equals(b)) {
                if(++work>config.maxWork()) { complete=false;break relationships; }
                if(edges.contains(a+"/"+b)) { directed++;if(edges.contains(b+"/"+a)) mutual++; }
            }
            if(!complete) break;
            long possible=(long)local.size()*(local.size()-1);
            if(ratio(directed,possible)>=config.density()&&ratio(mutual,possible)>=config.mutualDensity()) clustered.add(List.copyOf(local));
        }
        if(System.nanoTime()-started>config.maxMillis()*1_000_000L) throw new VoteRejectedException(VoteRejectedException.Code.PROCESSING_BUSY);
        return new ScopeState(old.scope(),old.revision(),old.nextAudit(),now,candidateSequence,complete?List.copyOf(clustered):List.of(),complete,work);
    }
}
