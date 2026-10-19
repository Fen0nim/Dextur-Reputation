package org.dextur.reputation.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;

/** Uses the foundation's bounded executor, cache locks and shutdown lifecycle. */
public final class VotingApplication implements VotingService {
    @FunctionalInterface public interface FactsProvider { CompletionStage<VoterFacts> facts(VoteRequest request); }
    private final ReputationApplication application;
    private final VotingRepository repository;
    private final Settings settings;
    private final VotingConfiguration configuration;
    private final FactsProvider facts;
    private final Clock clock;
    private final Consumer<Throwable> listenerFailure;
    private final Semaphore inFlight;
    private final CopyOnWriteArrayList<Consumer<VoteStatusChanged>> statusListeners=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<VoteCommitted>> listeners=new CopyOnWriteArrayList<>();
    public VotingApplication(ReputationApplication application,VotingRepository repository,Settings settings,
            VotingConfiguration configuration,FactsProvider facts,Clock clock,Consumer<Throwable> listenerFailure) {
        this.application=application;this.repository=repository;this.settings=settings;this.configuration=configuration;
        this.facts=facts;this.clock=clock;this.listenerFailure=listenerFailure;
        this.inFlight=new Semaphore(settings.queueSize());
    }
    @Override public CompletionStage<VoteReceipt> submit(VoteRequest request) {
        Objects.requireNonNull(request);
        // Includes requests awaiting the Paper facts stage, outside the IO executor queue.
        if(!inFlight.tryAcquire()) return CompletableFuture.failedFuture(new VoteRejectedException(VoteRejectedException.Code.PROCESSING_BUSY));
        var result=new CompletableFuture<VoteReceipt>();
        // Cancelling the caller's future must neither cancel accepted durable work nor release
        // its slot while it still waits for Paper facts. The internal completion owns the slot.
        try { process(request).whenComplete((receipt,error)->{
            inFlight.release();if(error==null) result.complete(receipt);else result.completeExceptionally(error);
        }); }
        catch(Throwable error) { inFlight.release();result.completeExceptionally(error); }
        return result;
    }
    private CompletionStage<VoteReceipt> process(VoteRequest request) {
        // A durable duplicate can be acknowledged after disconnect or a configuration change.
        return application.submit(()->repository.operation(request.operationId())).thenCompose(existing->{
            if(existing!=null) {
                VotingEngine.checkDuplicate(settings.scope(),request,existing);
                return view(request.targetId()).thenApply(view->new VoteReceipt(existing,true,view));
            }
            return facts.facts(request).thenCompose(trusted->application.submit(()->{
                VoteReceipt receipt=application.withProfiles(request.voterId(),request.targetId(),()->{
                    var result=repository.vote(settings.scope(),request,trusted,configuration.rules(),clock.instant().truncatedTo(ChronoUnit.MICROS));
                    application.updateProfile(result.currentView().profile(),!result.duplicate());return result;
                });
                if(!receipt.duplicate()) {
                    var event=new VoteCommitted(receipt.entry(),receipt.currentView());
                    for(var listener:listeners) try { listener.accept(event); } catch(Throwable error) {
                        try { listenerFailure.accept(error); } catch(RuntimeException ignored) { /* Diagnostics must not alter a committed result. */ }
                    }
                }
                return receipt;
            }));
        });
    }
    @Override public CompletionStage<ReputationView> view(UUID playerId) {
        Objects.requireNonNull(playerId);
        return application.submit(()->application.withProfiles(playerId,playerId,()->{
            var view=repository.votingView(playerId,settings.scope());application.updateProfile(view.profile(),false);return view;
        }));
    }
    @Override public CompletionStage<VotePage> history(UUID playerId,VotePage.Direction direction,int page) {
        Objects.requireNonNull(playerId);Objects.requireNonNull(direction);
        return application.submit(()->repository.votingHistory(playerId,settings.scope(),direction,page,configuration.pageSize()));
    }
    @Override public CompletionStage<VoteProcessingOutcome> outcome(UUID id) {
        if(!(repository instanceof org.dextur.reputation.domain.protection.ProtectionRepository protection)) return VotingService.super.outcome(id);
        return application.submit(()->protection.outcome(id));
    }
    @Override public AutoCloseable onStatusChanged(Consumer<VoteStatusChanged> listener) { Objects.requireNonNull(listener);statusListeners.add(listener);return ()->statusListeners.remove(listener); }
    void statusCommitted(VoteStatusChanged event) {
        for(var listener:statusListeners) try { listener.accept(event); } catch(Throwable error) { try { listenerFailure.accept(error); } catch(RuntimeException ignored) { } }
    }
    @Override public AutoCloseable onCommitted(Consumer<VoteCommitted> listener) {
        Objects.requireNonNull(listener);listeners.add(listener);return ()->listeners.remove(listener);
    }
}
