package org.dextur.reputation.domain.protection;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;

/** Internal administrative port. Never registered as a public Bukkit service. */
public interface ProtectionRepository {
    void protection(ProtectionRules rules) throws Exception;
    VoteProcessingOutcome outcome(UUID operation) throws Exception;
    Trust inspectTrust(UUID player,String scope) throws Exception;
    Audit inspectTransition(UUID operation) throws Exception;
    Assessment inspectVote(UUID operation) throws Exception;
    List<Audit> audit(String scope,long afterSequence,int limit) throws Exception;
    ReputationView review(UUID transition,UUID vote,UUID operator,String reason,Review action,long expectedRevision,Instant now) throws Exception;
    boolean refreshTrust(UUID player,String scope,long expectedRevision,org.dextur.reputation.domain.VoterFacts facts,Instant now) throws Exception;
    List<Trust> dueTrust(String scope,Instant before,int limit) throws Exception;
    boolean analyze(String scope,Instant now) throws Exception;
    long storageBytes() throws Exception;
}
