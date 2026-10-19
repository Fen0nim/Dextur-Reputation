package org.dextur.reputation.domain.protection;

import java.util.*;
import static org.dextur.reputation.domain.protection.Fixed.*;

/** Internal configuration, deliberately absent from reputation-api. All decimals are fixed point. */
public record ProtectionRules(Trust trust,Weight weight,Repeat repeated,Reciprocity reciprocity,
        Analysis analysis,Burst burst,Growth growth,long jsonWarningFraction) {
    public record Trust(boolean enabled,long min,long max,long initial,boolean maturity,int ageDays,int playHours,
            boolean history,boolean penalties,long maturityInfluence,long reliabilityPenalty,long diversityInfluence,long riskPenalty,long confirmedPenalty,
            boolean recalculate,int intervalMinutes,int batchSize,int restrictionMinutes) { }
    public record Weight(boolean enabled,long positive,long negative,long min,long max,boolean trust,long trustMin,long trustMax,
            boolean diversity,long diversityMin,long diversityMax,int diversityFull,boolean maturity,long maturityMin,long maturityMax) { }
    public record Repeat(boolean enabled,int days,List<Long> multipliers) { public Repeat { multipliers=List.copyOf(multipliers); } }
    public record Reciprocity(boolean enabled,int days,int evidence,List<Long> multipliers) { public Reciprocity { multipliers=List.copyOf(multipliers); } }
    public record Analysis(boolean enabled,int days,int intervalSeconds,int maxEvents,int maxNodes,int maxEdges,int maxWork,
            int maxMillis,int clusterMinimum,long density,long mutualDensity,long clusterMultiplier) { }
    public record Burst(boolean enabled,int minutes,int count,long immatureThreshold,long weakFraction,boolean mark,boolean pending) { }
    public record Growth(boolean enabled,int minutes,int count,long weakFraction) { }
    public ProtectionRules {
        Objects.requireNonNull(trust);Objects.requireNonNull(weight);Objects.requireNonNull(repeated);Objects.requireNonNull(reciprocity);
        Objects.requireNonNull(analysis);Objects.requireNonNull(burst);Objects.requireNonNull(growth);
        if(trust.min()<0||trust.max()>SCALE||trust.min()>trust.initial()||trust.initial()>trust.max()) fail("trust.score");
        for(long fraction:List.of(trust.maturityInfluence(),trust.reliabilityPenalty(),trust.diversityInfluence(),trust.riskPenalty(),trust.confirmedPenalty(),weight.diversityMin(),weight.diversityMax(),
                weight.maturityMin(),weight.maturityMax(),analysis.density(),analysis.mutualDensity(),analysis.clusterMultiplier(),burst.immatureThreshold(),burst.weakFraction(),growth.weakFraction(),jsonWarningFraction))
            if(fraction<0||fraction>SCALE) fail("fraction");
        if(trust.ageDays()<1||trust.ageDays()>36500||trust.playHours()<1||trust.playHours()>1000000||trust.intervalMinutes()<1||trust.intervalMinutes()>10080
                ||trust.batchSize()<1||trust.batchSize()>100||trust.restrictionMinutes()<0||trust.restrictionMinutes()>525600) fail("trust bounds");
        if(weight.positive()<1||weight.negative()<1||weight.positive()>units(1000000)||weight.negative()>units(1000000)||weight.min()<0||weight.max()<weight.min()||weight.max()>units(1000000)
                ||weight.trustMin()<0||weight.trustMax()<weight.trustMin()||weight.trustMax()>units(10)||weight.diversityMin()>weight.diversityMax()
                ||weight.maturityMin()>weight.maturityMax()||weight.diversityFull()<1||weight.diversityFull()>10000) fail("weight bounds");
        validateSteps(repeated.days(),repeated.multipliers());validateSteps(reciprocity.days(),reciprocity.multipliers());
        if(reciprocity.evidence()<2||reciprocity.evidence()>10000||analysis.days()<1||analysis.days()>3650||analysis.intervalSeconds()<1||analysis.intervalSeconds()>86400
                ||analysis.maxEvents()<16||analysis.maxEvents()>10000||analysis.maxNodes()<4||analysis.maxNodes()>256||analysis.maxEdges()<12||analysis.maxEdges()>65536
                ||analysis.maxWork()<100||analysis.maxWork()>1000000||analysis.maxMillis()<1||analysis.maxMillis()>1000||analysis.clusterMinimum()<4||analysis.clusterMinimum()>analysis.maxNodes()) fail("analysis bounds");
        if(burst.minutes()<1||burst.minutes()>10080||burst.count()<2||burst.count()>analysis.maxEvents()||growth.minutes()<1||growth.minutes()>10080||growth.count()<2||growth.count()>analysis.maxEvents()
                ||burst.weakFraction()<1||growth.weakFraction()<1||analysis.density()<1||analysis.mutualDensity()<1||jsonWarningFraction<1) fail("evidence bounds");
    }
    private static void validateSteps(int days,List<Long> values) {
        if(days<1||days>3650||values.size()!=5) fail("repeat window/steps");
        long previous=SCALE;
        for(long value:values) { if(value<0||value>previous) fail("multipliers must decrease within 0..1");previous=value; }
    }
    private static void fail(String field) { throw new IllegalArgumentException("Invalid protection configuration: "+field); }
    public long step(List<Long> multipliers,long interaction) { return multipliers.get((int)Math.min(4,Math.max(0,interaction-1))); }
    public int lookbackDays() { return Math.max(analysis.days(),Math.max(repeated.days(),Math.max(reciprocity.days(),Math.max((burst.minutes()+1439)/1440,(growth.minutes()+1439)/1440)))); }
}
