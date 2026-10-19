package org.dextur.reputation.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.PlayerLanguage;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;

class TierApplicationTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    private Settings settings() throws Exception {StorageFixtures.resources(directory);try(var in=getClass().getResourceAsStream("/voting.yml")){Files.copy(in,directory.resolve("voting.yml"));}return Settings.load(directory);}
    @Test void publicEventsFollowCommitAndAreOrderedWithoutReadOrDuplicateEffects() throws Exception {
        var settings=settings();try(var backend=StorageFixtures.local(directory,StorageType.SQLITE,false)) {
            UUID target=TierContract.seed(backend,99),voter=UUID.randomUUID();var rules=TierConfiguration.load(directory);backend.tiers(rules);
            try(var app=new ReputationApplication(backend,settings);var tier=new TierApplication(app,backend,settings,rules,new DisplayFormatter(DisplayConfiguration.load(directory)),error->{throw new AssertionError(error);})){var changes=new CopyOnWriteArrayList<TierService.TierChanged>();tier.onTierChanged(changes::add);tier.getTier(target).toCompletableFuture().get();assertTrue(changes.isEmpty());
                var config=VotingConfiguration.load(directory,Messages.bundled(PlayerLanguage.ru_RU));var voting=new VotingApplication(app,backend,settings,config,r->CompletableFuture.completedFuture(mature()),Clock.fixed(NOW,ZoneOffset.UTC),error->{throw new AssertionError(error);});
                // Use the QA eligibility port to isolate tier transitions from production admission limits.
                var request=vote(voter,target,VoteType.POSITIVE,"event");var receipt=app.submit(()->{var value=backend.vote("global",request,mature(),eligibility(),NOW);return value;}).get();
                // Public voting view populates a committed read without replaying effects.
                voting.view(target).toCompletableFuture().get();assertTrue(changes.isEmpty());assertEquals("RESPECTED",tier.cachedTier(target).orElseThrow().id());
                assertEquals(receipt.currentView().profile(),app.cachedProfile(target).orElseThrow());assertFalse(tier.format(target,"ru_RU").isEmpty());
            }
        }
    }
    @Test void realVotingPipelineDeliversOnlyFreshCommittedTierEventsAndShutdownClosesApi() throws Exception {
        var settings=settings();String custom=Files.readString(directory.resolve("voting.yml")).replace("minimum-playtime-seconds: 108000","minimum-playtime-seconds: 0").replace("minimum-account-age-seconds: 604800","minimum-account-age-seconds: 0").replace("cooldown-seconds: 7200","cooldown-seconds: 0").replace("base-daily-limit: 3","base-daily-limit: 1000");Files.writeString(directory.resolve("voting.yml"),custom);
        try(var backend=StorageFixtures.local(directory,StorageType.SQLITE,false)) {
            UUID target=TierContract.seed(backend,99);var rules=TierConfiguration.load(directory);backend.tiers(rules);
            var app=new ReputationApplication(backend,settings);var tiers=new TierApplication(app,backend,settings,rules,new DisplayFormatter(DisplayConfiguration.load(directory)),error->{throw new AssertionError(error);});var events=new CopyOnWriteArrayList<TierService.TierChanged>();tiers.onTierChanged(events::add);
            var config=VotingConfiguration.load(directory,Messages.bundled(PlayerLanguage.ru_RU));var voting=new VotingApplication(app,backend,settings,config,r->CompletableFuture.completedFuture(mature()),Clock.fixed(NOW,ZoneOffset.UTC),error->{throw new AssertionError(error);});
            tiers.getTier(target).toCompletableFuture().get();var request=new VoteRequest(UUID.randomUUID(),UUID.randomUUID(),target,VoteRequest.Action.SUBMIT,VoteType.POSITIVE,"helpful","");
            var receipt=voting.submit(request).toCompletableFuture().get();assertEquals("RESPECTED",receipt.currentView().profile().tier());assertEquals(1,events.size());assertEquals(backend.tierState(target,"global").history().getLast().id(),events.getFirst().transitionId());
            voting.submit(request).toCompletableFuture().get();voting.view(target).toCompletableFuture().get();tiers.getTier(target).toCompletableFuture().get();assertEquals(1,events.size());
            voting.submit(new VoteRequest(UUID.randomUUID(),request.voterId(),target,VoteRequest.Action.REVOKE,null,"","")).toCompletableFuture().get();
            var retained=tiers.getTier(target).toCompletableFuture().get();assertEquals("RESPECTED",retained.id());assertEquals(99,retained.score());assertEquals(250L,retained.nextPositiveThreshold());
            app.forget(target);tiers.forget(target);tiers.getTier(target).toCompletableFuture().get();assertEquals(1,events.size());tiers.close();assertTrue(tiers.cachedTier(target).isEmpty());assertThrows(Exception.class,()->tiers.getTier(target).toCompletableFuture().get());app.close();
        }
    }
    @Test void durablePermissionIntentRetriesWithoutOverlappingBatchesOrLosingOwnership() throws Exception {
        var settings=settings();try(var backend=StorageFixtures.local(directory,StorageType.SQLITE,false)) {
            UUID target=TierContract.seed(backend,99);backend.tiers(TierConfiguration.load(directory));var app=new ReputationApplication(backend,settings);var calls=new AtomicInteger();var failures=new AtomicInteger();
            var started=new CompletableFuture<Void>();var first=new CompletableFuture<List<String>>();var permissions=new TierPermissionSync.Permissions(){public CompletionStage<List<String>> synchronize(org.dextur.reputation.domain.tier.TierState state){int call=calls.incrementAndGet();started.complete(null);return call==1?first:CompletableFuture.completedFuture(List.of("reputation_owned"));}public void close(){}};
            try(var sync=new TierPermissionSync(app,backend,"global",16,permissions,e->failures.incrementAndGet())) {
                var pending=sync.tick().toCompletableFuture();started.get(3,TimeUnit.SECONDS);assertSame(pending,sync.tick());assertEquals(1,calls.get());first.completeExceptionally(new IllegalStateException("simulated LP failure"));pending.get();assertEquals(1,failures.get());assertEquals(0,backend.tierState(target,"global").appliedRevision());
                sync.tick().toCompletableFuture().get();assertEquals(2,calls.get());var state=backend.tierState(target,"global");assertEquals(state.syncRevision(),state.appliedRevision());assertEquals(List.of("reputation_owned"),state.ownedGroups());sync.tick().toCompletableFuture().get();assertEquals(2,calls.get());
            }app.close();
        }
    }
}
