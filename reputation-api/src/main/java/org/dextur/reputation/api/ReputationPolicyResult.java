package org.dextur.reputation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Public immutable policy projection. Contains no confidential anti-abuse inputs. */
public record ReputationPolicyResult(UUID playerId,String policyId,Decision decision,long reputation,String tier,
        String requirement,Long limit,BigDecimal multiplier,BigDecimal value,String reasonKey,
        Revision revision,Instant evaluatedAt,boolean integrationActive) {
    public enum Decision { ALLOW,DENY,LIMITED,UNAVAILABLE }
    public record Revision(long profile,long administration,long recovery,String configuration) { }
    public boolean permitted(){return decision==Decision.ALLOW||decision==Decision.LIMITED;}
}
