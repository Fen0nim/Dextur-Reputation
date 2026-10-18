package org.dextur.reputation.domain;

import java.util.UUID;
import org.dextur.reputation.api.ReputationSnapshot;

/** Blocking persistence port. Only application database workers may invoke this port. */
public interface ProfileRepository {
    ReputationSnapshot getOrCreate(UUID playerId, String scopeId) throws Exception;
    PlayerLanguage language(UUID playerId, PlayerLanguage defaultLanguage) throws Exception;
    void setLanguage(UUID playerId, PlayerLanguage language) throws Exception;
}
