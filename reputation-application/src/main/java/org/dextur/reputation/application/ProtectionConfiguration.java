package org.dextur.reputation.application;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.domain.protection.*;

/** Missing new fields receive bundled defaults in memory; administrator files are never rewritten. */
public final class ProtectionConfiguration {
    private ProtectionConfiguration() { }
    public static ProtectionRules load(Path directory) throws java.io.IOException {
        var t=merge(YamlFiles.resource("/trust.yml"),YamlFiles.read(directory.resolve("trust.yml")));
        var a=merge(YamlFiles.resource("/anti-abuse.yml"),YamlFiles.read(directory.resolve("anti-abuse.yml")));
        YamlFiles.version(t);YamlFiles.version(a);
        if(number(t,"trust.model-version",1,1)!=1) throw new IllegalArgumentException("Unsupported Trust model");
        if(flag(a,"burst-protection.actions.automatic-permanent-punishment")) throw new IllegalArgumentException("Automatic permanent punishment is not supported");
        return new ProtectionRules(new ProtectionRules.Trust(flag(t,"trust.enabled"),decimal(t,"trust.score.minimum"),decimal(t,"trust.score.maximum"),decimal(t,"trust.score.initial"),
                flag(t,"trust.maturity.enabled"),number(t,"trust.maturity.account-age-days-for-full-credit",1,36500),number(t,"trust.maturity.playtime-hours-for-full-credit",1,1000000),
                flag(t,"trust.history.enabled"),flag(t,"trust.abuse-penalties.enabled"),decimal(t,"trust.maturity.influence"),decimal(t,"trust.history.invalidated-penalty"),
                decimal(t,"trust.history.outgoing-diversity-influence"),decimal(t,"trust.history.repeat-risk-penalty"),decimal(t,"trust.abuse-penalties.confirmed-penalty"),
                flag(t,"trust.recalculation.enabled"),number(t,"trust.recalculation.interval-minutes",1,10080),number(t,"trust.recalculation.batch-size",1,100),number(t,"trust.abuse-penalties.confirmed-restriction-minutes",0,525600)),
                new ProtectionRules.Weight(flag(a,"voting-weight.enabled"),decimal(a,"voting-weight.base.like"),decimal(a,"voting-weight.base.dislike"),decimal(a,"voting-weight.effective-weight.minimum"),decimal(a,"voting-weight.effective-weight.maximum"),
                        flag(a,"voting-weight.trust-multiplier.enabled"),decimal(a,"voting-weight.trust-multiplier.minimum"),decimal(a,"voting-weight.trust-multiplier.maximum"),flag(a,"voting-weight.diversity.enabled"),
                        decimal(a,"voting-weight.diversity.minimum"),decimal(a,"voting-weight.diversity.maximum"),number(a,"voting-weight.diversity.full-credit-voters",1,10000),flag(a,"voting-weight.account-maturity.enabled"),decimal(a,"voting-weight.account-maturity.minimum"),decimal(a,"voting-weight.account-maturity.maximum")),
                new ProtectionRules.Repeat(flag(a,"repeated-target.enabled"),number(a,"repeated-target.window-days",1,3650),steps(a,"repeated-target.multipliers")),
                new ProtectionRules.Reciprocity(flag(a,"reciprocity.enabled"),number(a,"reciprocity.window-days",1,3650),number(a,"reciprocity.minimum-interactions-for-flag",2,10000),steps(a,"reciprocity.multipliers")),
                new ProtectionRules.Analysis(flag(a,"clusters.enabled"),number(a,"clusters.window-days",1,3650),number(a,"clusters.interval-seconds",1,86400),number(a,"clusters.maximum-events",16,10000),
                        number(a,"clusters.maximum-nodes",4,256),number(a,"clusters.maximum-edges",12,65536),number(a,"clusters.maximum-work",100,1000000),number(a,"clusters.maximum-milliseconds",1,1000),number(a,"clusters.minimum-members",4,256),
                        decimal(a,"clusters.minimum-density"),decimal(a,"clusters.minimum-mutual-density"),decimal(a,"clusters.confidence-multiplier")),
                new ProtectionRules.Burst(flag(a,"burst-protection.enabled"),number(a,"burst-protection.negative-votes.window-minutes",1,10080),number(a,"burst-protection.negative-votes.suspicious-count",2,10000),
                        decimal(a,"burst-protection.immature-threshold"),decimal(a,"burst-protection.weak-voter-fraction"),flag(a,"burst-protection.actions.mark-suspicious"),flag(a,"burst-protection.actions.allow-pending")),
                new ProtectionRules.Growth(flag(a,"rapid-change.enabled"),number(a,"rapid-change.window-minutes",1,10080),number(a,"rapid-change.suspicious-count",2,10000),decimal(a,"rapid-change.weak-voter-fraction")),decimal(a,"storage.json-warning-fraction"));
    }
    private static List<Long> steps(Map<String,Object> values,String key) { return List.of("first","second","third","fourth","subsequent").stream().map(s->decimal(values,key+"."+s)).toList(); }
    private static Object get(Map<String,Object> root,String path) {
        Object value=root;for(String part:path.split("\\.")) value=VotingConfiguration.mapping(value).get(part);
        if(value==null) throw new IllegalArgumentException("Missing protection setting: "+path);return value;
    }
    private static long decimal(Map<String,Object> root,String path) {
        try { return Fixed.decimal(get(root,path)); } catch(RuntimeException error) { throw new IllegalArgumentException("Invalid decimal: "+path); }
    }
    private static int number(Map<String,Object> root,String path,int min,int max) { return YamlFiles.number(Map.of("value",get(root,path)),"value",min,max); }
    private static boolean flag(Map<String,Object> root,String path) { return YamlFiles.flag(Map.of("value",get(root,path)),"value"); }
    private static Map<String,Object> merge(Map<String,Object> defaults,Map<String,Object> custom) {
        var result=new HashMap<>(defaults);
        for(var entry:custom.entrySet()) {
            Object before=defaults.get(entry.getKey()),after=entry.getValue();
            if(before instanceof Map<?,?>&&after instanceof Map<?,?>) result.put(entry.getKey(),merge(VotingConfiguration.mapping(before),VotingConfiguration.mapping(after)));
            else result.put(entry.getKey(),after);
        }return Map.copyOf(result);
    }
}
