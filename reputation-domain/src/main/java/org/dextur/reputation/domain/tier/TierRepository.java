package org.dextur.reputation.domain.tier;
import java.util.*;
/** Internal blocking persistence port. */
public interface TierRepository {
    void tiers(TierRules rules) throws Exception;
    TierState tierState(UUID player,String scope) throws Exception;
    default List<TierState> pendingTierSync(String scope,int limit) throws Exception {return pendingTierSyncAfter(scope,"",limit);}
    List<TierState> pendingTierSyncAfter(String scope,String after,int limit) throws Exception;
    boolean tierSynced(UUID player,String scope,long expected,List<String> owned) throws Exception;
}
