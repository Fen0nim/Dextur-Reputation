package org.dextur.reputation.storage;

import org.dextur.reputation.application.StorageType;
import org.dextur.reputation.domain.ProfileRepository;

/** Blocking internal port. Invoked only on managed IO workers or while the server is offline. */
public interface StorageBackend extends org.dextur.reputation.domain.investigation.InvestigationRepository, org.dextur.reputation.domain.policy.PolicyRepository, org.dextur.reputation.domain.recovery.RecoveryRepository, org.dextur.reputation.domain.tier.TierRepository, ProfileRepository, org.dextur.reputation.domain.VotingRepository, org.dextur.reputation.domain.protection.ProtectionRepository, AutoCloseable {
    StorageType type();
    StorageSnapshot snapshot() throws Exception;
    void prepareMigration() throws Exception;
    void importEmpty(StorageSnapshot snapshot) throws Exception;
    void finishMigration() throws Exception;
    @Override void close() throws Exception;
}
