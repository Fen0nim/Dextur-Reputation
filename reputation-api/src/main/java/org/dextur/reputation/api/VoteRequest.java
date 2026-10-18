package org.dextur.reputation.api;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Reuse operationId to retry an uncertain result; never generate a new ID for a network retry. */
public record VoteRequest(UUID operationId, UUID voterId, UUID targetId, Action action,
        VoteType type, String reason, String comment) {
    public enum Action { SUBMIT, REVOKE }
    public VoteRequest {
        Objects.requireNonNull(operationId); Objects.requireNonNull(voterId);
        Objects.requireNonNull(targetId); Objects.requireNonNull(action);
        reason = Objects.requireNonNull(reason).strip().toLowerCase(Locale.ROOT);
        comment = Objects.requireNonNull(comment).strip();
        if (action == Action.SUBMIT) Objects.requireNonNull(type);
        else if (type != null || !reason.isEmpty()) throw new IllegalArgumentException("Revoke has no type/reason");
    }
}
