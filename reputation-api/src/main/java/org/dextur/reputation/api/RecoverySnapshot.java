package org.dextur.reputation.api;

import java.time.Instant;
import java.util.*;

/** Immutable public view. Never contains hidden Trust, penalties or activity session credentials. */
public record RecoverySnapshot(UUID playerId,String scope,long revision,Status status,boolean eligible,String reason,
        String enrollmentId,String programId,Map<String,String> programNames,String stageId,Map<String,String> stageNames,
        List<Objective> objectives,List<Reward> rewards,List<ProposedReward> proposedRewards,String stageIcon,Instant enrolledAt,Instant completedAt,Instant cooldownUntil) {
    public enum Status { AVAILABLE,ACTIVE,PAUSED,COMPLETED,CANCELLED,EXPIRED }
    public record Objective(String id,String type,Map<String,String> names,Map<String,String> descriptions,long progress,long required,boolean available) {
        public Objective {names=Map.copyOf(names);descriptions=Map.copyOf(descriptions);}
        public boolean completed(){return available&&progress>=required;}
    }
    public record ProposedReward(String stageId,Map<String,String> names,long reputation){public ProposedReward{names=Map.copyOf(names);}}
    public record Reward(UUID operationId,String stageId,long reputation,Instant grantedAt) { }
    public RecoverySnapshot {Objects.requireNonNull(playerId);Objects.requireNonNull(status);programNames=Map.copyOf(programNames);stageNames=Map.copyOf(stageNames);objectives=List.copyOf(objectives);rewards=List.copyOf(rewards);proposedRewards=List.copyOf(proposedRewards);}
    public int completedTasks(){return (int)objectives.stream().filter(Objective::completed).count();}
    public int progressPercent(){if(objectives.isEmpty())return 0;return (int)(objectives.stream().mapToDouble(o->o.available()?Math.min(1,(double)o.progress()/o.required()):0).average().orElse(0)*100);}
}
