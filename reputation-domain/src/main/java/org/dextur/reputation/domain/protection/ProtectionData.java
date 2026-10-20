package org.dextur.reputation.domain.protection;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;
import static org.dextur.reputation.domain.protection.Fixed.*;

/** Private durable models. No Trust or exact factors are copied to public API DTOs. */
public final class ProtectionData {
    private ProtectionData() { }
    public enum Status { ACCEPTED,SUSPICIOUS,PENDING,REVOKED,SUPERSEDED,INVALIDATED }
    public enum Severity { LOW,MEDIUM,HIGH }
    public enum Review { ACCEPT,INVALIDATE,CONFIRM_MANIPULATION }
    public record Trust(UUID player,String scope,long score,int modelVersion,Instant calculatedAt,long revision,long inputRevision,
            long playtimeSeconds,Instant firstJoined,Instant factsAt,long submitted,long invalidated,long confirmed,
            Instant restrictedUntil,Map<String,Long> explanation) {
        public Trust {
            Objects.requireNonNull(player);ProtectionData.scope(scope);Objects.requireNonNull(calculatedAt);Objects.requireNonNull(factsAt);Objects.requireNonNull(firstJoined);Objects.requireNonNull(restrictedUntil);
            explanation=ordered(explanation);
            if(score<0||score>SCALE||modelVersion!=1||revision<0||inputRevision<0||playtimeSeconds<0||submitted<0||invalidated<0||confirmed<0||invalidated>submitted) fail();
        }
        public String key() { return player+"/"+scope; }
    }
    public record Pair(String scope,UUID voter,UUID target,UUID latestVote,String activeVote,long contributionUnits,long ceilingUnits,long revision) {
        public Pair { ProtectionData.scope(scope);Objects.requireNonNull(voter);Objects.requireNonNull(target);Objects.requireNonNull(latestVote);uuidOrEmpty(activeVote);if(ceilingUnits<0||revision<1||voter.equals(target)) fail(); }
        public String key() { return scope+"/"+voter+"/"+target; }
    }
    public record Score(UUID player,String scope,long baselineUnits,long currentUnits,long revision) {
        public Score { Objects.requireNonNull(player);ProtectionData.scope(scope);if(revision<1) fail(); }
        public String key() { return player+"/"+scope; }
    }
    public record Assessment(UUID voteId,long voteSequence,String scope,UUID voter,UUID target,Status status,
            long baseUnits,long weightUnits,long contributionUnits,long ceilingUnits,int calculationVersion,Instant evaluatedAt,
            long inputRevision,long revision,Map<String,Long> factors,List<String> signals,Severity severity,String policyHash) {
        public Assessment {
            Objects.requireNonNull(voteId);ProtectionData.scope(scope);Objects.requireNonNull(voter);Objects.requireNonNull(target);Objects.requireNonNull(status);Objects.requireNonNull(evaluatedAt);Objects.requireNonNull(severity);
            factors=ordered(factors);signals=signals.stream().distinct().sorted().toList();
            if(voteSequence<1||baseUnits<1||weightUnits<0||ceilingUnits<0||weightUnits>ceilingUnits||calculationVersion!=2||inputRevision<0||revision<1||!policyHash.matches("[0-9a-f]{64}")) fail();
            if((status==Status.PENDING||status==Status.REVOKED||status==Status.INVALIDATED||status==Status.SUPERSEDED)&&contributionUnits!=0) fail();
        }
        public Assessment status(Status next) { return new Assessment(voteId,voteSequence,scope,voter,target,next,baseUnits,weightUnits,
                next==Status.ACCEPTED||next==Status.SUSPICIOUS?contributionUnits:0,ceilingUnits,calculationVersion,evaluatedAt,inputRevision,revision+1,factors,signals,severity,policyHash); }
    }
    public record Audit(UUID id,long sequence,String scope,UUID voteId,UUID voter,UUID target,String kind,Status status,
            long beforeUnits,long afterUnits,long removedUnits,long addedUnits,String oldActive,String newActive,
            Instant createdAt,String actor,String reason,long inputRevision,Map<String,Long> evidence,List<UUID> relatedPlayers) {
        public Audit {
            Objects.requireNonNull(id);ProtectionData.scope(scope);Objects.requireNonNull(voteId);Objects.requireNonNull(voter);Objects.requireNonNull(target);Objects.requireNonNull(status);Objects.requireNonNull(createdAt);
            uuidOrEmpty(oldActive);uuidOrEmpty(newActive);Objects.requireNonNull(actor);Objects.requireNonNull(reason);evidence=ordered(evidence);relatedPlayers=relatedPlayers.stream().distinct().sorted().toList();
            if(sequence<1||inputRevision<0||!Set.of("VOTE","ACCEPT","INVALIDATE","CONFIRM_MANIPULATION","ANALYSIS","RESTORE").contains(kind)||reason.length()>1000
                    ||Math.addExact(Math.subtractExact(beforeUnits,removedUnits),addedUnits)!=afterUnits) fail();
        }
    }
    public record ScopeState(String scope,long revision,long nextAudit,Instant graphAt,long graphRevision,
            List<List<UUID>> clusters,boolean graphComplete,long graphWork) {
        public ScopeState { ProtectionData.scope(scope);Objects.requireNonNull(graphAt);clusters=clusters.stream().map(group->group.stream().distinct().sorted().toList()).distinct().sorted(Comparator.comparing(Object::toString)).toList();if(revision<0||nextAudit<1||graphRevision<0||graphWork<0) fail(); }
        public List<UUID> clustered() { return clusters.stream().flatMap(Collection::stream).distinct().sorted().toList(); }
        public boolean sameCluster(UUID a,UUID b) { return clusters.stream().anyMatch(group->group.contains(a)&&group.contains(b)); }
        public static ScopeState empty(String scope) { return new ScopeState(scope,0,1,Instant.EPOCH,0,List.of(),true,0); }
    }
    public record Sample(VoteEntry vote,boolean factsKnown,long trust,long maturity) { }
    public record View(Trust trust,Pair pair,Score score,Assessment latest,Assessment active,ScopeState scope,
            List<Sample> recent,boolean complete) { public View { recent=List.copyOf(recent); } }
    public record Change(VoteEntry vote,ReputationSnapshot profile,Trust trust,Pair pair,Score score,List<Assessment> assessments,Audit audit,ScopeState scope) {
        public Change { assessments=List.copyOf(assessments); }
    }
    private static Map<String,Long> ordered(Map<String,Long> map) { return Collections.unmodifiableMap(new TreeMap<>(map)); }
    private static void scope(String scope) { if(scope==null||!scope.matches("[a-z0-9_-]{1,32}")) fail(); }
    private static void uuidOrEmpty(String id) { Objects.requireNonNull(id);if(!id.isEmpty()) UUID.fromString(id); }
    private static void fail() { throw new IllegalArgumentException("Invalid private protection state"); }
}
