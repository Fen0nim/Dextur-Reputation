package org.dextur.reputation.storage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.dextur.reputation.application.*;
import org.dextur.reputation.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;
class LocalTierTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"}) void positiveHysteresisSurvivesRestartAndPolicyReconciliation(StorageType type) throws Exception {restart(type,false);}
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"}) void negativeHysteresisSurvivesRestartAndPolicyReconciliation(StorageType type) throws Exception {restart(type,true);}
    private void restart(StorageType type,boolean negative) throws Exception {
        UUID target;StorageSnapshot before;var rules=TierConfiguration.load(directory);
        try(var b=StorageFixtures.local(directory,type,false)) {target=TierContract.hysteresis(b,directory,negative);before=b.snapshot();}
        try(var b=StorageFixtures.local(directory,type,false)) {
            assertEquals(before,b.snapshot());b.tiers(rules);assertEquals(before,b.snapshot());assertEquals(negative?"OFFENDER":"RESPECTED",b.getOrCreate(target,"global").tier());
            var changed=new org.dextur.reputation.domain.tier.TierRules(rules.definitions(),-24,24,"a".repeat(64));var protection=before.protection();b.tiers(changed);assertEquals(negative?"SUSPICIOUS":"RECOGNIZED",b.getOrCreate(target,"global").tier());assertEquals(protection,b.snapshot().protection());assertEquals(before.votes(),b.snapshot().votes());
        }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"}) void pendingApprovalAndInvalidationChangeTierOnlyOnCommit(StorageType type) throws Exception {try(var b=StorageFixtures.local(directory,type,false)){TierContract.pending(b,directory);}}
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"}) void concurrentVotesProduceOneOrderedTransition(StorageType type) throws Exception {try(var b=StorageFixtures.local(directory,type,false)){TierContract.concurrent(b,directory);}}
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"}) void syncOwnershipAndCasAreDurable(StorageType type) throws Exception {
        UUID id;StorageSnapshot before;try(var b=StorageFixtures.local(directory,type,false)) {id=TierContract.hysteresis(b,directory,false);var state=b.tierState(id,"global");assertTrue(b.tierSynced(id,"global",state.syncRevision(),List.of("reputation_owned")));assertFalse(b.tierSynced(id,"global",state.syncRevision()-1,List.of()));assertTrue(b.pendingTierSync("global",100).stream().noneMatch(s->s.player().equals(id)));before=b.snapshot();}
        try(var b=StorageFixtures.local(directory,type,false)){assertEquals(before,b.snapshot());assertEquals(List.of("reputation_owned"),b.tierState(id,"global").ownedGroups());}
    }
    @Test void jsonFaultCannotCommitScoreWithoutTier() throws Exception {
        var fail=new AtomicBoolean();Path json=directory.resolve("json");UUID target;byte[] before;
        try(var b=new JsonStorage(json,1048576,false,point->{if(fail.get()&&point==JsonStorage.Point.BEFORE_RENAME)throw new java.io.IOException("Simulated tier failure");})) {
            target=TierContract.seed(b,99);b.tiers(TierConfiguration.load(directory));before=Files.readAllBytes(json.resolve("state.json"));fail.set(true);
            assertThrows(Exception.class,()->b.vote("global",vote(UUID.randomUUID(),target,VoteType.POSITIVE,"failure"),mature(),eligibility(),NOW));assertArrayEquals(before,Files.readAllBytes(json.resolve("state.json")));
        }
        try(var b=new JsonStorage(json,1048576,false)){assertEquals(99,b.getOrCreate(target,"global").visibleScore());assertEquals("RECOGNIZED",b.getOrCreate(target,"global").tier());}
    }
    @Test void sqliteTierUpdateFailureRollsBackJournalAndScore() throws Exception {
        try(var b=SqlStorage.sqlite(directory.resolve("test.sqlite"),250,false)){UUID target=TierContract.seed(b,99);b.tiers(TierConfiguration.load(directory));var before=b.snapshot();
            try(var c=b.connect();var s=c.createStatement()){s.execute("CREATE TRIGGER tier_failure BEFORE UPDATE OF tier ON dr_profiles WHEN NEW.tier='RESPECTED' BEGIN SELECT RAISE(FAIL,'simulated tier failure'); END");}
            assertThrows(Exception.class,()->b.vote("global",vote(UUID.randomUUID(),target,VoteType.POSITIVE,"failure"),mature(),eligibility(),NOW));assertEquals(before,b.snapshot());
        }
    }
    @Test void checksumsRejectCorruptedTierRecords() throws Exception {
        try(var b=SqlStorage.sqlite(directory.resolve("test.sqlite"),250,false)){TierContract.hysteresis(b,directory,false);try(var c=b.connect();var s=c.createStatement()){s.executeUpdate("UPDATE dr_tiers SET revision=revision+1");}}
        assertThrows(Exception.class,()->SqlStorage.sqlite(directory.resolve("test.sqlite"),250,false));
        try(var b=new JsonStorage(directory.resolve("json"),1048576,false)){TierContract.hysteresis(b,directory,false);}
        var path=directory.resolve("json/state.json");Files.writeString(path,Files.readString(path).replace("RESPECTED","SABOTEUR"));assertThrows(Exception.class,()->new JsonStorage(directory.resolve("json"),1048576,false));
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"}) void reconciliationCrossesBatchBoundariesAndKeepsEveryScope(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)){TierContract.scale(backend,directory);}
    }

}
