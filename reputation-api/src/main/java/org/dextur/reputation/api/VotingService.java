package org.dextur.reputation.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** All operations are asynchronous; callbacks are not guaranteed to execute on the Paper thread. */
public interface VotingService {
    CompletionStage<VoteReceipt> submit(VoteRequest request);
    CompletionStage<ReputationView> view(UUID playerId);
    CompletionStage<VotePage> history(UUID playerId, VotePage.Direction direction, int page);
    /** Listener called once after a new durable commit, never for duplicate/rejected operations. Close to unsubscribe. */
    AutoCloseable onCommitted(Consumer<VoteCommitted> listener);
    default CompletionStage<VoteProcessingOutcome> outcome(UUID operationId) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Processing outcomes unavailable"));
    }
    default AutoCloseable onStatusChanged(Consumer<VoteStatusChanged> listener) { return () -> { }; }
    record VoteStatusChanged(UUID transitionId, UUID operationId, VoteProcessingOutcome.Status status, ReputationView view) { }
    record VoteCommitted(VoteEntry entry, ReputationView view) { }
}
