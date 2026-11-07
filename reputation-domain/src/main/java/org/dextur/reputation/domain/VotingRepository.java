package org.dextur.reputation.domain;

import java.time.Instant;
import java.util.UUID;
import org.dextur.reputation.api.*;

/** Blocking transactional port; all implementations serialize read-check-write against the same authority. */
public interface VotingRepository {
    VoteEntry operation(UUID operationId) throws Exception;
    VoteReceipt vote(String scope, VoteRequest request, VoterFacts facts, VotingRules rules, Instant now) throws Exception;
    ReputationView votingView(UUID player, String scope) throws Exception;
    VotePage votingHistory(UUID player, String scope, VotePage.Direction direction, int page, int size) throws Exception;
    default ReasonHistory.Page reasonHistory(UUID player,String scope,ReasonHistory.Query query)throws Exception {
        throw new UnsupportedOperationException("Reason history unavailable");
    }
}
