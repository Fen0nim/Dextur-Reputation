package org.dextur.reputation.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable public state. All timestamps are UTC instants; scores are integral. */
public record ReputationSnapshot(UUID playerId, String scopeId, long communityScore,
        long systemScore, long visibleScore, String tier, long revision,
        Instant createdAt, Instant updatedAt) {
    public ReputationSnapshot {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(scopeId);
        Objects.requireNonNull(tier);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }
}
