package org.dextur.reputation.api;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Definitions describe policy outputs, not the existence of an auction/shop/chat implementation. */
public interface ReputationPolicyService {
    CompletionStage<ReputationPolicyResult> evaluate(UUID player,String policyId,Map<String,String> publicContext);
    default CompletionStage<ReputationPolicyResult> evaluate(UUID player,String policyId){return evaluate(player,policyId,Map.of());}
    Optional<ReputationPolicyResult> cached(UUID player,String policyId);
    Map<String,Metadata> policies();
    /** Registers integration metadata only; immutable administrator configuration remains authoritative. */
    AutoCloseable register(Metadata metadata);
    /** Declares an actual consumer adapter without changing any configured rule or security invariant. */
    AutoCloseable connect(String policyId,String providerId);
    AutoCloseable onChanged(Consumer<Changed> listener);
    record Metadata(String id,Map<String,String> names,String provider,boolean integrationActive) {
        public Metadata{Objects.requireNonNull(id);Objects.requireNonNull(provider);names=Map.copyOf(names);}
    }
    record Changed(UUID operationId,UUID playerId,String kind,ReputationPolicyResult.Revision revision) { }
}
