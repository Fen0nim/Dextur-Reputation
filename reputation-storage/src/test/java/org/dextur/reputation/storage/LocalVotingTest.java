package org.dextur.reputation.storage;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class LocalVotingTest {
    @TempDir Path directory;
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void votingLifecycleAndRestart(StorageType type) throws Exception {
        StorageSnapshot before;
        try(var backend=StorageFixtures.local(directory,type,false)) { VotingContract.lifecycle(backend);before=backend.snapshot(); }
        try(var backend=StorageFixtures.local(directory,type,false)) {
            assertEquals(before,backend.snapshot());var e=before.votes().getFirst();
            var request=new VoteRequest(e.operationId(),e.voterId(),e.targetId(),VoteRequest.Action.SUBMIT,e.type(),e.reason(),e.comment());
            assertTrue(backend.vote(e.scope(),request,null,VotingContract.rules(1,10000),VotingContract.NOW).duplicate());
        }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void concurrentDuplicatesAndIndependentVoters(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { VotingContract.concurrent(backend);backend.snapshot(); }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void durableCooldownQuotaAndReplacementCannotFarm(StorageType type) throws Exception {
        try(var backend=StorageFixtures.local(directory,type,false)) { VotingContract.limits(backend); }
    }
    @ParameterizedTest @EnumSource(JsonStorage.Point.class)
    void jsonFaultProtectsVoteAndProfileAsOneCommit(JsonStorage.Point point) throws Exception {
        UUID voter=UUID.randomUUID(),target=UUID.randomUUID();var request=VotingContract.request(voter,target,VoteType.POSITIVE,"failure");
        try(var backend=new JsonStorage(directory,1048576,false,p->{if(p==point) throw new java.io.IOException("simulated disk failure");})) {
            assertThrows(Exception.class,()->backend.vote("global",request,VotingContract.facts(),VotingContract.rules(10,0),VotingContract.NOW));
            assertThrows(Exception.class,backend::snapshot);
        }
        try(var backend=new JsonStorage(directory,1048576,false)) {
            boolean committed=point==JsonStorage.Point.AFTER_RENAME;var state=backend.snapshot();
            assertEquals(committed?1:0,state.votes().size());assertEquals(committed?2:0,state.profiles().size());
            var receipt=backend.vote("global",request,VotingContract.facts(),VotingContract.rules(10,0),VotingContract.NOW);
            assertEquals(committed,receipt.duplicate());assertEquals(1,receipt.currentView().profile().visibleScore());assertEquals(1,backend.snapshot().votes().size());
        }
    }
    @Test void applicationPublishesOnlyFreshDurableEventsAndUpdatesFoundationCache() throws Exception {
        StorageFixtures.resources(directory);Settings settings=Settings.load(directory);var backend=StorageFixtures.local(directory,StorageType.JSON,false);
        var configuration=new VotingConfiguration(VotingContract.rules(100,0),9,Map.of(),false,false);
        java.util.concurrent.atomic.AtomicInteger events=new java.util.concurrent.atomic.AtomicInteger(),facts=new java.util.concurrent.atomic.AtomicInteger();
        try(var app=new ReputationApplication(backend,settings,()->{try { backend.close(); }catch(Exception e) { throw new RuntimeException(e); }})) {
            var voting=new VotingApplication(app,backend,settings,configuration,request->{facts.incrementAndGet();return java.util.concurrent.CompletableFuture.completedFuture(VotingContract.facts());},java.time.Clock.fixed(VotingContract.NOW,java.time.ZoneOffset.UTC),error->{});
            UUID voter=UUID.randomUUID(),target=UUID.randomUUID();assertEquals(0,app.getProfile(target).toCompletableFuture().join().visibleScore());
            var subscription=voting.onCommitted(event->events.incrementAndGet());
            var badListener=voting.onCommitted(event->{throw new IllegalStateException("listener failure");});
            var request=VotingContract.request(voter,target,VoteType.POSITIVE,"service");
            try(var workers=java.util.concurrent.Executors.newFixedThreadPool(8)) {
                var calls=new ArrayList<java.util.concurrent.Callable<VoteReceipt>>();for(int i=0;i<16;i++) calls.add(()->voting.submit(request).toCompletableFuture().join());
                int fresh=0;for(var result:workers.invokeAll(calls)) if(!result.get().duplicate()) fresh++;assertEquals(1,fresh);
            }
            assertEquals(1,events.get());assertEquals(1,app.getProfile(target).toCompletableFuture().join().visibleScore());assertEquals(1,app.cachedProfile(target).orElseThrow().visibleScore());
            int queried=facts.get();assertTrue(voting.submit(request).toCompletableFuture().join().duplicate());assertEquals(queried,facts.get());
            var rejected=VotingContract.request(voter,target,VoteType.POSITIVE,"service");assertThrows(java.util.concurrent.CompletionException.class,()->voting.submit(rejected).toCompletableFuture().join());assertEquals(1,events.get());
            subscription.close();badListener.close();voting.submit(VotingContract.request(voter,target,VoteType.NEGATIVE,"changed")).toCompletableFuture().join();assertEquals(1,events.get());assertEquals(-1,app.cachedProfile(target).orElseThrow().visibleScore());
        }
    }
    @Test void serviceWriteFailureNeverPublishesEventOrChangesCachedProfile() throws Exception {
        StorageFixtures.resources(directory);Settings settings=Settings.load(directory);UUID target=UUID.randomUUID();
        var failing=new java.util.concurrent.atomic.AtomicBoolean();var backend=new JsonStorage(directory.resolve("json"),1048576,false,p->{if(failing.get()) throw new java.io.IOException("fault");});
        try(var app=new ReputationApplication(backend,settings,()->{try { backend.close(); }catch(Exception e) { throw new RuntimeException(e); }})) {
            var voting=new VotingApplication(app,backend,settings,new VotingConfiguration(VotingContract.rules(10,0),9,Map.of(),false,false),r->java.util.concurrent.CompletableFuture.completedFuture(VotingContract.facts()),java.time.Clock.fixed(VotingContract.NOW,java.time.ZoneOffset.UTC),error->{});
            java.util.concurrent.atomic.AtomicInteger events=new java.util.concurrent.atomic.AtomicInteger();voting.onCommitted(event->events.incrementAndGet());
            app.getProfile(target).toCompletableFuture().join();failing.set(true);
            assertThrows(java.util.concurrent.CompletionException.class,()->voting.submit(VotingContract.request(UUID.randomUUID(),target,VoteType.POSITIVE,"failure")).toCompletableFuture().join());
            assertEquals(0,events.get());assertEquals(0,app.cachedProfile(target).orElseThrow().visibleScore());
        }
    }
    @Test void sqliteFailureAfterJournalInsertRollsBackEverything() throws Exception {
        Path file=directory.resolve("db.sqlite");UUID voter=UUID.randomUUID(),target=UUID.randomUUID();var request=VotingContract.request(voter,target,VoteType.POSITIVE,"");
        try(var backend=SqlStorage.sqlite(file,250,false);var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement()) {
            s.execute("CREATE TRIGGER reject_score BEFORE UPDATE ON dr_profiles BEGIN SELECT RAISE(ABORT,'simulated failure'); END");
            assertThrows(SQLException.class,()->backend.vote("global",request,VotingContract.facts(),VotingContract.rules(10,0),VotingContract.NOW));
            assertTrue(backend.snapshot().isEmpty());s.execute("DROP TRIGGER reject_score");
            assertFalse(backend.vote("global",request,VotingContract.facts(),VotingContract.rules(10,0),VotingContract.NOW).duplicate());
        }
    }
    @Test void sqliteCorruptedActiveIndexFailsStartup() throws Exception {
        Path file=directory.resolve("db.sqlite");
        try(var backend=SqlStorage.sqlite(file,250,false)) { VotingContract.lifecycle(backend); }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement()) { s.executeUpdate("DELETE FROM dr_active_votes"); }
        assertThrows(SQLException.class,()->SqlStorage.sqlite(file,250,false));
    }
    @Test void recomputedChecksumCannotHideInconsistentVoteProfileOrRequestHash() throws Exception {
        StorageSnapshot state;try(var backend=StorageFixtures.local(directory,StorageType.JSON,false)) { VotingContract.lifecycle(backend);state=backend.snapshot(); }
        var codec=new SnapshotCodec();
        for(boolean profile:new boolean[]{true,false}) {
            var doc=com.google.gson.JsonParser.parseString(new String(codec.encode(1,state),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if(profile) {
                String target=state.votes().getFirst().targetId().toString();
                for(var row:doc.getAsJsonObject("data").getAsJsonArray("profiles")) if(row.getAsJsonObject().get("playerId").getAsString().equals(target)) row.getAsJsonObject().addProperty("community",999);
            } else doc.getAsJsonObject("data").getAsJsonArray("votes").get(0).getAsJsonObject().addProperty("comment","tampered");
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("2\n1\n"+doc.get("data")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));doc.addProperty("sha256",hash);
            assertThrows(java.io.IOException.class,()->codec.decode(doc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
    @Test void phaseOneSqliteSchemaUpgradesWithoutChangingExistingData() throws Exception {
        Path file=directory.resolve("legacy.sqlite");
        try(var backend=SqlStorage.sqlite(file,250,true)) { backend.prepareMigration();backend.importEmpty(StorageFixtures.sample());backend.finishMigration(); }
        // Reconstruct the exact Phase 1.1 tables and version, with nonzero scores/languages.
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement()) {
            s.execute("DROP TABLE dr_active_votes");s.execute("DROP TABLE dr_votes");s.execute("PRAGMA user_version=1");
        }
        try(var backend=SqlStorage.sqlite(file,250,false)) { assertEquals(StorageFixtures.sample(),backend.snapshot());VotingContract.lifecycle(backend); }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement();var row=s.executeQuery("PRAGMA user_version")) { assertTrue(row.next());assertEquals(2,row.getInt(1)); }
    }
    @Test void phaseOneJsonFormatUpgradesWithoutLosingProfilesOrLocales() throws Exception {
        // Exact canonical Phase 1.1 encoder, with its original checksum input.
        var state=StorageFixtures.sample();var codec=new SnapshotCodec();
        var doc=com.google.gson.JsonParser.parseString(new String(codec.encode(7,state),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        doc.addProperty("formatVersion",1);doc.getAsJsonObject("data").remove("votes");
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("1\n7\n"+doc.get("data")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));doc.addProperty("sha256",hash);
        Files.writeString(directory.resolve("state.json"),doc.toString());
        try(var backend=new JsonStorage(directory,1048576,false)) {
            assertEquals(state,backend.snapshot());VotingContract.lifecycle(backend);
            assertTrue(Files.readString(directory.resolve("state.json")).contains("\"formatVersion\":2"));
            assertTrue(backend.snapshot().profiles().containsAll(state.profiles()));assertEquals(state.preferences(),backend.snapshot().preferences());
        }
    }
    @ParameterizedTest @EnumSource(value=StorageType.class,names={"JSON","SQLITE"})
    void migrationsPreserveJournalAndIdempotency(StorageType source) throws Exception {
        StorageFixtures.resources(directory);var target=source==StorageType.JSON?StorageType.SQLITE:StorageType.JSON;
        Files.writeString(directory.resolve("storage.yml"),StorageFixtures.config(source));Files.writeString(directory.resolve("target.yml"),StorageFixtures.config(target));
        StorageSnapshot before;
        try(var backend=StorageManager.create(directory,Settings.load(directory),Map.of(),false)) { VotingContract.lifecycle(backend);before=backend.snapshot(); }
        var result=new StorageMigration().migrate(directory,directory.resolve("target.yml"),directory.resolve("backups"),Map.of());
        assertEquals(before,new SnapshotCodec().decode(Files.readAllBytes(result.backupDirectory().resolve("source.json"))).snapshot());
        try(var backend=StorageManager.create(directory,Settings.load(directory),Map.of(),false)) { assertEquals(before,backend.snapshot());VotingContract.concurrent(backend); }
    }
}
