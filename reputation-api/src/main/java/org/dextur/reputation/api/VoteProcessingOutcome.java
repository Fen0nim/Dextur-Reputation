package org.dextur.reputation.api;

import java.util.UUID;

/** Public processing state only. It deliberately contains no Trust score, weights or detector evidence. */
public record VoteProcessingOutcome(UUID operationId,Status status) {
    public enum Status { ACCEPTED,PENDING,SUPERSEDED,REVOKED,INVALIDATED }
}
