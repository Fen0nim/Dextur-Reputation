package org.dextur.reputation.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;

/** Internal capability: not part of reputation-api, not registered in ServicesManager or exposed by player commands. */
public final class ProtectionAdministration {
    private final ReputationApplication application;
    private final VotingApplication voting;
    private final ProtectionRepository repository;
    private final Settings settings;
    private final ProtectionRules rules;
    private final Clock clock;
    private final AtomicBoolean analyzing=new AtomicBoolean(),running=new AtomicBoolean(),closed=new AtomicBoolean();
    private final AtomicLong batches=new AtomicLong(),refreshed=new AtomicLong(),stale=new AtomicLong(),failed=new AtomicLong(),reviewed=new AtomicLong(),elapsedNanos=new AtomicLong();
    public record Metrics(long batches,long refreshed,long stale,long failed,long reviewed,long elapsedNanos,boolean running) { }
    public ProtectionAdministration(ReputationApplication app,VotingApplication voting,ProtectionRepository repository,Settings settings,ProtectionRules rules,Clock clock) {
        this.application=app;this.voting=voting;this.repository=repository;this.settings=settings;this.rules=rules;this.clock=clock;
    }
    public CompletionStage<Trust> trust(UUID player) { return application.submit(()->repository.inspectTrust(player,settings.scope())); }
    public CompletionStage<Assessment> assessment(UUID vote) { return application.submit(()->repository.inspectVote(vote)); }
    public CompletionStage<List<Audit>> audit(long after,int limit) { return application.submit(()->repository.audit(settings.scope(),after,limit)); }
    public CompletionStage<ReputationView> review(UUID transition,UUID vote,UUID operator,String reason,Review action,long revision) {
        if(closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Administration stopped"));
        return application.submit(()->{
            var evaluation=repository.inspectVote(vote);if(evaluation==null||!evaluation.scope().equals(settings.scope())) throw new IllegalArgumentException("Unknown scoped evaluation");
            return application.withProfiles(evaluation.voter(),evaluation.target(),()->{
                boolean duplicate=repository.inspectTransition(transition)!=null;
                var result=repository.review(transition,vote,operator,reason,action,revision,now());application.updateProfile(result.profile());
                if(!duplicate) { reviewed.incrementAndGet();voting.statusCommitted(new VotingService.VoteStatusChanged(transition,vote,repository.outcome(vote).status(),result)); }
                return result;
            });
        });
    }
    public CompletionStage<Boolean> initialize(UUID player,VoterFacts facts) {
        if(closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Administration stopped"));
        return application.submit(()->application.withProfiles(player,player,()->repository.refreshTrust(player,settings.scope(),-1,facts,now())));
    }
    /** Caller captures actual fresh Paper facts before calling; CAS protects intervening votes. */
    public CompletionStage<Boolean> refresh(UUID player,long revision,VoterFacts facts) {
        if(closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Administration stopped"));
        return application.submit(()->repository.refreshTrust(player,settings.scope(),revision,facts,now()));
    }
    /** One bounded, non-overlapping job. Offline inputs retain their original verification timestamp. */
    public CompletionStage<Metrics> recalculateDue() {
        if(closed.get()||!rules.trust().recalculate()||!running.compareAndSet(false,true)) return CompletableFuture.completedFuture(metrics());
        return application.submit(()->{
            long start=System.nanoTime();batches.incrementAndGet();
            try {
                var now=now();var due=repository.dueTrust(settings.scope(),now.minusSeconds(rules.trust().intervalMinutes()*60L),rules.trust().batchSize());
                for(var record:due) {
                    if(closed.get()||Thread.currentThread().isInterrupted()) break;
                    var facts=new VoterFacts(false,true,record.playtimeSeconds(),record.firstJoined(),true,true,true);
                    if(repository.refreshTrust(record.player(),record.scope(),record.revision(),facts,now)) refreshed.incrementAndGet();else stale.incrementAndGet();
                }
            } catch(Exception error) { failed.incrementAndGet();throw error; }
            finally { elapsedNanos.addAndGet(System.nanoTime()-start); }
            return metrics();
        }).whenComplete((value,error)->running.set(false));
    }
    public CompletionStage<Boolean> analyze() {
        if(closed.get()||!rules.analysis().enabled()||!analyzing.compareAndSet(false,true)) return CompletableFuture.completedFuture(false);
        return application.submit(()->repository.analyze(settings.scope(),now())).whenComplete((value,error)->{analyzing.set(false);if(error!=null) failed.incrementAndGet();});
    }
    public CompletionStage<Long> storageBytes() { return application.submit(repository::storageBytes); }
    public Metrics metrics() { return new Metrics(batches.get(),refreshed.get(),stale.get(),failed.get(),reviewed.get(),elapsedNanos.get(),running.get()); }
    public void stop() { closed.set(true); }
    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
