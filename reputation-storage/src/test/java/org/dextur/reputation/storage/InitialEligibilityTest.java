package org.dextur.reputation.storage;

import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.policy.InitialEligibility;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class InitialEligibilityTest {
    @TempDir Path directory;
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void onlyInitialEligibilityIsBypassed(StorageType type)throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)){InitialEligibilityContract.check(backend,directory);}
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void bypassAndAuditSurviveRestartAndMigration(StorageType type)throws Exception {
        StorageFixtures.resources(directory);Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(type));
        Files.writeString(directory.resolve("target.yml"),StorageFixtures.config(type==StorageType.JSON?StorageType.SQLITE:StorageType.JSON));
        StorageSnapshot expected;
        try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)) {
            InitialEligibilityContract.configure(b,directory);InitialEligibilityContract.force(b,InitialEligibilityContract.VOTER,true,InitialEligibilityContract.NOW);expected=b.snapshot();
        }
        try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){assertEquals(expected,b.snapshot());assertTrue(InitialEligibility.enabled(b.snapshot().administration().get(InitialEligibilityContract.VOTER,"global")));}
        new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of());
        try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)){assertEquals(expected,b.snapshot());assertTrue(InitialEligibility.enabled(b.snapshot().administration().get(InitialEligibilityContract.VOTER,"global")));}
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void bypassRetainsRealYoungAccountFactsAndAntiAbusePending(StorageType type)throws Exception {
        try(var b=StorageFixtures.local(directory,type,false)){
            InitialEligibilityContract.configure(b,directory);b.protection(ProtectionFixtures.rules(directory,true));
            UUID target=UUID.randomUUID(),a=UUID.randomUUID(),c=UUID.randomUUID();
            InitialEligibilityContract.force(b,a,true,InitialEligibilityContract.NOW);InitialEligibilityContract.force(b,c,true,InitialEligibilityContract.NOW);
            var rules=InitialEligibilityContract.rules(10,0,0,org.dextur.reputation.domain.VotingRules.RepeatPolicy.REPLACE);
            b.vote("global",ProtectionFixtures.vote(a,target,VoteType.NEGATIVE,"a"),ProtectionFixtures.young(),rules,InitialEligibilityContract.NOW);
            var second=b.vote("global",ProtectionFixtures.vote(c,target,VoteType.NEGATIVE,"b"),ProtectionFixtures.young(),rules,InitialEligibilityContract.NOW.plusSeconds(1));
            assertEquals(VoteProcessingOutcome.Status.PENDING,b.outcome(second.entry().operationId()).status());
            assertEquals(0,b.inspectTrust(c,"global").playtimeSeconds());
        }
    }
}
