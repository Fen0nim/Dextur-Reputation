package org.dextur.reputation.storage;

import org.dextur.reputation.application.StorageType;
import org.dextur.reputation.domain.ProfileRepository;

/** Blocking internal port. Invoked only on managed IO workers or while the server is offline. */
public interface StorageBackend extends ProfileRepository, org.dextur.reputation.domain.VotingRepository, org.dextur.reputation.domain.protection.ProtectionRepository, AutoCloseable {
    StorageType type();
    StorageSnapshot snapshot() throws Exception;
    void prepareMigration() throws Exception;
    void importEmpty(StorageSnapshot snapshot) throws Exception;
    void finishMigration() throws Exception;
    @Override void close() throws Exception;
}
