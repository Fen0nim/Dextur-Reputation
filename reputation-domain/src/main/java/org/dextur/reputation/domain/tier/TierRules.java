package org.dextur.reputation.domain.tier;

import java.util.*;

/** Pure deterministic policy; configuration supplies every threshold. */
public record TierRules(List<Definition> definitions,long neutralMinimum,long neutralMaximum,String hash) {
    public record Definition(String id,boolean enabled,long threshold,long hysteresis,int priority,
            Map<String,String> names,String color,String prefix,String suffix,String group,String discordRole,
            boolean notifications,Map<String,String> metadata) {
        public Definition {
            names=Map.copyOf(names);metadata=Map.copyOf(metadata);
            if(!id.matches("[A-Z_]{1,32}")||hysteresis<0||hysteresis>Math.abs(threshold)||priority<0||priority>10000
                    ||!group.matches("|reputation_[a-z0-9_]{1,48}")||!discordRole.matches("|[0-9]{1,32}")) throw new IllegalArgumentException("Invalid tier: "+id);
        }
    }
    public TierRules {
        definitions=List.copyOf(definitions);var ids=new HashSet<String>();var scores=new HashSet<Long>();var priorities=new HashSet<Integer>();
        if(neutralMinimum==Long.MIN_VALUE||neutralMaximum==Long.MAX_VALUE||neutralMinimum>0||neutralMaximum<0||neutralMinimum>neutralMaximum||!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid neutral interval / policy hash");
        for(var d:definitions) {
            if(!ids.add(d.id())||!scores.add(d.threshold())||!priorities.add(d.priority())||d.threshold()==Long.MIN_VALUE) throw new IllegalArgumentException("Duplicate tier ID, threshold or priority");
            if(d.id().equals("NEUTRAL")) { if(!d.enabled()||d.threshold()!=0||d.hysteresis()!=0) throw new IllegalArgumentException("NEUTRAL must be enabled at zero"); }
            else if(d.threshold()>=neutralMinimum&&d.threshold()<=neutralMaximum) throw new IllegalArgumentException("Tier threshold overlaps neutral interval: "+d.id());
        }
        if(!ids.contains("NEUTRAL")||definitions.size()>64||definitions.stream().noneMatch(d->d.threshold()==neutralMaximum+1)||definitions.stream().noneMatch(d->d.threshold()==neutralMinimum-1)) throw new IllegalArgumentException("Tiers must border the neutral interval");
    }
    public Definition byId(String id) { return definitions.stream().filter(t->t.id().equals(id)).findFirst().orElse(null); }
    public Definition select(long score,String previous,String previousHash) {
        var eligible=definitions.stream().filter(d->d.enabled()&&d.threshold()!=0&&(d.threshold()>0?score>=d.threshold():score<=d.threshold()))
                .max(Comparator.comparing(d->java.math.BigInteger.valueOf(d.threshold()).abs())).orElse(byId("NEUTRAL"));
        var old=byId(previous);
        if(hash.equals(previousHash)&&old!=null&&old.enabled()&&old.threshold()!=0
                &&(old.threshold()>0?score>0&&score>=old.threshold()-old.hysteresis():score<0&&score<=old.threshold()+old.hysteresis())
                &&(eligible.threshold()==0||Long.signum(eligible.threshold())==Long.signum(old.threshold())&&Math.abs(old.threshold())>Math.abs(eligible.threshold()))) return old;
        return eligible;
    }
}
