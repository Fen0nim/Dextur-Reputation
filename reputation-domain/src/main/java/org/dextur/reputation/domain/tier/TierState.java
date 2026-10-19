package org.dextur.reputation.domain.tier;

import java.time.Instant;
import java.util.*;

/** Durable hysteresis, local transition journal and permission reconciliation intent. */
public record TierState(UUID player,String scope,String tier,String policyHash,long profileRevision,long revision,
        List<Transition> history,String desiredGroup,long syncRevision,long appliedRevision,List<String> ownedGroups) {
    public record Transition(UUID id,long revision,String from,String to,long score,long profileRevision,Instant at,boolean notifyPlayer) {
        public Transition { Objects.requireNonNull(id);Objects.requireNonNull(at);if(revision<1||profileRevision<0||!from.matches("[A-Z_]{1,32}")||!to.matches("[A-Z_]{1,32}")||from.equals(to)) throw new IllegalArgumentException("Invalid tier transition"); }
    }
    public TierState {
        Objects.requireNonNull(player);history=List.copyOf(history);ownedGroups=ownedGroups.stream().distinct().sorted().toList();
        if(!scope.matches("[a-z0-9_-]{1,32}")||!tier.matches("[A-Z_]{1,32}")||!policyHash.matches("[0-9a-f]{64}")||profileRevision<0||revision<1||syncRevision<1||appliedRevision<0||appliedRevision>syncRevision||!safeGroup(desiredGroup)) throw new IllegalArgumentException("Invalid tier state");
        long last=0,lastProfile=-1;String prior=null;var ids=new HashSet<UUID>();
        for(var t:history) { if(last==0?(t.revision()!=1&&t.revision()!=2):t.revision()!=last+1) throw new IllegalArgumentException("Tier revision gap");if(t.revision()>revision||t.profileRevision()<lastProfile||t.profileRevision()>profileRevision||!ids.add(t.id())||prior!=null&&!prior.equals(t.from())) throw new IllegalArgumentException("Invalid tier history chain");last=t.revision();lastProfile=t.profileRevision();prior=t.to(); }
        if(history.isEmpty()?revision!=1:last!=revision)throw new IllegalArgumentException("Tier revision/history mismatch");
        if(prior!=null&&!prior.equals(tier)||ownedGroups.size()>64||ownedGroups.stream().anyMatch(g->g.isEmpty()||!safeGroup(g))) throw new IllegalArgumentException("Invalid tier history / group ownership");
    }
    private static boolean safeGroup(String group) { return group!=null&&group.matches("|reputation_[a-z0-9_]{1,48}"); }
    public String key() { return player+"/"+scope; }
    public TierState synced(long expected,List<String> owned) {
        if(expected!=syncRevision) throw new IllegalStateException("Stale permission synchronization");
        return new TierState(player,scope,tier,policyHash,profileRevision,revision,history,desiredGroup,syncRevision,expected,owned);
    }
}
