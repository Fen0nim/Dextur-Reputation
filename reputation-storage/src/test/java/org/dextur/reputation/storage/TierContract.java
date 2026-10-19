package org.dextur.reputation.storage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.tier.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;

final class TierContract {
    static UUID seed(StorageBackend b,long score) throws Exception {
        UUID target=UUID.randomUUID();var now=Instant.parse("2026-10-18T00:00:00Z");b.prepareMigration();b.importEmpty(new StorageSnapshot(List.of(new ReputationSnapshot(target,"global",score,0,score,"NEUTRAL",0,now,now)),List.of()));b.finishMigration();return target;
    }
    static TierRules small(Path path) throws Exception {
        var defaults=TierConfiguration.load(path);return new TierRules(defaults.definitions().stream().map(d->new TierRules.Definition(d.id(),d.enabled(),d.threshold()==25?1:d.threshold()==-25?-1:d.threshold(),d.hysteresis(),d.priority(),d.names(),d.color(),d.prefix(),d.suffix(),d.group(),d.discordRole(),d.notifications(),d.metadata())).toList(),0,0,"f".repeat(64));
    }
    static UUID hysteresis(StorageBackend b,Path dir,boolean negative) throws Exception {
        UUID target=seed(b,negative?-99:99),voter=UUID.randomUUID();b.tiers(TierConfiguration.load(dir));
        var vote=b.vote("global",vote(voter,target,negative?VoteType.NEGATIVE:VoteType.POSITIVE,"entry"),mature(),eligibility(),NOW);
        assertEquals(negative?"OFFENDER":"RESPECTED",vote.currentView().profile().tier());
        var request=new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.REVOKE,null,"","exit margin");
        var revoked=b.vote("global",request,mature(),eligibility(),NOW.plusSeconds(1));assertEquals(negative?-99:99,revoked.currentView().profile().visibleScore());assertEquals(vote.currentView().profile().tier(),revoked.currentView().profile().tier());
        var state=b.tierState(target,"global");assertEquals(2,state.revision());assertEquals(2,state.history().size());assertEquals(2,state.profileRevision());
        var before=b.snapshot();b.vote("global",request,mature(),eligibility(),NOW.plusSeconds(2));assertEquals(before,b.snapshot());return target;
    }
    static void pending(StorageBackend b,Path dir) throws Exception {
        b.protection(rules(dir.resolve("protection"),true));b.tiers(small(dir));UUID target=UUID.randomUUID();
        b.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"first"),young(),eligibility(),NOW);
        var pending=b.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"pending"),young(),eligibility(),NOW.plusSeconds(1));assertEquals("NEUTRAL",pending.currentView().profile().tier());
        UUID id=pending.entry().operationId();var accepted=b.review(UUID.randomUUID(),id,UUID.randomUUID(),"QA acceptance",org.dextur.reputation.domain.protection.ProtectionData.Review.ACCEPT,b.inspectVote(id).revision(),NOW.plusSeconds(2));assertEquals("SUSPICIOUS",accepted.profile().tier());
        var invalid=b.review(UUID.randomUUID(),id,UUID.randomUUID(),"QA invalidation",org.dextur.reputation.domain.protection.ProtectionData.Review.INVALIDATE,b.inspectVote(id).revision(),NOW.plusSeconds(3));assertEquals("NEUTRAL",invalid.profile().tier());assertEquals(2,b.tierState(target,"global").history().size());b.snapshot();
    }
    static void concurrent(StorageBackend b,Path dir) throws Exception {
        UUID target=seed(b,99);b.tiers(TierConfiguration.load(dir));
        try(var workers=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<Callable<VoteReceipt>>();for(int i=0;i<16;i++){var request=vote(UUID.randomUUID(),target,VoteType.POSITIVE,"concurrent");tasks.add(()->b.vote("global",request,mature(),eligibility(),NOW));}
            for(var f:workers.invokeAll(tasks))assertEquals("RESPECTED",f.get().currentView().profile().tier());
        }
        assertEquals(115,b.getOrCreate(target,"global").visibleScore());assertEquals(2,b.tierState(target,"global").history().size());assertEquals(16,b.tierState(target,"global").profileRevision());b.snapshot();
    }
    static void scale(StorageBackend backend,Path directory) throws Exception {
        var profiles=new ArrayList<ReputationSnapshot>();var names=List.of("global","other","a_scope");
        for(int i=0;i<135;i++)profiles.add(new ReputationSnapshot(new UUID(0,i/3+1),names.get(i%3),99,0,99,"NEUTRAL",0,NOW,NOW));
        backend.prepareMigration();backend.importEmpty(new StorageSnapshot(profiles,List.of()));backend.finishMigration();backend.tiers(TierConfiguration.load(directory));
        var snapshot=backend.snapshot();assertEquals(135,snapshot.profiles().size());assertEquals(135,snapshot.tiers().states().size());assertTrue(snapshot.profiles().stream().allMatch(p->p.tier().equals("RECOGNIZED")));
        backend.tiers(TierConfiguration.load(directory));assertEquals(snapshot,backend.snapshot());
    }

}
