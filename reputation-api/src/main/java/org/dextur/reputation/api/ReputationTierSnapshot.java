package org.dextur.reputation.api;
import java.util.*;
/** Immutable public display data. Strings use MiniMessage; plain names contain no tags. */
public record ReputationTierSnapshot(UUID playerId,String scopeId,String id,long score,long revision,long profileRevision,
        Map<String,String> localizedNames,String color,String prefix,String suffix,Long nextPositiveThreshold,int displayPriority) {
    public ReputationTierSnapshot { localizedNames=Map.copyOf(localizedNames); }
}
