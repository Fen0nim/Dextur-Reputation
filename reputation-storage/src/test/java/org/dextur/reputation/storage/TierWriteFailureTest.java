package org.dextur.reputation.storage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.protection.ProtectionData.Review;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;
class TierWriteFailureTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    @ParameterizedTest @EnumSource(JsonStorage.Point.class)
    void everyJsonCrashPointKeepsScoreAndTierInTheSameGeneration(JsonStorage.Point point) throws Exception {
        var fail=new AtomicBoolean();UUID target;Path json=directory.resolve("json");
        try(var backend=new JsonStorage(json,1048576,false,p->{if(fail.get()&&p==point)throw new java.io.IOException("Simulated failure");})) {
            target=TierContract.seed(backend,99);backend.tiers(TierConfiguration.load(directory));fail.set(true);
            assertThrows(Exception.class,()->backend.vote("global",vote(UUID.randomUUID(),target,VoteType.POSITIVE,"crash"),mature(),eligibility(),NOW));
        }
        try(var backend=new JsonStorage(json,1048576,false)) {
            var profile=backend.getOrCreate(target,"global");assertEquals(point==JsonStorage.Point.AFTER_RENAME?100:99,profile.visibleScore());assertEquals(point==JsonStorage.Point.AFTER_RENAME?"RESPECTED":"RECOGNIZED",profile.tier());
            assertEquals(profile.revision(),backend.tierState(target,"global").profileRevision());backend.snapshot();
        }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void failedPendingReviewCannotCommitTierOrPrivateStatusPartially(StorageType type) throws Exception {
        var fail=new AtomicBoolean();StorageBackend backend=type==StorageType.JSON?new JsonStorage(directory.resolve("json"),1048576,false,p->{if(fail.get()&&p==JsonStorage.Point.BEFORE_RENAME)throw new java.io.IOException("Simulated review failure");}):StorageFixtures.local(directory,type,false);
        StorageSnapshot before;
        try(backend) {
            backend.protection(rules(directory.resolve("rules"),true));backend.tiers(TierContract.small(directory));UUID target=UUID.randomUUID();backend.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"a"),young(),eligibility(),NOW);var pending=backend.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"pending"),young(),eligibility(),NOW.plusSeconds(1));before=backend.snapshot();
            if(type==StorageType.SQLITE)try(var c=((SqlStorage)backend).connect();var s=c.createStatement()){s.execute("CREATE TRIGGER tier_failure BEFORE UPDATE OF tier ON dr_profiles WHEN NEW.tier='SUSPICIOUS' BEGIN SELECT RAISE(FAIL,'simulated pending tier failure'); END");}
            fail.set(true);UUID id=pending.entry().operationId();long revision=backend.inspectVote(id).revision();assertThrows(Exception.class,()->backend.review(UUID.randomUUID(),id,UUID.randomUUID(),"QA rollback",Review.ACCEPT,revision,NOW.plusSeconds(2)));
            if(type==StorageType.SQLITE)assertEquals(before,backend.snapshot());
        }
        try(var reopened=StorageFixtures.local(directory,type,false)){assertEquals(before,reopened.snapshot());}
    }
}
