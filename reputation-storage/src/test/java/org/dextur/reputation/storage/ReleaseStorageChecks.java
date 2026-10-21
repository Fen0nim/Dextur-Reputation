package org.dextur.reputation.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.VoterFacts;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.policy.AdministrationData;
import org.dextur.reputation.domain.recovery.RecoveryData;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded synthetic load, deliberately separate from genuine historical upgrade fixtures. */
final class ReleaseStorageChecks {
    static final Instant NOW=RecoveryFixtures.NOW.plusSeconds(10);
    static final UUID TARGET=new UUID(88,1),BULK_TARGET=new UUID(88,2),LOCKED=new UUID(88,3);
    static void configured(StorageBackend b,Path dir)throws Exception {InvestigationContract.configure(b,dir);}
    static Request request(Action action,UUID subject){return new Request(UUID.randomUUID(),"global",action,subject,"CONSOLE","Release hardening",-1,"",NOW);}
    static Query query(Section section,UUID player){return new Query(section,"global",player,null,Filter.empty(),1,14);}
    static StorageSnapshot seed(StorageBackend b,Path dir)throws Exception {
        RecoveryFixtures.seed(b,dir,RecoveryFixtures.rules(org.dextur.reputation.domain.recovery.RecoveryRules.Type.ACTIVE_PLAYTIME));
        configured(b,dir);b.recovery(RecoveryFixtures.rules(org.dextur.reputation.domain.recovery.RecoveryRules.Type.ACTIVE_PLAYTIME));b.recover(RecoveryFixtures.request(RecoveryData.Command.START,RecoveryFixtures.NOW));b.recover(RecoveryFixtures.sample(RecoveryFixtures.NOW,0,0));
        b.getOrCreate(TARGET,"global");b.getOrCreate(LOCKED,"global");
        for(int i=0;i<8;i++)b.vote("global",ProtectionFixtures.vote(new UUID(88,100+i),BULK_TARGET,VoteType.POSITIVE,"bulk seed"),ProtectionFixtures.mature(),ProtectionFixtures.eligibility(),NOW);
        return b.snapshot();
    }
    static void stress(StorageBackend b,Path dir)throws Exception {
        seed(b,dir);
        UUID pendingTarget=new UUID(88,4);var young=new VoterFacts(true,true,0,NOW.minusSeconds(1),true,true,true);
        b.vote("global",ProtectionFixtures.vote(new UUID(88,401),pendingTarget,VoteType.NEGATIVE,"stress pending seed"),young,ProtectionFixtures.eligibility(),NOW);
        var pending=b.vote("global",ProtectionFixtures.vote(new UUID(88,402),pendingTarget,VoteType.NEGATIVE,"stress pending"),young,ProtectionFixtures.eligibility(),NOW).entry();
        assertEquals(org.dextur.reputation.domain.protection.ProtectionData.Status.PENDING,b.inspectVote(pending.operationId()).status());
        var approval=request(Action.APPROVE,pending.operationId());
        var jobs=new ArrayList<Callable<Object>>();
        for(int i=0;i<128;i++){
            var vote=ProtectionFixtures.vote(new UUID(89,i+1),TARGET,VoteType.POSITIVE,"Юникод 😀 <red>literal</red>");
            for(int copy=0;copy<2;copy++)jobs.add(()->b.vote("global",vote,ProtectionFixtures.mature(),ProtectionFixtures.eligibility(),NOW));
        }
        for(int i=0;i<64;i++){
            jobs.add(()->b.administer(PolicyContract.admin(TARGET,AdministrationData.Type.ADD,1,NOW)));
            jobs.add(()->b.moderate(request(Action.RECALCULATE_REPUTATION,TARGET)));
            jobs.add(()->b.moderate(request(Action.RECALCULATE_TRUST,TARGET)));
            jobs.add(()->b.policyInput(TARGET,"global"));jobs.add(()->b.investigate(query(Section.INSPECT,TARGET)));
        }
        for(int i=0;i<32;i++){
            jobs.add(()->b.administer(PolicyContract.admin(LOCKED,AdministrationData.Type.LOCK,0,NOW)));
            jobs.add(()->b.administer(PolicyContract.admin(LOCKED,AdministrationData.Type.UNLOCK,0,NOW)));
            jobs.add(()->b.recover(RecoveryFixtures.sample(NOW,10,10)));
            jobs.add(()->b.moderate(request(Action.RECALCULATE_ALL,RecoveryFixtures.PLAYER)));
        }
        var plan=b.plan(Action.INVALIDATE,"global",new Filter(null,BULK_TARGET,null,"",null,null,"","",null),"CONSOLE","Bounded stress",NOW);UUID bulk=UUID.randomUUID();b.begin(bulk,plan,NOW);
        for(int i=0;i<16;i++)jobs.add(()->b.advance(bulk,"CONSOLE",false,NOW));
        for(int i=0;i<16;i++)jobs.add(()->b.moderate(approval));
        Collections.shuffle(jobs,new Random(8102026));
        long started=System.nanoTime();try(var pool=Executors.newFixedThreadPool(8)){
            var futures=jobs.stream().map(pool::submit).toList();for(var f:futures)f.get(120,TimeUnit.SECONDS);assertTrue(futures.stream().allMatch(Future::isDone));
        }
        assertEquals(org.dextur.reputation.domain.protection.ProtectionData.Status.ACCEPTED,b.inspectVote(pending.operationId()).status());
        var snapshot=b.snapshot();assertEquals(138,snapshot.votes().size());assertEquals(138,snapshot.votes().stream().map(VoteEntry::operationId).distinct().count());
        assertEquals(64,snapshot.administration().contribution(TARGET,"global"));assertEquals(45,b.getOrCreate(TARGET,"global").communityScore());assertEquals(109,b.getOrCreate(TARGET,"global").visibleScore());
        assertEquals(0,b.getOrCreate(BULK_TARGET,"global").communityScore());assertEquals("COMPLETED",snapshot.investigation().get(Kind.BULK,bulk).status());
        var recovery=snapshot.recovery().get(RecoveryFixtures.PLAYER,"global");assertEquals(5,recovery.lifetimeReward());assertEquals(1,recovery.rewards().size());
        assertTrue(snapshot.profiles().stream().allMatch(p->p.visibleScore()==p.communityScore()+p.systemScore()&&p.revision()>=0));
        var original=b.snapshot();var dry=b.plan(Action.RECALCULATE_ALL,"global",new Filter(null,TARGET,null,"",null,null,"","",null),"CONSOLE","Verify without mutation",NOW);assertFalse(dry.subjects().isEmpty());assertEquals(original,b.snapshot());
        writeReport(b.type()+"-stress",Map.of("operations",jobs.size(),"workers",8,"seconds",(System.nanoTime()-started)/1e9,"unique_votes",138,"reward_count",1));
    }
    static void inspectionParity(StorageBackend b,Path dir,InvestigationContract.Scenario scenario)throws Exception {
        InvestigationContract.run(scenario,b,dir);var state=b.snapshot();
        var model=new JsonInvestigation(state,AdministrationConfiguration.load(dir),ProtectionConfiguration.load(dir),TierConfiguration.load(dir));
        var query=query(Section.INSPECT,InvestigationContract.TARGET);
        assertEquals(new InvestigationReports(model).read(query).summary(),b.investigate(query).summary());
    }
    static void writeReport(String name,Object value)throws Exception {Path folder=Path.of("build/reports/release");Files.createDirectories(folder);Files.writeString(folder.resolve(name+".json"),ProtectionCodec.encode(value));}
    static StorageSnapshot portable(StorageBackend b,Path dir)throws Exception {
        RecoveryFixtures.complete(b,dir);b.features(PolicyConfiguration.load(dir));b.investigation(AdministrationConfiguration.load(dir));
        var pending=b.snapshot().protection().assessments().stream().filter(a->a.status()==org.dextur.reputation.domain.protection.ProtectionData.Status.PENDING).findFirst().orElseThrow();
        b.moderate(request(Action.REJECT,pending.voteId()));b.moderate(request(Action.RESTORE,pending.voteId()));
        b.administer(PolicyContract.admin(RecoveryFixtures.PLAYER,AdministrationData.Type.ADD,7,NOW.plusSeconds(100)));
        var plan=b.plan(Action.INVALIDATE,"global",new Filter(null,pending.target(),null,"",null,null,"","",null),"CONSOLE","Preserve bulk metadata",NOW);b.begin(UUID.randomUUID(),plan,NOW);
        return b.snapshot();
    }
}
