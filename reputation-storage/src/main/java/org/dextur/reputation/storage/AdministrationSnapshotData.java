package org.dextur.reputation.storage;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.domain.policy.AdministrationData.State;
public record AdministrationSnapshotData(List<State> states){
    public AdministrationSnapshotData{states=states.stream().sorted(Comparator.comparing(State::key)).toList();var keys=new HashSet<String>();for(var row:states)if(!keys.add(row.key()))throw new IllegalArgumentException("Duplicate administration identity");}
    public static AdministrationSnapshotData empty(){return new AdministrationSnapshotData(List.of());}
    public boolean isEmpty(){return states.isEmpty();}
    public State get(UUID id,String scope){return states.stream().filter(s->s.player().equals(id)&&s.scope().equals(scope)).findFirst().orElse(null);}
    public long contribution(UUID id,String scope){var row=get(id,scope);return row==null?0:row.contribution();}
    public AdministrationSnapshotData put(State row){var rows=new ArrayList<>(states);rows.removeIf(s->s.key().equals(row.key()));rows.add(row);return new AdministrationSnapshotData(rows);}
    public void validate(List<ReputationSnapshot> profiles,RecoverySnapshotData recovery){var index=new HashMap<String,ReputationSnapshot>();for(var p:profiles)index.put(p.playerId()+"/"+p.scopeId(),p);for(var row:states){var p=index.get(row.key());if(p==null)throw new IllegalArgumentException("Administration profile missing");var program=recovery.get(row.player(),row.scope());var contributions=new HashSet<Long>();if(program==null){if(!row.audit().isEmpty())contributions.add(row.audit().getFirst().nonAdminSystem());if(!row.audit().isEmpty()&&Math.subtractExact(p.systemScore(),row.contribution())!=row.audit().getFirst().nonAdminSystem())throw new IllegalArgumentException("Administration baseline mismatch");}else{contributions.add(program.baselineSystem());for(var reward:program.rewards())contributions.add(reward.systemAfter());}for(var audit:row.audit())if(!contributions.contains(audit.nonAdminSystem()))throw new IllegalArgumentException("Administration/recovery audit mismatch");}}
}
