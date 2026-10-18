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
    record VoteCommitted(VoteEntry entry, ReputationView view) { }
}
