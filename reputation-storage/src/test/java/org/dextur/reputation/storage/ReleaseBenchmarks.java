package org.dextur.reputation.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.investigation.InvestigationData.*;
import org.dextur.reputation.domain.policy.PolicyEngine;
import static org.junit.jupiter.api.Assertions.*;

final class ReleaseBenchmarks {
    @FunctionalInterface interface Work{void run(int n)throws Exception;}
    static Map<String,Object> measure(int samples,Work work)throws Exception {
        long[] times=new long[samples];for(int i=0;i<samples;i++){long start=System.nanoTime();work.run(i);times[i]=System.nanoTime()-start;}Arrays.sort(times);var out=new TreeMap<String,Object>();out.put("samples",samples);out.put("median_ms",times[(samples-1)/2]/1e6);out.put("p95_ms",times[(int)Math.ceil(samples*.95)-1]/1e6);if(samples>=100)out.put("p99_ms",times[(int)Math.ceil(samples*.99)-1]/1e6);return out;
    }
    static void run(StorageBackend b,Path dir,String engine,boolean large)throws Exception {
        var metrics=new TreeMap<String,Object>();
        if(large){var dataset=ReleaseDataset.create(10000,100000,850);long start=System.nanoTime();b.prepareMigration();b.importEmpty(dataset);b.finishMigration();assertEquals(dataset,b.snapshot());metrics.put("dataset_import_seconds",(System.nanoTime()-start)/1e9);}
        ReleaseStorageChecks.configured(b,dir);UUID target=large?ReleaseDataset.HOT:ReleaseStorageChecks.TARGET;b.getOrCreate(target,"global");var rules=PolicyConfiguration.load(dir);Instant now=RecoveryFixtures.NOW.plusSeconds(1000000);
        if(large){
            // Current operations enrich the explicitly synthetic legacy journal; these are not historical fixtures.
            b.getOrCreate(RecoveryFixtures.PLAYER,"global");b.administer(PolicyContract.admin(RecoveryFixtures.PLAYER,org.dextur.reputation.domain.policy.AdministrationData.Type.SET,-101,RecoveryFixtures.NOW));
            b.refreshTrust(RecoveryFixtures.PLAYER,"global",-1,new org.dextur.reputation.domain.VoterFacts(true,true,108000,RecoveryFixtures.NOW.minusSeconds(604800),true,true,true),RecoveryFixtures.NOW);
            b.recovery(RecoveryFixtures.rules(org.dextur.reputation.domain.recovery.RecoveryRules.Type.ACTIVE_PLAYTIME));b.recover(RecoveryFixtures.request(org.dextur.reputation.domain.recovery.RecoveryData.Command.START,RecoveryFixtures.NOW));b.recover(RecoveryFixtures.sample(RecoveryFixtures.NOW,0,0));b.recover(RecoveryFixtures.sample(RecoveryFixtures.NOW.plusSeconds(10),10,10));
            assertEquals(1,b.snapshot().recovery().get(RecoveryFixtures.PLAYER,"global").rewards().size());
            var young=new org.dextur.reputation.domain.VoterFacts(true,true,0,now.minusSeconds(1),true,true,true);
            for(int n=0;n<100;n++){
                UUID subject=new UUID(806,n+1);b.vote("global",ProtectionFixtures.vote(new UUID(807,2*n+1),subject,VoteType.NEGATIVE,"Large pending seed"),young,ProtectionFixtures.eligibility(),now);
                var pending=b.vote("global",ProtectionFixtures.vote(new UUID(807,2*n+2),subject,VoteType.NEGATIVE,"Large pending"),young,ProtectionFixtures.eligibility(),now).entry();
                assertEquals(org.dextur.reputation.domain.protection.ProtectionData.Status.PENDING,b.inspectVote(pending.operationId()).status());
                if(n<50)b.moderate(new Request(UUID.randomUUID(),"global",Action.REJECT,pending.operationId(),"CONSOLE","Large moderation",-1,"",now));
                if(n<25)b.moderate(new Request(UUID.randomUUID(),"global",Action.RESTORE,pending.operationId(),"CONSOLE","Large restoration",-1,"",now));
                if(n<50)b.administer(PolicyContract.admin(target,org.dextur.reputation.domain.policy.AdministrationData.Type.ADD,1,now));
            }
            metrics.put("pending_query",measure(100,n->assertEquals(75,b.investigate(new Query(Section.PENDING,"global",null,null,Filter.empty(),1,14)).total())));
            metrics.put("audit_lookup",measure(100,n->assertFalse(b.investigate(ReleaseStorageChecks.query(Section.AUDIT,target)).rows().isEmpty())));
            metrics.put("bulk_selection",measure(100,n->assertEquals(n>=25&&n<50?1:2,b.plan(Action.INVALIDATE,"global",new Filter(null,new UUID(806,n+1),null,"",null,null,"","",null),"CONSOLE","Bounded selector",now).subjects().size())));
        }
        int oldProfiles=b.snapshot().profiles().size(),oldVotes=b.snapshot().votes().size();
        metrics.put("reputation_snapshot_read",measure(100,n->assertNotNull(b.getOrCreate(target,"global"))));
        metrics.put("policy_evaluation",measure(100,n->assertNotNull(PolicyEngine.evaluate(b.policyInput(target,"global"),rules,"chat.send",Map.of(),now,false))));
        var cast=new ArrayList<VoteEntry>();metrics.put("vote_submission",measure(100,n->cast.add(b.vote("global",ProtectionFixtures.vote(new UUID(804,n+1),new UUID(805,n/10+1),VoteType.POSITIVE,"Benchmark literal <red>😀</red>"),ProtectionFixtures.mature(),ProtectionFixtures.eligibility(),now).entry())));
        metrics.put("trust_recalculation",measure(100,n->b.moderate(new Request(UUID.randomUUID(),"global",Action.RECALCULATE_TRUST,target,"CONSOLE","Benchmark",-1,"",now))));
        metrics.put("admin_inspection",measure(100,n->assertEquals(target.toString(),b.investigate(ReleaseStorageChecks.query(Section.INSPECT,target)).summary().get("uuid"))));
        metrics.put("large_history_pagination",measure(100,n->{var page=b.votingHistory(target,"global",VotePage.Direction.RECEIVED,large?3500:1,14);assertTrue(page.entries().size()<=14);}));
        metrics.put("gui_snapshot_generation",measure(100,n->{var report=b.investigate(new Query(Section.VOTES,"global",target,null,Filter.empty(),large?3500:1,14));assertTrue(report.rows().size()<=14);}));
        metrics.put("bounded_relations",measure(10,n->assertTrue(b.investigate(ReleaseStorageChecks.query(Section.RELATIONS,target)).rows().size()<=20)));
        metrics.put("bulk_batch_10_records",measure(10,n->{var plan=b.plan(Action.INVALIDATE,"global",new Filter(null,new UUID(805,n+1),null,"",null,null,"","",null),"CONSOLE","Benchmark batch",now);assertEquals(10,plan.subjects().size());UUID operation=UUID.randomUUID();b.begin(operation,plan,now);var batch=b.advance(operation,"CONSOLE",false,now);assertEquals("COMPLETED",batch.operation().status());}));
        metrics.put(b.type()==StorageType.JSON?"json_snapshot_write":"preference_transaction",measure(100,n->b.setLanguage(target,n%2==0?org.dextur.reputation.domain.PlayerLanguage.en_US:org.dextur.reputation.domain.PlayerLanguage.ru_RU)));
        var cached=new ReputationApplication(b,new Settings("global",org.dextur.reputation.domain.PlayerLanguage.ru_RU,100,60,2,256,15,false,null));try(cached){cached.getProfile(target).toCompletableFuture().get();metrics.put("api_cached_snapshot_read",measure(1000,n->assertTrue(cached.cachedProfile(target).isPresent())));}
        metrics.put("environment",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"architecture",System.getProperty("os.arch"),"processors",Runtime.getRuntime().availableProcessors(),"engine",engine,"initial_profiles",oldProfiles,"initial_votes",oldVotes,"synthetic",true,"warmup","configuration and import only; first measured sample retained","gui_scope","immutable report model, not Bukkit item rendering"));
        if(large){assertEquals(100300,b.snapshot().votes().size());
            String query="SELECT v.* FROM (SELECT operation_id,sequence_no FROM dr_votes WHERE scope_id='global' AND target_uuid='"+target+"' ORDER BY sequence_no DESC LIMIT 14 OFFSET 50000) page JOIN dr_votes v ON v.operation_id=page.operation_id ORDER BY page.sequence_no DESC";
            try(var c=((SqlStorage)b).connect();var q=c.createStatement();var rows=q.executeQuery((b.type()==StorageType.SQLITE?"EXPLAIN QUERY PLAN ":"EXPLAIN ")+query)){
                var used=new ArrayList<String>();while(rows.next()){String key=b.type()==StorageType.SQLITE?rows.getString("detail"):rows.getString("key");if(key!=null)used.add(key);}
                assertTrue(used.stream().anyMatch(k->k.contains("dr_votes_target")),used.toString());metrics.put("history_actual_used_indexes",used);
            }
        }
        ReleaseStorageChecks.writeReport(engine+"-benchmarks",metrics);
    }
}
