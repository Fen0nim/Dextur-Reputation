package org.dextur.reputation.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable journal event. Use outcome/history for current status and activity; use view for precise accumulated totals. */
public record VoteEntry(UUID operationId, long sequence, String scope, UUID voterId, UUID targetId,
        Action action, VoteType type, String reason, String comment, Instant createdAt,
        UUID previousOperationId, long baseWeight, long effectiveWeight, int calculationVersion,
        String requestHash, long communityBefore, long communityAfter) {
    public enum Action { CAST, REPLACE, REVOKE }
    public VoteEntry {
        Objects.requireNonNull(operationId); Objects.requireNonNull(voterId); Objects.requireNonNull(targetId);
        Objects.requireNonNull(action); Objects.requireNonNull(type); Objects.requireNonNull(comment);
        Objects.requireNonNull(createdAt);
        if (sequence < 1 || !scope.matches("[a-z0-9_-]{1,32}") || voterId.equals(targetId)
                || !reason.matches("[a-z0-9_-]{1,32}") || baseWeight < 1 || effectiveWeight < 0
                || calculationVersion < 1 || !requestHash.matches("[0-9a-f]{64}")
                || (action == Action.REVOKE && effectiveWeight != 0) || (calculationVersion == 1 && action != Action.REVOKE && effectiveWeight == 0)) throw new IllegalArgumentException("Invalid vote entry");
    }
    public long contribution() { return Math.multiplyExact(type.sign(), effectiveWeight); }
    public String pairKey() { return scope + "/" + voterId + "/" + targetId; }
}
