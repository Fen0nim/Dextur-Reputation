package org.dextur.reputation.domain.recovery;

import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;

/** Private durable state: immutable definitions, receipts, session watermarks and complete audit. */
public final class RecoveryData {
    private RecoveryData() { }
    public enum Command { READ,START,CANCEL,PAUSE,RESUME,RESET_STAGE,LOCK,UNLOCK,SAMPLE,EXTERNAL }
    public record Facts(boolean known,long playSeconds,Instant firstJoined) {
        public Facts {Objects.requireNonNull(firstJoined);if(playSeconds<0)throw new IllegalArgumentException("Negative playtime");}
    }
    public record Request(UUID operation,UUID player,String scope,Command command,Instant now,Facts facts,
            UUID session,long connected,long active,boolean login,UUID enrollment,String objective,long progress,String provider,String actor,String reason,Set<String> availableProviders) {
        public Request {Objects.requireNonNull(operation);Objects.requireNonNull(player);if(!scope.matches("[a-z0-9_-]{1,32}"))throw new IllegalArgumentException("Scope");Objects.requireNonNull(command);Objects.requireNonNull(now);Objects.requireNonNull(facts);Objects.requireNonNull(session);Objects.requireNonNull(enrollment);Objects.requireNonNull(objective);Objects.requireNonNull(provider);Objects.requireNonNull(actor);Objects.requireNonNull(reason);availableProviders=Set.copyOf(availableProviders);if(connected<0||active<0||active>connected||progress<0||actor.length()>128||reason.length()>500)throw new IllegalArgumentException("Invalid recovery request");}
    }
    public record Receipt(UUID operation,String payload) {public Receipt{Objects.requireNonNull(operation);if(payload==null||!payload.matches("[0-9a-f]{64}"))RecoveryRules.fail();}}
    public record Reward(UUID operation,UUID enrollment,String stage,long reputation,long trustCredit,long systemBefore,long systemAfter,long programCap,long lifetimeCap,long trustProgramCap,long trustLifetimeCap,boolean trustAllowed,Instant at) {
        public Reward{Objects.requireNonNull(operation);Objects.requireNonNull(enrollment);RecoveryRules.identifier(stage);Objects.requireNonNull(at);if(reputation<0||trustCredit<0||programCap<0||lifetimeCap<0||trustProgramCap<0||trustLifetimeCap<0||Math.addExact(systemBefore,reputation)!=systemAfter)RecoveryRules.fail();}
    }
    public record Audit(UUID id,UUID enrollment,String kind,String stage,Instant at,String actor,String reason,String fromStatus,String toStatus,Map<String,Long> progress,long connectedTotal,long activeTotal,long confirmedCounter,boolean locked) {
        public Audit{Objects.requireNonNull(id);Objects.requireNonNull(enrollment);RecoveryService.Kind.valueOf(kind);RecoverySnapshot.Status.valueOf(fromStatus);RecoverySnapshot.Status.valueOf(toStatus);progress=longs(progress);if(connectedTotal<0||activeTotal<0||activeTotal>connectedTotal||confirmedCounter<0)RecoveryRules.fail();Objects.requireNonNull(stage);Objects.requireNonNull(at);if(actor==null||actor.length()>128||reason==null||reason.length()>500)RecoveryRules.fail();}
    }
    public record Enrollment(UUID id,RecoveryRules definition,RecoverySnapshot.Status status,int stage,Instant startedAt,Instant stageAt,Instant endedAt,
            long stagePlay,long stageActive,int stageDays,Map<String,Long> external,Map<String,Long> progress,List<String> completedStages) {
        public Enrollment {Objects.requireNonNull(id);Objects.requireNonNull(definition);Objects.requireNonNull(status);Objects.requireNonNull(startedAt);Objects.requireNonNull(stageAt);Objects.requireNonNull(endedAt);external=longs(external);progress=longs(progress);completedStages=List.copyOf(completedStages);if(status==RecoverySnapshot.Status.AVAILABLE||stage<0||stage>=definition.program().stages().size()||stagePlay<0||stageActive<0||stageDays<0||stageAt.isBefore(startedAt)||!endedAt.equals(Instant.EPOCH)&&endedAt.isBefore(startedAt))RecoveryRules.fail();RecoveryRules.unique(completedStages);
            var current=definition.program().stages().get(stage);var required=new HashMap<String,RecoveryRules.Objective>();for(var objective:current.objectives())required.put(objective.id(),objective);
            for(var value:progress.entrySet())if(!required.containsKey(value.getKey())||value.getValue()>required.get(value.getKey()).required())RecoveryRules.fail();
            for(var value:external.entrySet())if(!required.containsKey(value.getKey())||required.get(value.getKey()).type()!=RecoveryRules.Type.EXTERNAL||value.getValue()>required.get(value.getKey()).required())RecoveryRules.fail();
            var completed=definition.program().stages().stream().limit(stage+(status==RecoverySnapshot.Status.COMPLETED?1:0)).map(RecoveryRules.Stage::id).toList();if(!completedStages.equals(completed))RecoveryRules.fail();
            if(status==RecoverySnapshot.Status.COMPLETED&&(stage!=definition.program().stages().size()-1||!completedProgress(current,progress)))RecoveryRules.fail();
        }
    }
    public record State(UUID player,String scope,long revision,long baselineSystem,long lifetimeReward,long lifetimeTrust,
            boolean locked,long confirmedSeen,Instant lastAt,Instant activityAt,Instant eligibleSince,UUID session,long sessionConnected,long sessionActive,long play,long active,
            String latestDay,Map<String,Long> dayActive,List<String> loginDates,List<String> loginDays,List<Enrollment> enrollments,List<Reward> rewards,List<Audit> audit,List<Receipt> receipts) {
        public State {
            Objects.requireNonNull(player);if(!scope.matches("[a-z0-9_-]{1,32}"))RecoveryRules.fail();Objects.requireNonNull(lastAt);Objects.requireNonNull(activityAt);Objects.requireNonNull(eligibleSince);Objects.requireNonNull(session);Objects.requireNonNull(latestDay);if(!latestDay.isEmpty())LocalDate.parse(latestDay);dayActive=longs(dayActive);dayActive.keySet().forEach(LocalDate::parse);loginDates=loginDates.stream().distinct().sorted().toList();loginDates.forEach(LocalDate::parse);loginDays=loginDays.stream().distinct().sorted().toList();loginDays.forEach(LocalDate::parse);enrollments=List.copyOf(enrollments);rewards=List.copyOf(rewards);audit=List.copyOf(audit);receipts=List.copyOf(receipts);
            if(revision<0||lifetimeReward<0||lifetimeTrust<0||confirmedSeen<0||sessionConnected<0||sessionActive<0||sessionActive>sessionConnected||play<0||active<0||active>play||receipts.size()>10000||dayActive.size()>3660)RecoveryRules.fail();
            var ids=new HashSet<UUID>();long total=0,credit=0,system=baselineSystem;for(var r:rewards){if(!ids.add(r.operation())||!r.operation().equals(RecoveryEngine.stable(r.enrollment()+"/reward/"+r.stage()))||r.systemBefore()!=system)RecoveryRules.fail();system=r.systemAfter();total=Math.addExact(total,r.reputation());credit=Math.addExact(credit,r.trustCredit());}if(total!=lifetimeReward||credit!=lifetimeTrust)RecoveryRules.fail();
            ids.clear();for(var e:enrollments)if(!ids.add(e.id()))RecoveryRules.fail();if(enrollments.stream().filter(e->e.status()==RecoverySnapshot.Status.ACTIVE||e.status()==RecoverySnapshot.Status.PAUSED).count()>1)RecoveryRules.fail();
            for(var r:rewards){if(!ids.contains(r.enrollment()))RecoveryRules.fail();var e=enrollments.stream().filter(x->x.id().equals(r.enrollment())).findFirst().orElseThrow();var stage=e.definition().program().stages().stream().filter(x->x.id().equals(r.stage())).findFirst().orElseThrow();if(r.reputation()>stage.reputation()||r.trustCredit()>stage.trustCredit())RecoveryRules.fail();}
            for(var e:enrollments){long reward=rewards.stream().filter(x->x.enrollment().equals(e.id())).mapToLong(Reward::reputation).sum(),trust=rewards.stream().filter(x->x.enrollment().equals(e.id())).mapToLong(Reward::trustCredit).sum();if(reward>e.definition().maximumProgramReward()||trust>e.definition().maximumTrustCredit())RecoveryRules.fail();}
            for(var a:audit)if(!ids.contains(a.enrollment())&&!(a.kind().equals("ADMINISTRATIVE_LOCK_CHANGED")&&a.enrollment().equals(new UUID(0,0))))RecoveryRules.fail();
            long cumulative=0,cumulativeTrust=0;var spent=new HashMap<UUID,Long>();var trustSpent=new HashMap<UUID,Long>();for(var r:rewards){var e=enrollments.stream().filter(x->x.id().equals(r.enrollment())).findFirst().orElseThrow();var stage=e.definition().program().stages().stream().filter(x->x.id().equals(r.stage())).findFirst().orElseThrow();if(r.programCap()>e.definition().maximumProgramReward()||r.lifetimeCap()>e.definition().maximumLifetimeReward()||r.trustProgramCap()>e.definition().maximumTrustCredit()||r.trustLifetimeCap()>e.definition().lifetimeTrustCredit())RecoveryRules.fail();long expected=Math.min(stage.reputation(),Math.min(Math.max(0,r.programCap()-spent.getOrDefault(r.enrollment(),0L)),Math.max(0,r.lifetimeCap()-cumulative)));long expectedTrust=r.trustAllowed()?Math.min(stage.trustCredit(),Math.min(Math.max(0,r.trustProgramCap()-trustSpent.getOrDefault(r.enrollment(),0L)),Math.max(0,r.trustLifetimeCap()-cumulativeTrust))):0;if(r.reputation()!=expected||r.trustCredit()!=expectedTrust)RecoveryRules.fail();cumulative=Math.addExact(cumulative,r.reputation());cumulativeTrust=Math.addExact(cumulativeTrust,r.trustCredit());spent.merge(r.enrollment(),r.reputation(),Math::addExact);trustSpent.merge(r.enrollment(),r.trustCredit(),Math::addExact);}
            for(var r:rewards){var event=audit.stream().filter(a->a.enrollment().equals(r.enrollment())&&a.stage().equals(r.stage())&&a.kind().equals("REWARD_GRANTED")).findFirst().orElseThrow();if(r.trustAllowed()&&(event.confirmedCounter()>0||event.locked()))RecoveryRules.fail();}ids.clear();for(var r:receipts)if(!ids.add(r.operation()))RecoveryRules.fail();ids.clear();for(var a:audit)if(!ids.add(a.id()))RecoveryRules.fail();
        }
        public String key(){return player+"/"+scope;}
        public Enrollment current(){return enrollments.isEmpty()?null:enrollments.getLast();}
        public static State empty(UUID p,String s,long baseline){return new State(p,s,0,baseline,0,0,false,0,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,new UUID(0,0),0,0,0,0,"",Map.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());}
    }
    public static Request disabled(State state,Instant now){return new Request(UUID.randomUUID(),state.player(),state.scope(),Command.READ,now.isBefore(state.lastAt())?state.lastAt():now,new Facts(false,0,Instant.EPOCH),new UUID(0,0),0,0,false,new UUID(0,0),"",0,"","SERVER","DISABLED",Set.of());}
    public record Change(State state,ReputationSnapshot profile,org.dextur.reputation.domain.protection.ProtectionData.Trust trust,RecoverySnapshot snapshot,List<RecoveryService.Changed> events) {public Change{events=List.copyOf(events);}}
    private static boolean completedProgress(RecoveryRules.Stage stage,Map<String,Long> progress){for(var o:stage.objectives())if(progress.getOrDefault(o.id(),0L)!=o.required())return false;return true;}
    static Map<String,Long> longs(Map<String,Long> m){var sorted=new TreeMap<>(m);if(sorted.values().stream().anyMatch(n->n==null||n<0))RecoveryRules.fail();return Collections.unmodifiableMap(sorted);}
}
