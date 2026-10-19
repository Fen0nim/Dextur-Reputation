package org.dextur.reputation.storage;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class LocalProtectionTest {
    @TempDir Path directory;
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void boundedClusterAnalysisIsAuditedAndSurvivesRestart(StorageType type) throws Exception {
        StorageSnapshot before;try(var backend=StorageFixtures.local(directory,type,false)) { ProtectionContract.clusters(backend,ProtectionFixtures.defaults(directory));before=backend.snapshot(); }
        try(var backend=StorageFixtures.local(directory,type,false)) { assertEquals(before,backend.snapshot()); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void repeatReciprocityAndWindowExpiryNeverFarm(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { ProtectionContract.repeated(backend,ProtectionFixtures.defaults(directory)); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void matureBurstAndImmatureAttacks(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { ProtectionContract.bursts(backend,ProtectionFixtures.defaults(directory)); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void historicalVoteCompatibility(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { ProtectionContract.historical(backend,ProtectionFixtures.rules(directory,false)); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void preciseFractionsAndRestart(StorageType type) throws Exception {
        var rules=ProtectionFixtures.rules(directory,false);StorageSnapshot state;
        try(var backend=StorageFixtures.local(directory,type,false)) { ProtectionContract.fractions(backend,rules);state=backend.snapshot(); }
        try(var backend=StorageFixtures.local(directory,type,false)) { assertEquals(state,backend.snapshot());backend.protection(rules);assertEquals(state,backend.snapshot()); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void pendingReviewAndConfirmedRestriction(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { ProtectionContract.pending(backend,ProtectionFixtures.rules(directory,true));backend.snapshot(); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void pendingReplacementRetainsOldContributionAndCannotApproveStaleIntent(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { ProtectionContract.replacementPending(backend,ProtectionFixtures.rules(directory,true));backend.snapshot(); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void concurrentDuplicateAndTrustCas(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { ProtectionContract.concurrent(backend,ProtectionFixtures.rules(directory,false)); }
    }
}
