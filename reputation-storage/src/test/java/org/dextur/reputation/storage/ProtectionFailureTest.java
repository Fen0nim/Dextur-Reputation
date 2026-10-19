package org.dextur.reputation.storage;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;

class ProtectionFailureTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    @Test void boundedAdmissionIncludesPaperFactsWaitAndReleasesCapacityAfterFailure() throws Exception {
        StorageFixtures.resources(directory);var defaults=Settings.load(directory);
        var settings=new Settings(defaults.scope(),defaults.defaultLanguage(),defaults.cacheSize(),defaults.cacheSeconds(),defaults.workers(),2,defaults.shutdownSeconds(),false,defaults.database(),defaults.storage());
        var backend=StorageFixtures.local(directory,StorageType.JSON,false);backend.protection(rules(directory.resolve("rules"),false));
        try(var app=new ReputationApplication(backend,settings,()->{try {backend.close();}catch(Exception e){throw new RuntimeException(e);}})) {
            var latch=new CountDownLatch(2);var fourFacts=new CountDownLatch(4);var gates=new ConcurrentHashMap<UUID,CompletableFuture<org.dextur.reputation.domain.VoterFacts>>();
            var voting=new VotingApplication(app,backend,settings,new VotingConfiguration(eligibility(),9,Map.of(),false,false),request->{
                var gate=new CompletableFuture<org.dextur.reputation.domain.VoterFacts>();gates.put(request.operationId(),gate);latch.countDown();fourFacts.countDown();return gate;
            },java.time.Clock.fixed(NOW,java.time.ZoneOffset.UTC),e->{});
            var first=voting.submit(vote(UUID.randomUUID(),UUID.randomUUID(),VoteType.POSITIVE,"waiting one")).toCompletableFuture();
            var second=voting.submit(vote(UUID.randomUUID(),UUID.randomUUID(),VoteType.POSITIVE,"waiting two")).toCompletableFuture();assertTrue(latch.await(5,TimeUnit.SECONDS));
            var request=vote(UUID.randomUUID(),UUID.randomUUID(),VoteType.POSITIVE,"backpressure");var error=assertThrows(CompletionException.class,()->voting.submit(request).toCompletableFuture().join());assertEquals(VoteRejectedException.Code.PROCESSING_BUSY,((VoteRejectedException)error.getCause()).code());
            assertTrue(first.cancel(true));var cancelled=assertThrows(CompletionException.class,()->voting.submit(request).toCompletableFuture().orTimeout(5,TimeUnit.SECONDS).join());assertInstanceOf(VoteRejectedException.class,cancelled.getCause());
            gates.values().forEach(gate->gate.completeExceptionally(new IllegalStateException("Paper stopped")));assertThrows(CancellationException.class,first::join);assertThrows(CompletionException.class,second::join);
            var third=voting.submit(request).toCompletableFuture();var fourth=voting.submit(vote(UUID.randomUUID(),UUID.randomUUID(),VoteType.POSITIVE,"capacity restored")).toCompletableFuture();
            assertTrue(fourFacts.await(5,TimeUnit.SECONDS),"Both admission slots must be reusable");assertFalse(third.isDone());assertFalse(fourth.isDone());
            gates.values().forEach(gate->gate.complete(mature()));assertFalse(third.get(5,TimeUnit.SECONDS).duplicate());assertFalse(fourth.get(5,TimeUnit.SECONDS).duplicate());
        }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void interruptedPrivateMigrationNeverActivatesAndBackupIsComplete(StorageType source) throws Exception {
        StorageFixtures.resources(directory);StorageType target=source==StorageType.JSON?StorageType.SQLITE:StorageType.JSON;
        Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(source));Files.writeString(directory.resolve("target.yml"),StorageFixtures.config(target));StorageSnapshot before;
        try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)) { ProtectionContract.pending(b,rules(directory.resolve("rules"),true));before=b.snapshot(); }
        assertThrows(Exception.class,()->new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of(),()->{throw new java.io.IOException("QA interrupted verification");}));
        assertEquals(source,Settings.load(directory).storage().type());assertThrows(Exception.class,()->StorageManager.create(directory,Settings.load(directory,directory.resolve("target.yml")),Map.of(),false));
        try(var paths=Files.list(directory.resolve("backups"))) { Path backup=paths.findFirst().orElseThrow();assertEquals(before,new SnapshotCodec().decode(Files.readAllBytes(backup.resolve("source.json"))).snapshot()); }
        try(var b=StorageManager.create(directory,Settings.load(directory),Map.of(),false)) { assertEquals(before,b.snapshot()); }
    }
    @Test void explicitJsonRecoveryRestoresWholePrivateSnapshot() throws Exception {
        StorageFixtures.resources(directory);var settings=Settings.load(directory);var rules=rules(directory.resolve("rules"),true);Path store=directory.resolve("storage/json");StorageSnapshot expected;
        try(var b=new JsonStorage(store,1048576,false)) {
            b.protection(rules);b.vote("global",vote(UUID.randomUUID(),UUID.randomUUID(),VoteType.POSITIVE,"before backup"),mature(),eligibility(),NOW);expected=b.snapshot();
            b.vote("global",vote(UUID.randomUUID(),UUID.randomUUID(),VoteType.POSITIVE,"last operation"),mature(),eligibility(),NOW.plusSeconds(1));
        }
        Files.writeString(store.resolve("state.json"),"broken");assertThrows(Exception.class,()->new JsonStorage(store,1048576,false));
        var quarantine=new StorageMigration().recoverJson(directory,settings,"backup");assertEquals("broken",Files.readString(quarantine.resolve("state.json")));
        try(var b=new JsonStorage(store,1048576,false)) { assertEquals(expected,b.snapshot()); }
    }
    @Test void sqlitePrivateIndexCorruptionBlocksStartup() throws Exception {
        try(var b=StorageFixtures.local(directory,StorageType.SQLITE,false)) { ProtectionContract.fractions(b,rules(directory,false)); }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("test.sqlite"));var s=c.createStatement()) {s.executeUpdate("UPDATE dr_trust SET revision=revision+1");}
        assertThrows(Exception.class,()->StorageFixtures.local(directory,StorageType.SQLITE,false));
    }
    @ParameterizedTest @EnumSource(JsonStorage.Point.class)
    void jsonCrashNeverPartiallyCommitsTrustPendingJournalAndProfile(JsonStorage.Point point) throws Exception {
        var rules=rules(directory,true);var armed=new AtomicBoolean();UUID target=UUID.randomUUID();var pending=vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"pending");StorageSnapshot before;
        try(var b=new JsonStorage(directory.resolve("json"),1048576,false,p->{if(armed.get()&&p==point) throw new java.io.IOException("QA disk fault");})) {
            b.protection(rules);b.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"first"),young(),eligibility(),NOW);before=b.snapshot();armed.set(true);
            assertThrows(Exception.class,()->b.vote("global",pending,young(),eligibility(),NOW.plusSeconds(1)));assertThrows(Exception.class,b::snapshot);
        }
        try(var b=new JsonStorage(directory.resolve("json"),1048576,false)) {
            var after=b.snapshot();if(point==JsonStorage.Point.AFTER_RENAME) {
                assertEquals(before.votes().size()+1,after.votes().size());assertEquals(Status.PENDING,b.inspectVote(pending.operationId()).status());assertNotNull(b.inspectTrust(pending.voterId(),"global"));
            } else assertEquals(before,after);
        }
    }
    @Test void sqliteFailureAfterPrivateWritesRollsBackBothVoteAndReview() throws Exception {
        var rules=rules(directory,true);Path file=directory.resolve("test.sqlite");
        try(var b=SqlStorage.sqlite(file,250,false);var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement()) {
            b.protection(rules);var target=UUID.randomUUID();b.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"first"),young(),eligibility(),NOW);
            var pending=b.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"pending"),young(),eligibility(),NOW.plusSeconds(1));var before=b.snapshot();
            s.execute("CREATE TRIGGER phase3_failure BEFORE UPDATE ON dr_profiles BEGIN SELECT RAISE(ABORT,'QA transaction rollback'); END");
            assertThrows(Exception.class,()->b.review(UUID.randomUUID(),pending.entry().operationId(),UUID.randomUUID(),"Review",Review.ACCEPT,1,NOW.plusSeconds(2)));assertEquals(before,b.snapshot());
            assertThrows(Exception.class,()->b.vote("global",vote(UUID.randomUUID(),target,VoteType.POSITIVE,"failure"),mature(),eligibility(),NOW.plusSeconds(3)));assertEquals(before,b.snapshot());
        }
    }
    @Test void jsonLimitRejectsBeforeCommitAndPreservesReadableState() throws Exception {
        var rules=rules(directory,false);Path store=directory.resolve("json");StorageSnapshot before;
        try(var b=new JsonStorage(store,1048576,false)) { ProtectionContract.fractions(b,rules);before=b.snapshot(); }
        int size=(int)Files.size(store.resolve("state.json"));
        try(var b=new JsonStorage(store,size+10,false)) {
            b.protection(rules);assertThrows(Exception.class,()->b.vote("global",vote(UUID.randomUUID(),UUID.randomUUID(),VoteType.POSITIVE,"too large"),mature(),eligibility(),NOW.plusSeconds(10)));assertEquals(before,b.snapshot());
        }
    }
    @Test void validChecksumCannotHideInconsistentPrivateCalculation() throws Exception {
        StorageSnapshot snapshot;try(var b=StorageFixtures.local(directory,StorageType.JSON,false)) { ProtectionContract.fractions(b,rules(directory,false));snapshot=b.snapshot(); }
        var codec=new SnapshotCodec();var doc=com.google.gson.JsonParser.parseString(new String(codec.encode(1,snapshot),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var protection=doc.getAsJsonObject("data").getAsJsonObject("protection");
        for(var a:protection.getAsJsonArray("assessments")) a.getAsJsonObject().getAsJsonObject("factors").addProperty("base",999999);
        for(var a:protection.getAsJsonArray("audit")) a.getAsJsonObject().getAsJsonObject("evidence").addProperty("base",999999);
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("3\n1\n"+doc.get("data")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));doc.addProperty("sha256",hash);
        assertThrows(Exception.class,()->codec.decode(doc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void committedEventsAndReviewEventsAreIdempotentAndNeverPrecedePersistence() throws Exception {
        StorageFixtures.resources(directory);var settings=Settings.load(directory);var rules=rules(directory,true);var armed=new AtomicBoolean();var backend=new JsonStorage(directory.resolve("json"),1048576,false,p->{if(armed.get()) throw new java.io.IOException("QA fault");});backend.protection(rules);
        try(var app=new ReputationApplication(backend,settings,()->{try {backend.close();}catch(Exception e){throw new RuntimeException(e);}})) {
            var voting=new VotingApplication(app,backend,settings,new VotingConfiguration(eligibility(),9,Map.of(),false,false),r->CompletableFuture.completedFuture(young()),java.time.Clock.fixed(NOW,java.time.ZoneOffset.UTC),e->{});
            var admin=new ProtectionAdministration(app,voting,backend,settings,rules,java.time.Clock.fixed(NOW.plusSeconds(5),java.time.ZoneOffset.UTC));
            var commits=new AtomicInteger();var reviews=new AtomicInteger();voting.onCommitted(e->{assertNotNull(assertDoesNotThrow(()->backend.operation(e.entry().operationId())));commits.incrementAndGet();});voting.onStatusChanged(e->{assertNotNull(assertDoesNotThrow(()->backend.inspectTransition(e.transitionId())));reviews.incrementAndGet();});
            var target=UUID.randomUUID();voting.submit(vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"first")).toCompletableFuture().join();var pending=voting.submit(vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"pending")).toCompletableFuture().join();
            var transition=UUID.randomUUID();var operator=UUID.randomUUID();admin.review(transition,pending.entry().operationId(),operator,"Accepted",Review.ACCEPT,1).toCompletableFuture().join();admin.review(transition,pending.entry().operationId(),operator,"Accepted",Review.ACCEPT,1).toCompletableFuture().join();assertEquals(1,reviews.get());assertEquals(2,commits.get());assertEquals(-1,app.cachedProfile(target).orElseThrow().visibleScore());
            armed.set(true);assertThrows(CompletionException.class,()->admin.review(UUID.randomUUID(),pending.entry().operationId(),operator,"Reject",Review.INVALIDATE,2).toCompletableFuture().join());assertEquals(1,reviews.get());assertEquals(-1,app.cachedProfile(target).orElseThrow().visibleScore());admin.stop();assertThrows(CompletionException.class,()->admin.initialize(UUID.randomUUID(),mature()).toCompletableFuture().join());
        }
    }
}
