package org.dextur.reputation.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;

class ProtectionModelTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    @Test void initialTrustAndVerifiedMaturityAreVersionedAndReputationIndependent() throws Exception {
        var rules=defaults(directory);var id=UUID.randomUUID();var initial=TrustModel.initial(id,"global",mature(),rules,NOW,0);
        assertEquals(500000,initial.score());assertEquals(1,initial.modelVersion());assertEquals(0,initial.submitted());assertEquals(NOW,initial.factsAt());
        var mature=TrustModel.calculate(id,"global",mature(),rules,NOW,initial,1,List.of(),false);
        var young=TrustModel.calculate(id,"global",young(),rules,NOW,null,1,List.of(),false);
        assertEquals(600000,mature.score());assertEquals(400000,young.score());assertEquals(2,mature.revision());
        assertTrue(Arrays.stream(TrustModel.class.getDeclaredMethods()).flatMap(m->Arrays.stream(m.getParameterTypes())).noneMatch(t->t==ReputationSnapshot.class));
        var bad=new Trust(id,"global",500000,1,NOW,1,0,0,NOW,NOW,20,20,100,Instant.EPOCH,Map.of());
        assertEquals(0,TrustModel.calculate(id,"global",young(),rules,NOW,bad,2,List.of(),false).score());
        UUID target=UUID.randomUUID(),operation=UUID.randomUUID();
        var positiveProfile=new VoteEntry(operation,1,"global",id,target,VoteEntry.Action.CAST,VoteType.POSITIVE,"helpful","",NOW,null,1,1,1,"0".repeat(64),1000000,1000001);
        var negativeProfile=new VoteEntry(operation,1,"global",id,target,VoteEntry.Action.CAST,VoteType.POSITIVE,"helpful","",NOW,null,1,1,1,"0".repeat(64),-1000000,-999999);
        assertEquals(TrustModel.calculate(id,"global",mature(),rules,NOW,initial,1,List.of(new Sample(positiveProfile,true,600000,1000000)),false).score(),
                TrustModel.calculate(id,"global",mature(),rules,NOW,initial,1,List.of(new Sample(negativeProfile,true,600000,1000000)),false).score());
        Path config=directory.resolve("trust.yml");Files.writeString(config,Files.readString(config).replace("initial: 0.50","initial: 0.99"));
        assertEquals(1000000,TrustModel.calculate(id,"global",mature(),org.dextur.reputation.application.ProtectionConfiguration.load(directory),NOW,null,0,List.of(),false).score());
    }
    @Test void fixedPointIsDeterministicAndRejectsOverflowOrExcessPrecision() {
        assertEquals(100000,Fixed.product(500000,200000));assertEquals(2,Fixed.product(3,500000));assertEquals(2,Fixed.product(5,500000));
        assertEquals(0,Fixed.display(499999));assertEquals(1,Fixed.display(500000));assertEquals(-1,Fixed.display(-500000));
        assertEquals(350000,Fixed.decimal("0.35"));assertThrows(ArithmeticException.class,()->Fixed.decimal("0.0000001"));
        assertThrows(ArithmeticException.class,()->Fixed.units(Long.MAX_VALUE));assertThrows(ArithmeticException.class,()->Fixed.product(Long.MAX_VALUE,Long.MAX_VALUE));
    }
    @Test void combinationAndClampsAreReproducibleFromDurableFactors() throws Exception {
        defaults(directory);Path config=directory.resolve("anti-abuse.yml");String original=Files.readString(config);
        for(String replacement:List.of("base: {like: 100.0, dislike: 100.0}","base: {like: 0.01, dislike: 0.01}","base: {like: 1.0, dislike: 1.0}")) {
            Files.writeString(config,original.replace("base: {like: 1.0, dislike: 1.0}",replacement).replace("minimum: 0.0, maximum: 1.50","minimum: 0.20, maximum: 1.50"));
            var rules=org.dextur.reputation.application.ProtectionConfiguration.load(directory);
            try(var backend=new JsonStorage(directory.resolve(UUID.randomUUID().toString()),1048576,false)) {
                backend.protection(rules);var receipt=backend.vote("global",vote(UUID.randomUUID(),UUID.randomUUID(),VoteType.POSITIVE,"weight"),mature(),eligibility(),NOW);
                var a=backend.inspectVote(receipt.entry().operationId());var f=a.factors();
                assertEquals(Fixed.clamp(Fixed.product(f.get("base"),f.get("trust_multiplier"),f.get("repeat_multiplier"),f.get("reciprocity_multiplier"),f.get("diversity_multiplier"),f.get("maturity_multiplier"),f.get("cluster_multiplier")),rules.weight().min(),rules.weight().max()),a.weightUnits());
                if(replacement.contains("100.0")) assertEquals(1500000,a.weightUnits());
                if(replacement.contains("0.01")) assertEquals(rules.weight().min(),a.weightUnits());
                backend.snapshot();
            }
        }
    }
    @Test void denseGroupsAreAuditedInSubsequentBoundedIdempotentTransition() throws Exception {
        var rules=defaults(directory);var members=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        try(var b=StorageFixtures.local(directory,org.dextur.reputation.application.StorageType.JSON,false)) {
            b.protection(rules);int sequence=0;
            for(var a:members) for(var target:members) if(!a.equals(target)) b.vote("global",vote(a,target,VoteType.POSITIVE,"group"),mature(),eligibility(),NOW.plusSeconds(sequence++));
            var before=b.snapshot();assertTrue(b.analyze("global",NOW.plusSeconds(60)));assertFalse(b.analyze("global",NOW.plusSeconds(61)));
            var after=b.snapshot();assertEquals(before.profiles(),after.profiles());assertEquals(before.votes(),after.votes());assertEquals(before.protection().assessments(),after.protection().assessments());
            var audit=after.protection().audit().getLast();assertEquals("ANALYSIS",audit.kind());assertEquals(Status.SUSPICIOUS,audit.status());assertEquals(4,audit.relatedPlayers().size());
            assertTrue(after.protection().trusts().stream().allMatch(t->t.confirmed()==0));
            var next=b.vote("global",vote(members.get(0),members.get(1),VoteType.NEGATIVE,"following group"),mature(),eligibility(),NOW.plusSeconds(62));
            assertTrue(b.inspectVote(next.entry().operationId()).signals().contains("DENSE_RECIPROCAL_GROUP"));
        }
    }
    @Test void threeFriendsAndDisjointGroupsAreNotConflated() throws Exception {
        var rules=defaults(directory);var members=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        try(var b=StorageFixtures.local(directory,org.dextur.reputation.application.StorageType.JSON,false)) {
            b.protection(rules);int sequence=0;for(var a:members) for(var target:members) if(!a.equals(target)) b.vote("global",vote(a,target,VoteType.NEGATIVE,"friends"),mature(),eligibility(),NOW.plusSeconds(sequence++));
            b.analyze("global",NOW.plusSeconds(60));assertTrue(b.snapshot().protection().scopes().getFirst().clustered().isEmpty());
        }
        var other=UUID.randomUUID();var scope=new ScopeState("global",0,1,NOW,0,List.of(members,List.of(other)),true,0);
        assertFalse(scope.sameCluster(members.getFirst(),other));assertTrue(scope.sameCluster(members.getFirst(),members.getLast()));
    }
    @Test void boundedEvidenceUsesPendingAndNeverInventsConfirmedSignals() throws Exception {
        defaults(directory);Path file=directory.resolve("anti-abuse.yml");Files.writeString(file,Files.readString(file).replace("maximum-events: 2048","maximum-events: 16"));
        var rules=org.dextur.reputation.application.ProtectionConfiguration.load(directory);
        try(var b=StorageFixtures.local(directory,org.dextur.reputation.application.StorageType.JSON,false)) {
            b.protection(rules);var target=UUID.randomUUID();VoteReceipt last=null;
            for(int i=0;i<18;i++) last=b.vote("global",vote(UUID.randomUUID(),target,VoteType.POSITIVE,"bounded"),mature(),eligibility(),NOW.plusSeconds(i));
            var a=b.inspectVote(last.entry().operationId());assertEquals(Status.PENDING,a.status());assertTrue(a.signals().contains("ANALYSIS_CAPACITY"));
            assertTrue(b.snapshot().protection().trusts().stream().allMatch(t->t.confirmed()==0));
        }
    }
    @Test void privateCodecRejectsFractionalIntegersAndOverflow() throws Exception {
        var initial=TrustModel.initial(UUID.randomUUID(),"global",mature(),defaults(directory),NOW,0);String json=ProtectionCodec.encode(initial);
        assertThrows(RuntimeException.class,()->ProtectionCodec.decode(json.replace("\"revision\":1","\"revision\":1.5"),Trust.class));
        assertThrows(RuntimeException.class,()->ProtectionCodec.decode(json.replace("\"revision\":1","\"revision\":9223372036854775808"),Trust.class));
        assertThrows(RuntimeException.class,()->ProtectionCodec.decode(json.replace("\"modelVersion\":1","\"modelVersion\":2"),Trust.class));
    }
}
