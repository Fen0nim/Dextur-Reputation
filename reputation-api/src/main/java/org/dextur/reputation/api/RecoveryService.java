package org.dextur.reputation.api;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Asynchronous local service. Providers are trusted server plugins, never player supplied identifiers. */
public interface RecoveryService {
    CompletionStage<RecoverySnapshot> status(UUID player);
    CompletionStage<RecoverySnapshot> start(UUID player);
    CompletionStage<RecoverySnapshot> cancel(UUID player);
    Optional<RecoverySnapshot> cached(UUID player);
    AutoCloseable onChanged(Consumer<Changed> listener);
    ProviderHandle registerProvider(ObjectiveProvider provider);
    /** Local post-commit extension, not a durable delivery queue. Reconcile missed effects using persisted reward IDs. */
    AutoCloseable registerRewardProvider(RewardProvider provider);
    interface RewardProvider {
        String id();
        int modelVersion();
        boolean available();
        /** Must return promptly and deduplicate by reward.operationId(). Cannot alter core reputation/Trust. */
        CompletionStage<Void> granted(RecoverySnapshot snapshot,RecoverySnapshot.Reward reward);
    }
    interface ObjectiveProvider {
        String id();
        int modelVersion();
        boolean available();
        void validate(String objectiveId,Map<String,String> configuration);
        default void cancelled(UUID enrollmentId) { }
    }
    interface ProviderHandle extends AutoCloseable {
        /** Absolute progress for one objective, monotonic and bounded by the configured target. */
        CompletionStage<RecoverySnapshot> submit(UUID operationId,UUID player,UUID enrollmentId,String objectiveId,long absoluteProgress);
        @Override void close();
    }
    enum Kind { PROGRAM_STARTED,STAGE_COMPLETED,PROGRESS_CHANGED,REWARD_GRANTED,PROGRAM_PAUSED,PROGRAM_COMPLETED,PROGRAM_CANCELLED,PROGRAM_EXPIRED,PROGRAM_RESUMED,ADMINISTRATIVE_LOCK_CHANGED }
    record Changed(UUID operationId,Kind kind,RecoverySnapshot snapshot,String stageId) { }
}
