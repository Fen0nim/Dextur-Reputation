package org.dextur.reputation.storage;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.domain.recovery.RecoveryData.State;
/** Complete portable recovery ledger. Includes frozen definitions and private Trust credit audit. */
public record RecoverySnapshotData(List<State> states) {
    public RecoverySnapshotData {states=states.stream().sorted(Comparator.comparing(State::key)).toList();var keys=new HashSet<String>();for(var s:states)if(!keys.add(s.key()))throw new IllegalArgumentException("Duplicate recovery identity");}
    public static RecoverySnapshotData empty(){return new RecoverySnapshotData(List.of());}
    public boolean isEmpty(){return states.isEmpty();}
    public State get(UUID id,String scope){return states.stream().filter(s->s.player().equals(id)&&s.scope().equals(scope)).findFirst().orElse(null);}
    public RecoverySnapshotData put(State row){var rows=new ArrayList<>(states);rows.removeIf(s->s.key().equals(row.key()));rows.add(row);return new RecoverySnapshotData(rows);}
    public void validate(List<ReputationSnapshot> profiles){validate(profiles,AdministrationSnapshotData.empty());}
    public void validate(List<ReputationSnapshot> profiles,AdministrationSnapshotData administration){var index=new HashMap<String,ReputationSnapshot>();for(var p:profiles)index.put(p.playerId()+"/"+p.scopeId(),p);for(var s:states){var p=index.get(s.key());if(p==null||p.systemScore()!=Math.addExact(Math.addExact(s.baselineSystem(),s.lifetimeReward()),administration.contribution(s.player(),s.scope())))throw new IllegalArgumentException("Recovery/profile integrity mismatch");}}
}
