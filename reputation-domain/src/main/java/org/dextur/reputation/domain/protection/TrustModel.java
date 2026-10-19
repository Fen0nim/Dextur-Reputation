package org.dextur.reputation.domain.protection;

import java.time.*;
import java.util.*;
import org.dextur.reputation.domain.VoterFacts;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import static org.dextur.reputation.domain.protection.Fixed.*;

/** Version 1: verified maturity, outgoing diversity, reliability and explicit confirmed penalties. Never reputation. */
public final class TrustModel {
    private TrustModel() { }
    public static Trust initial(UUID player,String scope,VoterFacts facts,ProtectionRules rules,Instant now,long inputRevision) {
        return new Trust(player,scope,rules.trust().initial(),1,now,1,inputRevision,facts.playtimeSeconds(),facts.firstJoined(),now,0,0,0,Instant.EPOCH,
                Map.of("initial_only",1L,"initial",rules.trust().initial(),"maturity",maturity(facts,rules,now)));
    }
    public static long maturity(VoterFacts facts,ProtectionRules rules,Instant now) {
        long age=Math.max(0,Duration.between(facts.firstJoined(),now).getSeconds());
        long account=clamp(ratio(Math.min(age,(long)rules.trust().ageDays()*86400),(long)rules.trust().ageDays()*86400),0,SCALE);
        long play=clamp(ratio(Math.min(facts.playtimeSeconds(),(long)rules.trust().playHours()*3600),(long)rules.trust().playHours()*3600),0,SCALE);
        return (account+play)/2;
    }
    public static Trust calculate(UUID player,String scope,VoterFacts facts,ProtectionRules rules,Instant now,
            Trust previous,long inputRevision,List<Sample> recent,boolean submission) {
        var config=rules.trust();long submitted=previous==null?0:previous.submitted(),invalid=previous==null?0:previous.invalidated(),confirmed=previous==null?0:previous.confirmed();
        if(submission) submitted=Math.incrementExact(submitted);
        long maturity=maturity(facts,rules,now);
        var targets=new HashSet<UUID>();long repeats=0;var pairs=new HashMap<UUID,Integer>();
        for(var sample:recent) if(sample.vote().voterId().equals(player)&&sample.vote().action()!=org.dextur.reputation.api.VoteEntry.Action.REVOKE) {
            targets.add(sample.vote().targetId());int count=pairs.merge(sample.vote().targetId(),1,Integer::sum);if(count>=rules.reciprocity().evidence()) repeats++;
        }
        long diversity=clamp(ratio(targets.size(),Math.max(1,rules.weight().diversityFull())),0,SCALE);
        long reliability=clamp(ratio(invalid,Math.max(1,submitted)),0,SCALE),risk=clamp(ratio(repeats,Math.max(1,rules.reciprocity().evidence())),0,SCALE);
        long score=config.initial();
        if(config.enabled()) {
            if(config.maturity()) score=Math.addExact(score,product(config.maturityInfluence(),maturity-SCALE/2));
            if(config.history()) score=Math.addExact(Math.subtractExact(score,product(config.reliabilityPenalty(),reliability)+product(config.riskPenalty(),risk)),product(config.diversityInfluence(),diversity));
            if(config.penalties()) score=Math.subtractExact(score,Math.multiplyExact(config.confirmedPenalty(),Math.min(confirmed,1000000)));
        }
        score=clamp(score,config.min(),config.max());
        var explanation=new TreeMap<String,Long>();explanation.put("maturity",maturity);explanation.put("outgoing_diversity",diversity);explanation.put("invalidated_fraction",reliability);explanation.put("repeat_risk",risk);
        explanation.put("initial",config.initial());explanation.put("confirmed",confirmed);explanation.put("enabled",config.enabled()?1L:0L);explanation.put("minimum",config.min());explanation.put("maximum",config.max());
        explanation.put("maturity_enabled",config.maturity()?1L:0L);explanation.put("history_enabled",config.history()?1L:0L);explanation.put("penalties_enabled",config.penalties()?1L:0L);
        explanation.put("maturity_influence",config.maturityInfluence());explanation.put("reliability_penalty",config.reliabilityPenalty());explanation.put("diversity_influence",config.diversityInfluence());
        explanation.put("repeat_penalty",config.riskPenalty());explanation.put("confirmed_penalty",config.confirmedPenalty());
        return new Trust(player,scope,score,1,now,previous==null?1:Math.incrementExact(previous.revision()),inputRevision,
                facts.playtimeSeconds(),facts.firstJoined(),facts.online()||previous==null?now:previous.factsAt(),submitted,invalid,confirmed,previous==null?Instant.EPOCH:previous.restrictedUntil(),
                explanation);
    }
    /** Uses only persisted factors, independent of the current configuration. */
    public static long reproduce(Trust trust) {
        var f=trust.explanation();long score=f.get("initial");if(f.getOrDefault("initial_only",0L)==1) return score;
        if(f.get("enabled")==1) {
            if(f.get("maturity_enabled")==1) score=Math.addExact(score,product(f.get("maturity_influence"),f.get("maturity")-SCALE/2));
            if(f.get("history_enabled")==1) score=Math.addExact(Math.subtractExact(score,product(f.get("reliability_penalty"),f.get("invalidated_fraction"))+product(f.get("repeat_penalty"),f.get("repeat_risk"))),product(f.get("diversity_influence"),f.get("outgoing_diversity")));
            if(f.get("penalties_enabled")==1) score=Math.subtractExact(score,Math.multiplyExact(f.get("confirmed_penalty"),Math.min(f.get("confirmed"),1000000)));
        }
        return clamp(score,f.get("minimum"),f.get("maximum"));
    }
}
