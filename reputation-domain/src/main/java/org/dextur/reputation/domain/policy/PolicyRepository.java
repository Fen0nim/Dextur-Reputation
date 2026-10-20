package org.dextur.reputation.domain.policy;
import java.util.UUID;
public interface PolicyRepository {
    void features(PolicyRules rules)throws Exception;
    PolicyEngine.Input policyInput(UUID player,String scope)throws Exception;
    AdministrationData.Change administer(AdministrationData.Request request)throws Exception;
}
