package org.dextur.reputation.storage;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.recovery.*;
import org.dextur.reputation.domain.recovery.RecoveryData.*;
import org.dextur.reputation.domain.protection.*;
import static org.junit.jupiter.api.Assertions.*;
final class RecoveryFixtures {
    static final Instant NOW=Instant.parse("2027-01-10T10:00:00Z");
    static final UUID PLAYER=UUID.fromString("ccc00000-0000-4000-8000-000000000005"),SESSION=UUID.fromString("ddd00000-0000-4000-8000-000000000005"),ZERO=new UUID(0,0);
    static Map<String,String> text(String value){return Map.of("ru_RU",value,"en_US",value);}
    static RecoveryRules.Objective objective(String id,RecoveryRules.Type type,long n){return new RecoveryRules.Objective(id,type,n,type==RecoveryRules.Type.EXTERNAL?"test-provider":"",1,Map.of(),text(id),text("description"));}
    static RecoveryRules rules(RecoveryRules.Type type){return program(List.of(new RecoveryRules.Stage("one",text("one"),"CLOCK",List.of(objective("first",type,10)),5,5000),new RecoveryRules.Stage("two",text("two"),"BOOK",List.of(objective("second",RecoveryRules.Type.WAIT_PERIOD,10)),10,5000)),12,20);}
    static RecoveryRules program(List<RecoveryRules.Stage> stages,long cap,long lifetime){return new RecoveryRules(true,-100,108000,604800,true,false,2592000,86400,"Europe/Berlin",120,3,2,30,32,4096,cap,lifetime,10000,50000,RecoveryRules.Action.PAUSE,RecoveryRules.Action.PAUSE,new RecoveryRules.Program("test",1,text("rehabilitation"),stages));}
    static Request request(Command command,Instant at){return request(UUID.randomUUID(),command,at,0,0,SESSION,true,ZERO,"",0,Set.of());}
    static Request request(UUID op,Command command,Instant at,long play,long active,UUID session,boolean login,UUID enrollment,String objective,long progress,Set<String> providers){return new Request(op,PLAYER,"global",command,at,new Facts(true,108000,NOW.minusSeconds(604800)),session,play,active,login,enrollment,objective,progress,command==Command.EXTERNAL?"test-provider":"","ADMIN:test","reason",providers);}
    static Request sample(Instant at,long play,long active){return request(UUID.randomUUID(),Command.SAMPLE,at,play,active,SESSION,true,ZERO,"",0,Set.of());}
    static void seed(StorageBackend b,Path directory,RecoveryRules rules)throws Exception{
        b.prepareMigration();b.importEmpty(new StorageSnapshot(List.of(new ReputationSnapshot(PLAYER,"global",-101,0,-101,"NEUTRAL",0,NOW.minusSeconds(1000000),NOW.minusSeconds(100))),List.of(new StorageSnapshot.Preference(PLAYER,org.dextur.reputation.domain.PlayerLanguage.en_US,NOW))));b.finishMigration();
        b.protection(ProtectionFixtures.defaults(directory));b.tiers(TierConfiguration.load(directory));b.recovery(rules);b.refreshTrust(PLAYER,"global",-1,new org.dextur.reputation.domain.VoterFacts(true,true,108000,NOW.minusSeconds(604800),true,true,true),NOW);
    }
    static StorageSnapshot complete(StorageBackend b,Path dir)throws Exception{seed(b,dir,rules(RecoveryRules.Type.ACTIVE_PLAYTIME));b.recover(request(Command.START,NOW));b.recover(sample(NOW,0,0));b.recover(sample(NOW.plusSeconds(10),10,10));b.recover(request(Command.READ,NOW.plusSeconds(20)));b.protection(ProtectionFixtures.rules(dir.resolve("migration-private"),true));var target=UUID.randomUUID();var young=new org.dextur.reputation.domain.VoterFacts(true,true,0,NOW.minusSeconds(1),true,true,true);for(int i=0;i<2;i++)b.vote("global",ProtectionFixtures.vote(UUID.randomUUID(),target,org.dextur.reputation.api.VoteType.NEGATIVE,"preserved pending"),young,ProtectionFixtures.eligibility(),NOW.plusSeconds(30+i));
        var snapshot=b.snapshot();assertEquals(2,snapshot.votes().size());assertTrue(snapshot.protection().assessments().stream().anyMatch(a->a.status()==org.dextur.reputation.domain.protection.ProtectionData.Status.PENDING));assertEquals(12,snapshot.recovery().get(PLAYER,"global").lifetimeReward());return snapshot;}
}
