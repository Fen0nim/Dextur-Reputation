package org.dextur.reputation.domain.recovery;

/** Blocking internal port, invoked on the existing bounded IO executor only. */
public interface RecoveryRepository {
    void recovery(RecoveryRules rules) throws Exception;
    RecoveryRules recoveryDefinition(java.util.UUID player,String scope) throws Exception;
    RecoveryData.Change recover(RecoveryData.Request request) throws Exception;
}
