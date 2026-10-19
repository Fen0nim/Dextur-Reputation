package org.dextur.reputation.storage;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.protection.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.storage.ProtectionFixtures.*;

final class ProtectionContract {
    static void clusters(StorageBackend backend,ProtectionRules rules) throws Exception {
        backend.protection(rules);var members=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());int n=0;
        for(var a:members) for(var b:members) if(!a.equals(b)) backend.vote("groups",vote(a,b,VoteType.POSITIVE,"group"),mature(),eligibility(),NOW.plusSeconds(n++));
        var before=backend.snapshot();assertTrue(backend.analyze("groups",NOW.plusSeconds(60)));assertFalse(backend.analyze("groups",NOW.plusSeconds(61)));
        var after=backend.snapshot();assertEquals(before.profiles(),after.profiles());assertEquals(before.votes(),after.votes());
        var event=backend.audit("groups",0,100).getLast();assertEquals("ANALYSIS",event.kind());assertEquals(Status.SUSPICIOUS,event.status());assertEquals(4,event.relatedPlayers().size());
        var next=backend.vote("groups",vote(members.get(0),members.get(1),VoteType.NEGATIVE,"after graph"),mature(),eligibility(),NOW.plusSeconds(62));
        assertTrue(backend.inspectVote(next.entry().operationId()).signals().contains("DENSE_RECIPROCAL_GROUP"));backend.snapshot();
    }
    static void repeated(StorageBackend backend,ProtectionRules rules) throws Exception {
        backend.protection(rules);UUID a=UUID.randomUUID(),b=UUID.randomUUID();long ceiling=Long.MAX_VALUE;
        for(int i=0;i<5;i++) {
            var receipt=backend.vote("global",vote(a,b,i%2==0?VoteType.POSITIVE:VoteType.NEGATIVE,"repeat "+i),mature(),eligibility(),NOW.plusSeconds(i));
            var assessment=backend.inspectVote(receipt.entry().operationId());assertEquals(rules.repeated().multipliers().get(i),assessment.factors().get("repeat_multiplier"));
            assertTrue(assessment.weightUnits()<=ceiling);ceiling=assessment.weightUnits();
        }
        assertEquals(0,ceiling);
        backend.vote("global",new VoteRequest(UUID.randomUUID(),a,b,VoteRequest.Action.REVOKE,null,"","withdraw"),mature(),eligibility(),NOW.plusSeconds(6));
        var later=backend.vote("global",vote(a,b,VoteType.POSITIVE,"window expired"),mature(),eligibility(),NOW.plusSeconds(40*86400L));
        assertEquals(0,backend.inspectVote(later.entry().operationId()).weightUnits());
        UUID x=UUID.randomUUID(),y=UUID.randomUUID();
        for(int i=0;i<6;i++) {
            var receipt=backend.vote("reciprocal",vote(i%2==0?x:y,i%2==0?y:x,i%3==0?VoteType.NEGATIVE:VoteType.POSITIVE,"reciprocal "+i),mature(),eligibility(),NOW.plusSeconds(i));
            var assessment=backend.inspectVote(receipt.entry().operationId());
            assertEquals(i<3?1000000:rules.reciprocity().multipliers().get(Math.min(4,i-2)),assessment.factors().get("reciprocity_multiplier"));
            if(i>=3) assertTrue(assessment.signals().contains("RECIPROCITY"));
        }
        backend.snapshot();
    }
    static void bursts(StorageBackend backend,ProtectionRules rules) throws Exception {
        backend.protection(rules);UUID matureTarget=UUID.randomUUID(),youngTarget=UUID.randomUUID(),growthTarget=UUID.randomUUID();
        for(int i=0;i<12;i++) {
            var legitimate=backend.vote("mature",vote(UUID.randomUUID(),matureTarget,VoteType.NEGATIVE,"legitimate "+i),mature(),eligibility(),NOW.plusSeconds(i));
            assertNotEquals(VoteProcessingOutcome.Status.PENDING,backend.outcome(legitimate.entry().operationId()).status());
            var attack=backend.vote("young",vote(UUID.randomUUID(),youngTarget,VoteType.NEGATIVE,"attack "+i),young(),eligibility(),NOW.plusSeconds(i));
            assertEquals(i>=9?VoteProcessingOutcome.Status.PENDING:VoteProcessingOutcome.Status.ACCEPTED,backend.outcome(attack.entry().operationId()).status());
            var growth=backend.vote("growth",vote(UUID.randomUUID(),growthTarget,VoteType.POSITIVE,"growth "+i),young(),eligibility(),NOW.plusSeconds(i));
            assertEquals(i>=11?VoteProcessingOutcome.Status.PENDING:VoteProcessingOutcome.Status.ACCEPTED,backend.outcome(growth.entry().operationId()).status());
        }
        assertTrue(backend.snapshot().protection().trusts().stream().allMatch(t->t.confirmed()==0));
    }
    static void historical(StorageBackend backend,ProtectionRules rules) throws Exception {
        UUID target=UUID.randomUUID(),voter=UUID.randomUUID();var old=backend.vote("global",vote(voter,target,VoteType.POSITIVE,"phase two"),mature(),eligibility(),NOW);
        assertEquals(1,old.entry().calculationVersion());var original=backend.snapshot();backend.protection(rules);assertEquals(original,backend.snapshot());
        var fresh=backend.vote("global",vote(UUID.randomUUID(),target,VoteType.POSITIVE,"phase three"),mature(),eligibility(),NOW.plusSeconds(1));
        assertEquals(1,fresh.currentView().profile().visibleScore());assertEquals(old.entry(),backend.operation(old.entry().operationId()));
        backend.vote("global",new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.REVOKE,null,"","legacy revoke"),mature(),eligibility(),NOW.plusSeconds(2));
        assertEquals(0,backend.votingView(target,"global").profile().visibleScore());backend.snapshot();
    }
    static void fractions(StorageBackend backend,ProtectionRules rules) throws Exception {
        backend.protection(rules);UUID target=UUID.randomUUID();
        for(int i=0;i<3;i++) {
            var receipt=backend.vote("global",vote(UUID.randomUUID(),target,VoteType.POSITIVE,"fraction"),mature(),eligibility(),NOW.plusSeconds(i));
            assertEquals(i==0?0:1,receipt.currentView().profile().visibleScore());assertEquals(2,receipt.entry().calculationVersion());
            assertEquals(350000,backend.inspectVote(receipt.entry().operationId()).weightUnits());
        }
        var score=backend.snapshot().protection().scores().stream().filter(s->s.player().equals(target)).findFirst().orElseThrow();assertEquals(1050000,score.currentUnits());
        assertEquals(3,backend.votingView(target,"global").statistics().positive());
    }
    static void pending(StorageBackend backend,ProtectionRules rules) throws Exception {
        backend.protection(rules);UUID target=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID();
        var a=backend.vote("global",vote(first,target,VoteType.NEGATIVE,"a"),young(),eligibility(),NOW);
        var request=vote(second,target,VoteType.NEGATIVE,"b");var b=backend.vote("global",request,young(),eligibility(),NOW.plusSeconds(1));
        assertEquals(VoteProcessingOutcome.Status.PENDING,backend.outcome(request.operationId()).status());
        assertEquals(a.currentView().profile().communityScore(),b.currentView().profile().communityScore());assertEquals(1,b.currentView().statistics().negative());
        assertFalse(backend.votingHistory(target,"global",VotePage.Direction.RECEIVED,1,10).entries().getFirst().active());
        UUID transition=UUID.randomUUID(),operator=UUID.randomUUID();var privateVote=backend.inspectVote(request.operationId());
        var accepted=backend.review(transition,request.operationId(),operator,"Reviewed independent evidence",Review.ACCEPT,privateVote.revision(),NOW.plusSeconds(2));
        assertEquals(-1,accepted.profile().visibleScore());assertEquals(2,accepted.statistics().negative());
        assertEquals(accepted,backend.review(transition,request.operationId(),operator,"Reviewed independent evidence",Review.ACCEPT,privateVote.revision(),NOW.plusSeconds(3)));
        assertEquals(VoteRejectedException.Code.ID_CONFLICT,assertThrows(VoteRejectedException.class,()->backend.review(transition,request.operationId(),operator,"different",Review.ACCEPT,privateVote.revision(),NOW.plusSeconds(3))).code());
        var invalidated=backend.review(UUID.randomUUID(),request.operationId(),operator,"Confirmed manipulation",Review.CONFIRM_MANIPULATION,backend.inspectVote(request.operationId()).revision(),NOW.plusSeconds(4));
        assertEquals(0,invalidated.profile().visibleScore());assertEquals(1,invalidated.statistics().negative());
        var trust=backend.inspectTrust(second,"global");assertEquals(1,trust.confirmed());assertEquals(1,trust.invalidated());assertTrue(trust.restrictedUntil().isAfter(NOW));
        assertEquals(VoteRejectedException.Code.TEMPORARILY_RESTRICTED,assertThrows(VoteRejectedException.class,()->backend.vote("global",vote(second,UUID.randomUUID(),VoteType.POSITIVE,"restricted"),young(),eligibility(),NOW.plusSeconds(5))).code());
        assertEquals(4,backend.audit("global",0,100).size());
    }
    static void replacementPending(StorageBackend backend,ProtectionRules rules) throws Exception {
        backend.protection(rules);UUID target=UUID.randomUUID(),author=UUID.randomUUID();
        var positive=backend.vote("global",vote(author,target,VoteType.POSITIVE,"original"),young(),eligibility(),NOW);
        backend.vote("global",vote(UUID.randomUUID(),target,VoteType.NEGATIVE,"other"),young(),eligibility(),NOW.plusSeconds(1));
        var replacement=backend.vote("global",vote(author,target,VoteType.NEGATIVE,"pending replacement"),young(),eligibility(),NOW.plusSeconds(2));
        assertEquals(VoteProcessingOutcome.Status.PENDING,backend.outcome(replacement.entry().operationId()).status());
        var active=backend.votingHistory(target,"global",VotePage.Direction.RECEIVED,1,10).entries().stream().filter(VotePage.Item::active).map(i->i.entry().operationId()).toList();
        assertTrue(active.contains(positive.entry().operationId()));assertFalse(active.contains(replacement.entry().operationId()));
        var pending=backend.inspectVote(replacement.entry().operationId());
        var accepted=backend.review(UUID.randomUUID(),pending.voteId(),UUID.randomUUID(),"Review",Review.ACCEPT,pending.revision(),NOW.plusSeconds(3));
        assertEquals(-1,accepted.profile().visibleScore());assertEquals(0,accepted.statistics().positive());
        assertEquals(VoteProcessingOutcome.Status.SUPERSEDED,backend.outcome(positive.entry().operationId()).status());
        var revoke=new VoteRequest(UUID.randomUUID(),author,target,VoteRequest.Action.REVOKE,null,"","revoke");
        backend.vote("global",revoke,young(),eligibility(),NOW.plusSeconds(4));assertEquals(0,backend.votingView(target,"global").profile().visibleScore());
        assertThrows(IllegalStateException.class,()->backend.review(UUID.randomUUID(),pending.voteId(),UUID.randomUUID(),"stale",Review.ACCEPT,pending.revision(),NOW.plusSeconds(5)));
    }
    static void concurrent(StorageBackend backend,ProtectionRules rules) throws Exception {
        backend.protection(rules);UUID target=UUID.randomUUID(),author=UUID.randomUUID();var request=vote(author,target,VoteType.POSITIVE,"concurrent");
        try(var workers=Executors.newFixedThreadPool(8)) {
            List<Callable<VoteReceipt>> calls=new ArrayList<>();for(int i=0;i<24;i++) calls.add(()->backend.vote("global",request,mature(),eligibility(),NOW));
            int fresh=0;for(var result:workers.invokeAll(calls)) if(!result.get().duplicate()) fresh++;assertEquals(1,fresh);
        }
        var trust=backend.inspectTrust(author,"global");assertEquals(1,trust.submitted());assertEquals(1,backend.audit("global",0,100).size());
        assertTrue(backend.refreshTrust(author,"global",trust.revision(),mature(),NOW.plusSeconds(1)));
        assertFalse(backend.refreshTrust(author,"global",trust.revision(),young(),NOW.plusSeconds(2)));
        assertEquals(1,backend.inspectTrust(author,"global").submitted());assertEquals(1,backend.snapshot().protection().assessments().size());
        long expected=backend.inspectTrust(author,"global").revision();
        try(var workers=Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> refreshes=new ArrayList<>();for(int i=0;i<20;i++) refreshes.add(()->backend.refreshTrust(author,"global",expected,mature(),NOW.plusSeconds(3)));
            int updated=0;for(var result:workers.invokeAll(refreshes)) if(result.get()) updated++;assertEquals(1,updated);assertEquals(expected+1,backend.inspectTrust(author,"global").revision());
            List<Callable<VoteReceipt>> votes=new ArrayList<>();for(int i=0;i<24;i++) {var independent=vote(UUID.randomUUID(),target,VoteType.POSITIVE,"independent concurrent");votes.add(()->backend.vote("global",independent,mature(),eligibility(),NOW.plusSeconds(4)));}
            for(var result:workers.invokeAll(votes)) assertFalse(result.get().duplicate());
        }
        assertEquals(25,backend.votingView(target,"global").statistics().positive());assertEquals(9,backend.votingView(target,"global").profile().visibleScore());
        var score=backend.snapshot().protection().scores().stream().filter(s->s.player().equals(target)).findFirst().orElseThrow();assertEquals(8750000,score.currentUnits());assertEquals(25,backend.audit("global",0,100).size());
    }
}
