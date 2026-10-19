package org.dextur.reputation.api;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
/** Async IO; cached reads and formatting never query storage. Callbacks execute on IO workers. */
public interface TierService {
    CompletionStage<ReputationTierSnapshot> getTier(UUID playerId);
    Optional<ReputationTierSnapshot> cachedTier(UUID playerId);
    String format(UUID playerId,String language);
    AutoCloseable onTierChanged(Consumer<TierChanged> listener);
    record TierChanged(UUID transitionId,ReputationTierSnapshot tier,String previousId,boolean notifyPlayer) { }
}
