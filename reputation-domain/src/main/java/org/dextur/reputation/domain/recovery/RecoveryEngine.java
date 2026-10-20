package org.dextur.reputation.domain.recovery;

import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.Trust;
import static org.dextur.reputation.domain.recovery.RecoveryData.*;
import static org.dextur.reputation.api.RecoverySnapshot.Status.*;

/** One deterministic decision function for every backend. Rewards are applied by the caller in its transaction. */
public final class RecoveryEngine {
    private RecoveryEngine() { }
    public static Change evaluate(State before,ReputationSnapshot profile,Trust trust,RecoveryRules current,Request request) {
        State initial=before==null?State.empty(request.player(),request.scope(),profile.systemScore()):before;
        return new Work(initial,profile,trust,current,request).run();
    }
    private static final class Work {
        final State old;final Request r;final RecoveryRules current;final List<Enrollment> enrollments;final List<Reward> rewards;final List<Audit> audit;final List<Receipt> receipts;
        final Map<String,Long> dayActive;final Set<String> days;final Set<String> loginDates;final List<Audit> added=new ArrayList<>();
        ReputationSnapshot profile;Trust trust;Enrollment e;long lifetime,credit,play,active,sessionPlay,sessionActive,confirmed;UUID session;String latestDay;boolean locked;Instant activityAt,eligibleSince;String auditStatus;
        Work(State old,ReputationSnapshot profile,Trust trust,RecoveryRules current,Request r){
            this.old=old;this.profile=profile;this.trust=trust;this.current=current;this.r=r;e=old.current();auditStatus=e==null?AVAILABLE.name():e.status().name();enrollments=new ArrayList<>(old.enrollments());rewards=new ArrayList<>(old.rewards());audit=new ArrayList<>(old.audit());receipts=new ArrayList<>(old.receipts());dayActive=new TreeMap<>(old.dayActive());days=new TreeSet<>(old.loginDays());loginDates=new TreeSet<>(old.loginDates());
            lifetime=old.lifetimeReward();credit=old.lifetimeTrust();play=old.play();active=old.active();session=old.session();sessionPlay=old.sessionConnected();sessionActive=old.sessionActive();confirmed=old.confirmedSeen();latestDay=old.latestDay();locked=old.locked();activityAt=old.activityAt();eligibleSince=old.eligibleSince();
        }
        boolean open(){return e!=null&&(e.status()==ACTIVE||e.status()==PAUSED);}
        void event(RecoveryService.Kind kind,String reason){UUID id=stable(r.operation()+"/"+kind+"/"+(e==null?"":e.id())+"/"+(e==null?0:e.stage()));var a=new Audit(id,e==null?new UUID(0,0):e.id(),kind.name(),e==null?"":stage().id(),r.now(),r.actor(),reason,auditStatus,e==null?AVAILABLE.name():e.status().name(),e==null?Map.of():e.progress(),play,active,confirmed,locked);auditStatus=a.toStatus();if(audit.stream().noneMatch(v->v.id().equals(id))){audit.add(a);added.add(a);}}
        RecoveryRules.Stage stage(){return e.definition().program().stages().get(e.stage());}
        void enrollment(RecoverySnapshot.Status status,Instant ended){e=new Enrollment(e.id(),e.definition(),status,e.stage(),e.startedAt(),e.stageAt(),ended,e.stagePlay(),e.stageActive(),e.stageDays(),e.external(),e.progress(),e.completedStages());}
        void policy(RecoveryRules.Action action,String reason){if(!open()||action==RecoveryRules.Action.NONE)return;switch(action){
            case PAUSE->{if(e.status()!=PAUSED){enrollment(PAUSED,r.now());event(RecoveryService.Kind.PROGRAM_PAUSED,reason);}}
            case CANCEL->{enrollment(CANCELLED,r.now());event(RecoveryService.Kind.PROGRAM_CANCELLED,reason);}
            case RESET_STAGE->{eligibleSince=r.now();e=new Enrollment(e.id(),e.definition(),PAUSED,e.stage(),e.startedAt(),r.now(),r.now(),play,active,days.size(),Map.of(),Map.of(),e.completedStages());event(RecoveryService.Kind.PROGRAM_PAUSED,reason);}
            default->{}
        }}
        String eligibility(){if(!current.enabled())return "DISABLED";if(!r.facts().known())return "UNKNOWN";if(locked)return "LOCKED";if(open())return "ACTIVE";if(profile.visibleScore()>current.threshold())return "SCORE";if(r.facts().playSeconds()<current.minimumPlaySeconds())return "PLAYTIME";if(r.facts().firstJoined().isAfter(r.now())||Duration.between(r.facts().firstJoined(),r.now()).getSeconds()<current.minimumAgeSeconds())return "AGE";if(r.now().isBefore(cooldown()))return "COOLDOWN";if(trust!=null&&(trust.restrictedUntil().isAfter(r.now())))return "RESTRICTED";return "READY";}
        Instant cooldown(){return e==null||open()?Instant.EPOCH:e.endedAt().plusSeconds(Math.max(current.cooldownSeconds(),e.definition().cooldownSeconds()));}
        boolean available(RecoveryRules.Objective o){return switch(o.type()){case EXTERNAL->r.availableProviders().contains(o.provider()+"@"+o.providerVersion());case NO_CONFIRMED_ABUSE->trust!=null;default->true;};}
        void start(){if(!eligibility().equals("READY"))throw new IllegalStateException(eligibility());if(r.actor().equals("PLAYER")&&!current.manual())throw new IllegalStateException("MANUAL_DISABLED");
            if(trust!=null)confirmed=trust.confirmed();eligibleSince=r.now();UUID id=stable(r.operation()+"/enrollment");e=new Enrollment(id,current,ACTIVE,0,r.now(),r.now(),Instant.EPOCH,play,active,days.size(),Map.of(),Map.of(),List.of());event(RecoveryService.Kind.PROGRAM_STARTED,"");}
        Change run(){
            boolean record=switch(r.command()){case START,CANCEL,PAUSE,RESUME,RESET_STAGE,LOCK,UNLOCK,EXTERNAL->true;default->false;};String payload=hash(java.util.stream.Stream.of(r.command().name(),r.player().toString(),r.scope(),r.enrollment().toString(),r.objective(),Long.toString(r.progress()),r.provider(),r.actor(),r.reason()).map(v->v.length()+":"+v).collect(java.util.stream.Collectors.joining()));
            var prior=receipts.stream().filter(x->x.operation().equals(r.operation())).findFirst().orElse(null);
            if(prior!=null){if(!prior.payload().equals(payload))throw new IllegalArgumentException("ID_CONFLICT");return result(old,List.of());}
            if(r.now().isBefore(old.lastAt()))throw new IllegalStateException("CLOCK_REGRESSION");
            if(record&&receipts.size()>=10000)throw new IllegalStateException("RECEIPT_LIMIT");
            if(r.command()==Command.LOCK)locked=true;if(r.command()==Command.UNLOCK)locked=false;if(r.command()==Command.LOCK||r.command()==Command.UNLOCK)event(RecoveryService.Kind.ADMINISTRATIVE_LOCK_CHANGED,r.reason());
            if(open()){
                if(!current.enabled())policy(RecoveryRules.Action.PAUSE,"DISABLED");
                if(trust!=null&&trust.confirmed()>confirmed){confirmed=trust.confirmed();policy(e.definition().confirmedAction(),"CONFIRMED_ABUSE");}
                if(locked||trust!=null&&trust.restrictedUntil().isAfter(r.now()))policy(e.definition().lockAction(),"LOCKED");
                if(open()&&!r.now().isBefore(e.startedAt().plusSeconds(e.definition().expirationSeconds()))){enrollment(EXPIRED,r.now());event(RecoveryService.Kind.PROGRAM_EXPIRED,"");}
            }
            if(r.command()==Command.START)start();
            else if(!open()&&current.enabled()&&current.automatic()&&r.command()==Command.SAMPLE&&eligibility().equals("READY"))start();
            switch(r.command()){
                case CANCEL->{if(open()){enrollment(CANCELLED,r.now());event(RecoveryService.Kind.PROGRAM_CANCELLED,r.reason());}}
                case PAUSE->policy(RecoveryRules.Action.PAUSE,r.reason());
                case RESET_STAGE->policy(RecoveryRules.Action.RESET_STAGE,r.reason());
                case RESUME->{if(e!=null&&e.status()==PAUSED){if(!current.enabled()||locked||trust!=null&&trust.restrictedUntil().isAfter(r.now()))throw new IllegalStateException("RESTRICTED");if(stage().objectives().stream().anyMatch(o->!available(o)))throw new IllegalStateException("PROVIDER_UNAVAILABLE");long paused=Math.max(0,Duration.between(e.endedAt(),r.now()).getSeconds());eligibleSince=r.now();e=new Enrollment(e.id(),e.definition(),ACTIVE,e.stage(),e.startedAt(),e.stageAt().plusSeconds(paused),Instant.EPOCH,e.stagePlay(),e.stageActive(),e.stageDays(),e.external(),e.progress(),e.completedStages());event(RecoveryService.Kind.PROGRAM_RESUMED,r.reason());}}
                default->{}
            }
            if(r.command()==Command.SAMPLE)sample();
            if(r.command()==Command.EXTERNAL){if(e==null||!e.id().equals(r.enrollment())||e.status()!=ACTIVE)throw new IllegalStateException("STALE_ENROLLMENT");var objective=stage().objectives().stream().filter(o->o.id().equals(r.objective())&&o.type()==RecoveryRules.Type.EXTERNAL&&o.provider().equals(r.provider())&&available(o)).findFirst().orElseThrow(()->new IllegalArgumentException("INVALID_OBJECTIVE"));if(r.progress()>objective.required()||r.progress()<e.external().getOrDefault(objective.id(),0L))throw new IllegalArgumentException("INVALID_PROGRESS");var external=new TreeMap<>(e.external());external.put(objective.id(),r.progress());e=new Enrollment(e.id(),e.definition(),e.status(),e.stage(),e.startedAt(),e.stageAt(),e.endedAt(),e.stagePlay(),e.stageActive(),e.stageDays(),external,e.progress(),e.completedStages());}
            if(e!=null&&e.status()==ACTIVE&&current.enabled())advance();
            if(record)receipts.add(new Receipt(r.operation(),payload));
            if(e!=null){if(enrollments.isEmpty()||!enrollments.getLast().id().equals(e.id()))enrollments.add(e);else enrollments.set(enrollments.size()-1,e);}
            State next=new State(old.player(),old.scope(),old.revision()+1,old.baselineSystem(),lifetime,credit,locked,confirmed,r.now(),activityAt,eligibleSince,session,sessionPlay,sessionActive,play,active,latestDay,dayActive,new ArrayList<>(loginDates),new ArrayList<>(days),enrollments,rewards,audit,receipts);
            // Read-only status with no objective/state change avoids a write and repeated notifications.
            State comparable=new State(next.player(),next.scope(),old.revision(),next.baselineSystem(),next.lifetimeReward(),next.lifetimeTrust(),next.locked(),next.confirmedSeen(),old.lastAt(),next.activityAt(),next.eligibleSince(),next.session(),next.sessionConnected(),next.sessionActive(),next.play(),next.active(),next.latestDay(),next.dayActive(),next.loginDates(),next.loginDays(),next.enrollments(),next.rewards(),next.audit(),next.receipts());
            if(comparable.equals(old))return result(old,List.of());
            if(added.isEmpty()&&e!=null&&e.status()==ACTIVE&&(old.current()==null||!e.progress().equals(old.current().progress())))event(RecoveryService.Kind.PROGRESS_CHANGED,"");
            next=new State(next.player(),next.scope(),next.revision(),next.baselineSystem(),next.lifetimeReward(),next.lifetimeTrust(),next.locked(),next.confirmedSeen(),next.lastAt(),next.activityAt(),next.eligibleSince(),next.session(),next.sessionConnected(),next.sessionActive(),next.play(),next.active(),next.latestDay(),next.dayActive(),next.loginDates(),next.loginDays(),next.enrollments(),next.rewards(),audit,next.receipts());
            return result(next,added);
        }
        void sample(){
            boolean newSession=!session.equals(r.session());
            if(newSession){if(!r.login())return;
                if(receipts.stream().anyMatch(x->x.operation().equals(r.session())))return;
                if(receipts.size()>=10000)throw new IllegalStateException("RECEIPT_LIMIT");
                receipts.add(new Receipt(r.session(),hash("SESSION/"+r.player()+"/"+r.scope())));session=r.session();sessionPlay=0;sessionActive=0;}
            if(r.connected()<sessionPlay||r.active()<sessionActive)return;
            long delta=r.connected()-sessionPlay,eligible=r.active()-sessionActive;if(eligible>delta+1)throw new IllegalArgumentException("ACTIVITY_OVERCOUNT");eligible=Math.min(eligible,delta);
            // A delayed or forged cumulative sample cannot count more than elapsed server time plus one flush interval.
            long max=Math.max(0,Duration.between(old.activityAt().equals(Instant.EPOCH)?r.now():old.activityAt(),r.now()).getSeconds())+current.persistenceSeconds()*2L;
            if(delta>max)throw new IllegalArgumentException("ACTIVITY_JUMP");sessionPlay=r.connected();sessionActive=r.active();activityAt=r.now();
            String day=r.now().atZone(ZoneId.of(e==null?current.timezone():e.definition().timezone())).toLocalDate().toString();
            if(newSession&&r.login()){if(loginDates.size()>=3660&&!loginDates.contains(day))throw new IllegalStateException("DAY_LIMIT");loginDates.add(day);}
            if(e==null||e.status()!=ACTIVE||!current.enabled())return;
            // A cumulative interval crossing enrollment/resume cannot prove where its activity occurred.
            // Establish a fresh baseline instead of replaying pre-enrollment or paused activity.
            if(old.activityAt().isBefore(eligibleSince)){delta=0;eligible=0;}
            play=Math.addExact(play,delta);active=Math.addExact(active,eligible);

            if(day.compareTo(latestDay)>=0){latestDay=day;if(dayActive.size()>=3660&&!dayActive.containsKey(day))throw new IllegalStateException("DAY_LIMIT");dayActive.merge(day,eligible,Math::addExact);if(loginDates.contains(day)&&dayActive.get(day)>=e.definition().minimumDayActiveSeconds())days.add(day);}
        }
        void advance(){
            var stage=stage();if(stage.objectives().stream().anyMatch(o->!available(o))){policy(RecoveryRules.Action.PAUSE,"PROVIDER_UNAVAILABLE");return;}
            var progress=new TreeMap<String,Long>();for(var o:stage.objectives()){
                long n=switch(o.type()){
                    case PLAYTIME->play-e.stagePlay();case ACTIVE_PLAYTIME->active-e.stageActive();case LOGIN_DAYS->days.size()-e.stageDays();
                    case WAIT_PERIOD->Math.max(0,Duration.between(e.stageAt(),r.now()).getSeconds());
                    case NO_CONFIRMED_ABUSE->trust!=null&&trust.confirmed()==0&&!trust.restrictedUntil().isAfter(r.now())?Math.max(0,Duration.between(e.stageAt(),r.now()).getSeconds()):0;
                    case EXTERNAL->e.external().getOrDefault(o.id(),0L);
                };progress.put(o.id(),Math.min(o.required(),Math.max(e.progress().getOrDefault(o.id(),0L),n)));
            }
            e=new Enrollment(e.id(),e.definition(),e.status(),e.stage(),e.startedAt(),e.stageAt(),e.endedAt(),e.stagePlay(),e.stageActive(),e.stageDays(),e.external(),progress,e.completedStages());
            if(stage.objectives().stream().anyMatch(o->progress.get(o.id())<o.required()))return;
            UUID rewardId=stable(e.id()+"/reward/"+stage.id());
            if(rewards.stream().noneMatch(x->x.operation().equals(rewardId))){
                long spent=rewards.stream().filter(x->x.enrollment().equals(e.id())).mapToLong(Reward::reputation).sum();long usedCredit=rewards.stream().filter(x->x.enrollment().equals(e.id())).mapToLong(Reward::trustCredit).sum();
                long amount=Math.min(stage.reputation(),Math.min(Math.max(0,Math.min(current.maximumProgramReward(),e.definition().maximumProgramReward())-spent),Math.max(0,Math.min(current.maximumLifetimeReward(),e.definition().maximumLifetimeReward())-lifetime)));
                boolean trustAllowed=TrustModel.canRehabilitate(trust)&&trust.confirmed()==0&&!locked&&!trust.restrictedUntil().isAfter(r.now());long grantedCredit=!trustAllowed?0:Math.min(stage.trustCredit(),Math.min(Math.max(0,Math.min(current.maximumTrustCredit(),e.definition().maximumTrustCredit())-usedCredit),Math.max(0,Math.min(current.lifetimeTrustCredit(),e.definition().lifetimeTrustCredit())-credit)));
                long after=Math.addExact(profile.systemScore(),amount);rewards.add(new Reward(rewardId,e.id(),stage.id(),amount,grantedCredit,profile.systemScore(),after,Math.min(current.maximumProgramReward(),e.definition().maximumProgramReward()),Math.min(current.maximumLifetimeReward(),e.definition().maximumLifetimeReward()),Math.min(current.maximumTrustCredit(),e.definition().maximumTrustCredit()),Math.min(current.lifetimeTrustCredit(),e.definition().lifetimeTrustCredit()),trustAllowed,r.now()));lifetime=Math.addExact(lifetime,amount);credit=Math.addExact(credit,grantedCredit);
                profile=new ReputationSnapshot(profile.playerId(),profile.scopeId(),profile.communityScore(),after,Math.addExact(profile.communityScore(),after),profile.tier(),Math.incrementExact(profile.revision()),profile.createdAt(),r.now());
                if(grantedCredit>0)trust=TrustModel.rehabilitate(trust,credit,r.now());
                event(RecoveryService.Kind.REWARD_GRANTED,"");
            }
            var completed=new ArrayList<>(e.completedStages());if(!completed.contains(stage.id()))completed.add(stage.id());event(RecoveryService.Kind.STAGE_COMPLETED,"");
            if(e.stage()+1==e.definition().program().stages().size()){e=new Enrollment(e.id(),e.definition(),COMPLETED,e.stage(),e.startedAt(),e.stageAt(),r.now(),e.stagePlay(),e.stageActive(),e.stageDays(),e.external(),e.progress(),completed);event(RecoveryService.Kind.PROGRAM_COMPLETED,"");}
            else e=new Enrollment(e.id(),e.definition(),ACTIVE,e.stage()+1,e.startedAt(),r.now(),Instant.EPOCH,play,active,days.size(),Map.of(),Map.of(),completed);
        }
        Change result(State state,List<Audit> changes){e=state.current();var objectives=new ArrayList<RecoverySnapshot.Objective>();if(e!=null)for(var o:stage().objectives())objectives.add(new RecoverySnapshot.Objective(o.id(),o.type().name(),o.names(),o.descriptions(),e.progress().getOrDefault(o.id(),0L),o.required(),e.status()==COMPLETED||e.completedStages().contains(stage().id())||available(o)));
            var publicRewards=state.rewards().stream().filter(x->e!=null&&x.enrollment().equals(e.id())).map(x->new RecoverySnapshot.Reward(x.operation(),x.stage(),x.reputation(),x.at())).toList();String reason=eligibility();
            if(e!=null&&e.status()==PAUSED){var pause=state.audit().reversed().stream().filter(a->a.enrollment().equals(e.id())&&a.kind().equals(RecoveryService.Kind.PROGRAM_PAUSED.name())).findFirst();String cause=pause.map(Audit::reason).orElse("");reason=switch(cause){case "DISABLED"->"DISABLED";case "PROVIDER_UNAVAILABLE"->"PROVIDER_UNAVAILABLE";case "LOCKED"->"LOCKED";case "CONFIRMED_ABUSE"->"RESTRICTED";default->"PAUSED";};}
            var snapshot=new RecoverySnapshot(state.player(),state.scope(),state.revision(),e==null?AVAILABLE:e.status(),reason.equals("READY"),reason,e==null?"":e.id().toString(),e==null?current.program().id():e.definition().program().id(),e==null?current.program().names():e.definition().program().names(),e==null?"":stage().id(),e==null?Map.of():stage().names(),objectives,publicRewards,(e==null?current:e.definition()).program().stages().stream().map(stage->new RecoverySnapshot.ProposedReward(stage.id(),stage.names(),stage.reputation())).toList(),e==null?"COMPASS":stage().icon(),e==null?Instant.EPOCH:e.startedAt(),e==null||open() ? Instant.EPOCH:e.endedAt(),cooldown());
            return new Change(state,profile,trust,snapshot,changes.stream().map(a->new RecoveryService.Changed(a.id(),RecoveryService.Kind.valueOf(a.kind()),snapshot,a.stage())).toList());
        }
    }
    public static UUID stable(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
    public static String hash(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
