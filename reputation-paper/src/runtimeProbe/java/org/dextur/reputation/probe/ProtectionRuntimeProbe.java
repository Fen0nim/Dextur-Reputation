package org.dextur.reputation.probe;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.time.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.ProtectionAdministration;
import org.dextur.reputation.domain.VoterFacts;
import org.dextur.reputation.domain.protection.ProtectionData.Review;

/** Trusted, isolated QA plugin. The private capability is deliberately obtained by reflection, never a public service. */
final class ProtectionRuntimeProbe {
    private final JavaPlugin plugin;
    private final VotingService voting;
    private ProtectionAdministration admin;
    private Player a,b;
    private UUID target,pending;
    private long baseline;
    private int cleanup,additionalReviews;
    private final java.util.concurrent.atomic.AtomicInteger commits=new java.util.concurrent.atomic.AtomicInteger(),statuses=new java.util.concurrent.atomic.AtomicInteger();
    private final Properties old=new Properties();
    ProtectionRuntimeProbe(JavaPlugin plugin) { this.plugin=plugin;voting=plugin.getServer().getServicesManager().load(VotingService.class); }
    void start(Runnable success,Consumer<Throwable> failed) {
        Path file=plugin.getDataFolder().toPath().resolve("protection-state.properties");
        final AutoCloseable committed,changed;
        try {
            var owner=plugin.getServer().getPluginManager().getPlugin("DexturReputation");var method=owner.getClass().getDeclaredMethod("protectionAdministration");method.setAccessible(true);admin=(ProtectionAdministration)method.invoke(owner);
            check(plugin.getServer().getServicesManager().load(ProtectionAdministration.class)==null,"Private administration exposed as service");
            if(Files.exists(file)) try(var input=Files.newInputStream(file)) { old.load(input); }
            committed=voting.onCommitted(e->commits.incrementAndGet());changed=voting.onStatusChanged(e->statuses.incrementAndGet());
        } catch(Throwable error) {failed.accept(error);return;}
        poll(()->onMain(()->{
            a=plugin.getServer().getPlayerExact("Phase3A");b=plugin.getServer().getPlayerExact("Phase3B");var player=plugin.getServer().getPlayerExact("Phase3Target");
            if(a==null||b==null||player==null) return false;
            target=player.getUniqueId();player.kick(net.kyori.adventure.text.Component.text("QA: known offline target"));a.performCommand("rep language en_US");b.performCommand("rep language ru_RU");return true;
        }),Boolean::booleanValue,0)
        .thenCompose(ignored->poll(()->onMain(()->plugin.getServer().getPlayer(target)==null),Boolean::booleanValue,0))
        .thenCompose(ignored->poll(()->admin.trust(a.getUniqueId()),Objects::nonNull,0))
        .thenCompose(trust->{
            check(trust.modelVersion()==1,"Trust model version");
            if(old.isEmpty()) check(trust.score()==500000,"Initial Trust must be 0.50");
            else check(trust.revision()>=Long.parseLong(old.getProperty("trustRevision")),"Trust restart revision lost");
            return voting.history(target,VotePage.Direction.RECEIVED,1);
        }).thenCompose(page->{
            check(page.totalEntries()==Long.parseLong(old.getProperty("entries","0")),"Phase 3 journal restart mismatch");
            if(old.isEmpty()) return CompletableFuture.completedFuture(null);
            pending=UUID.fromString(old.getProperty("pending"));
            return voting.outcome(pending).thenCompose(outcome->{check(outcome.status()==VoteProcessingOutcome.Status.PENDING,"Pending did not survive restart");return admin.assessment(pending);})
                .thenCompose(evaluation->{check(evaluation.weightUnits()==350000,"Exact fraction lost on restart");return admin.review(UUID.randomUUID(),pending,a.getUniqueId(),"QA reject previous pending",Review.INVALIDATE,evaluation.revision());})
                .thenCompose(ignored->voting.submit(revoke(a,"restart cleanup"))).thenCompose(ignored->voting.submit(revoke(b,"restart cleanup"))).thenApply(ignored->{cleanup=2;return null;});
        }).thenCompose(ignored->voting.history(target,VotePage.Direction.RECEIVED,1))
        .thenCompose(page->{
            String legacy=System.getProperty("dextur.probe.legacy-target");if(legacy==null)return CompletableFuture.completedFuture(page);
            UUID oldTarget=UUID.fromString(legacy);
            return voting.history(oldTarget,VotePage.Direction.RECEIVED,1).thenCompose(history->{
                check(history.totalEntries()==Long.parseLong(System.getProperty("dextur.probe.legacy-count")),"Phase 2 history lost on upgrade");check(history.entries().stream().allMatch(item->item.entry().calculationVersion()==1),"Phase 2 weights reinterpreted");return voting.view(oldTarget);
            }).thenApply(view->{check(view.profile().visibleScore()==Long.parseLong(System.getProperty("dextur.probe.legacy-score")),"Legacy profile changed");plugin.getLogger().info("PROBE_LEGACY_PHASE2_PASS: preserved real format-2 profiles and version-1 votes");return page;});
        })
        .thenCompose(page->{
            baseline=page.totalEntries();var request=request(a,VoteType.POSITIVE,"parallel fractional");var calls=new ArrayList<CompletableFuture<VoteReceipt>>();
            for(int i=0;i<12;i++) calls.add(voting.submit(request).toCompletableFuture());
            return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).thenApply(done->{check(calls.stream().map(CompletableFuture::join).filter(r->!r.duplicate()).count()==1,"Fraction duplicate applied");return calls.getFirst().join();});
        }).thenCompose(receipt->{
            check(receipt.currentView().profile().visibleScore()==0,"Single .35 rounded prematurely");return voting.submit(request(b,VoteType.NEGATIVE,"first negative"));
        }).thenCompose(receipt->voting.outcome(receipt.entry().operationId()).thenCompose(outcome->{
            // Previous-run submissions remain evidence inside the window even after revocation.
            if(outcome.status()!=VoteProcessingOutcome.Status.PENDING) return CompletableFuture.completedFuture(receipt.currentView());
            additionalReviews++;return admin.review(UUID.randomUUID(),receipt.entry().operationId(),a.getUniqueId(),"QA accepted returning voter",Review.ACCEPT,1);
        })).thenCompose(view->voting.submit(request(a,VoteType.NEGATIVE,"pending replacement")))
        .thenCompose(receipt->{
            pending=receipt.entry().operationId();check(receipt.currentView().profile().visibleScore()==0,"Pending replacement changed score");check(receipt.currentView().statistics().positive()==1&&receipt.currentView().statistics().negative()==1,"Pending replaced active contribution");return voting.outcome(pending);
        }).thenCompose(outcome->{check(outcome.status()==VoteProcessingOutcome.Status.PENDING,"Immature negative spike not pending");return admin.assessment(pending);})
        .thenCompose(evaluation->{
            check(evaluation.weightUnits()==350000,"Fraction not stored exactly");UUID transition=UUID.randomUUID();
            return admin.review(transition,pending,a.getUniqueId(),"QA accepted",Review.ACCEPT,evaluation.revision())
                .thenCompose(view->{check(view.profile().visibleScore()==-1,"Two -.35 contributions must round to -1");return admin.review(transition,pending,a.getUniqueId(),"QA accepted",Review.ACCEPT,evaluation.revision());});
        }).thenCompose(view->{
            check(statuses.get()==(old.isEmpty()?1:2)+additionalReviews,"Review event duplicated");return onMain(()->{papi("-1");return null;});
        }).thenCompose(ignored->admin.trust(a.getUniqueId()))
        .thenCompose(trust->onMain(()->new VoterFacts(true,true,a.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)/20L,Instant.ofEpochMilli(a.getFirstPlayed()),true,true,true))
            .thenCompose(facts->admin.refresh(a.getUniqueId(),trust.revision(),facts).thenCompose(updated->{check(updated,"Fresh Trust CAS rejected");return admin.refresh(a.getUniqueId(),trust.revision(),facts);})).thenApply(stale->{check(!stale,"Stale Trust CAS overwrote new state");return null;}))
        .thenCompose(ignored->voting.submit(revoke(a,"withdraw")))
        .thenCompose(receipt->{check(receipt.currentView().profile().visibleScore()==0,"Revoke exact delta failed");return onMain(()->{a.performCommand("like Phase3Target helpful native positive");return null;});})
        .thenCompose(ignored->waitHistory(baseline+5))
        .thenCompose(ignored->onMain(()->{a.performCommand("dislike Phase3Target rude native pending");return null;}))
        .thenCompose(ignored->waitHistory(baseline+6))
        .thenCompose(page->{pending=page.entries().getFirst().entry().operationId();return voting.outcome(pending);})
        .thenCompose(outcome->{check(outcome.status()==VoteProcessingOutcome.Status.PENDING,"Native command bypassed pending pipeline");return onMain(()->{a.performCommand("rep gui Phase3Target");return null;});})
        .thenCompose(ignored->poll(()->onMain(()->a.getOpenInventory().getTopInventory().getHolder()!=null&&a.getOpenInventory().getTopInventory().getHolder().getClass().getName().endsWith("VotingGui$Session")&&a.getOpenInventory().getTopInventory().getItem(38)!=null),Boolean::booleanValue,0))
        .thenCompose(ignored->admin.trust(a.getUniqueId()))
        .thenCompose(trust->onMain(()->{
            papi("0");check(commits.get()==6+cleanup,"Fresh durable event count mismatch");
            old.setProperty("entries",Long.toString(baseline+6));old.setProperty("pending",pending.toString());old.setProperty("trustRevision",Long.toString(trust.revision()));
            Files.createDirectories(file.getParent());try(var output=Files.newOutputStream(file)) {old.store(output,"Private QA restart assertions");}
            return null;
        })).whenComplete((ignored,error)->onMain(()->{
            try {committed.close();changed.close();if(error!=null){failed.accept(error);return null;}
                plugin.getLogger().info("PROBE_PROTECTION_PASS: actual Paper facts, private Trust, CAS, six fractional votes, 12 retries, pending replacement, accepted review, idempotent status event, native commands, GUI, PAPI privacy, restart state");success.run();
            }catch(Throwable failure){failed.accept(failure);}return null;
        }));
    }
    private VoteRequest request(Player voter,VoteType type,String comment) {return new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target,VoteRequest.Action.SUBMIT,type,type==VoteType.POSITIVE?"helpful":"rude",comment);}
    private VoteRequest revoke(Player voter,String comment) {return new VoteRequest(UUID.randomUUID(),voter.getUniqueId(),target,VoteRequest.Action.REVOKE,null,"",comment);}
    private void papi(String score) throws Exception {
        var papi=plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");if(papi==null||!papi.isEnabled())return;
        var manager=papi.getClass().getMethod("getLocalExpansionManager").invoke(papi);var expansion=manager.getClass().getMethod("getExpansion",String.class).invoke(manager,"dexturreputation");
        var request=expansion.getClass().getSuperclass().getMethod("onRequest",org.bukkit.OfflinePlayer.class,String.class);var player=plugin.getServer().getOfflinePlayer(target);
        check(score.equals(request.invoke(expansion,player,"score")),"PAPI exact accumulator display/cache mismatch");check(request.invoke(expansion,player,"trust")==null,"Trust leaked through PAPI");
    }
    private CompletionStage<VotePage> waitHistory(long count) {return poll(()->voting.history(target,VotePage.Direction.RECEIVED,1),page->page.totalEntries()==count,0);}
    private <T> CompletionStage<T> poll(Supplier<CompletionStage<T>> read,Predicate<T> ready,int attempt) {
        return read.get().thenCompose(value->{if(ready.test(value))return CompletableFuture.completedFuture(value);if(attempt>150)return CompletableFuture.failedFuture(new AssertionError("Protection runtime timed out"));
            var result=new CompletableFuture<T>();plugin.getServer().getScheduler().runTaskLater(plugin,()->poll(read,ready,attempt+1).whenComplete((next,error)->{if(error!=null)result.completeExceptionally(error);else result.complete(next);}),2L);return result;});
    }
    private <T> CompletionStage<T> onMain(Callable<T> action) {var result=new CompletableFuture<T>();plugin.getServer().getScheduler().runTask(plugin,()->{try{result.complete(action.call());}catch(Throwable error){result.completeExceptionally(error);}});return result;}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
