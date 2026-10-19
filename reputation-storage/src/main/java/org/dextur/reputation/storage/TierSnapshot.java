package org.dextur.reputation.storage;
import java.util.*;
import org.dextur.reputation.domain.tier.*;
import org.dextur.reputation.api.ReputationSnapshot;
/** Portable complete tier state. No dependency on the currently configured policy. */
public record TierSnapshot(List<TierState> states) {
    public TierSnapshot { var map=new TreeMap<String,TierState>();for(var s:states) if(map.put(s.key(),s)!=null) throw new IllegalArgumentException("Duplicate tier state");states=List.copyOf(map.values()); }
    public static TierSnapshot empty() { return new TierSnapshot(List.of()); }
    public boolean isEmpty() { return states.isEmpty(); }
    public TierState get(UUID id,String scope) { return states.stream().filter(s->s.player().equals(id)&&s.scope().equals(scope)).findFirst().orElse(null); }
    public TierSnapshot put(TierState state) { var rows=new ArrayList<>(states);rows.removeIf(s->s.key().equals(state.key()));rows.add(state);return new TierSnapshot(rows); }
    public void validate(List<ReputationSnapshot> profiles) {
        var index=new HashMap<String,ReputationSnapshot>();for(var p:profiles) index.put(p.playerId()+"/"+p.scopeId(),p);
        for(var state:states) { var p=index.get(state.key());if(p==null||!p.tier().equals(state.tier())||p.revision()!=state.profileRevision()) throw new IllegalArgumentException("Tier/profile revision mismatch"); }
    }
    static TierState reconcile(ReputationSnapshot profile,TierState old,TierRules rules) {
        var selected=rules.select(profile.visibleScore(),old==null?profile.tier():old.tier(),old==null?"":old.policyHash());
        boolean changed=old==null?!profile.tier().equals(selected.id()):!old.tier().equals(selected.id());
        long revision=old==null?1:old.revision()+(changed?1:0);
        var history=new ArrayList<>(old==null?List.<TierState.Transition>of():old.history());
        if(changed) history.add(new TierState.Transition(UUID.randomUUID(),revision,old==null?profile.tier():old.tier(),selected.id(),profile.visibleScore(),profile.revision(),StorageSnapshot.now(),old!=null&&old.policyHash().equals(rules.hash())));
        boolean sync=old==null||changed||!old.policyHash().equals(rules.hash())||!old.desiredGroup().equals(selected.group());
        return new TierState(profile.playerId(),profile.scopeId(),selected.id(),rules.hash(),profile.revision(),revision,history,selected.group(),old==null?1:old.syncRevision()+(sync?1:0),old==null?0:old.appliedRevision(),old==null?List.of():old.ownedGroups());
    }
    static ReputationSnapshot profile(ReputationSnapshot p,TierState state) { return new ReputationSnapshot(p.playerId(),p.scopeId(),p.communityScore(),p.systemScore(),p.visibleScore(),state.tier(),p.revision(),p.createdAt(),p.updatedAt()); }
}
