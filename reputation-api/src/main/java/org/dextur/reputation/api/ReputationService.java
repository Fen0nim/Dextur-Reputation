package org.dextur.reputation.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Thread-safe asynchronous access. Failures complete the stage exceptionally. */
public interface ReputationService {
    /** Creates a zero profile if absent. Never resolves player names or exposes Trust. */
    CompletionStage<ReputationSnapshot> getProfile(UUID playerId);
}
