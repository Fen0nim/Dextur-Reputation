package org.dextur.reputation.storage;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same durable voting contract is exercised against each real backend. */
final class VotingContract {
    static final Instant NOW=Instant.parse("2026-10-18T12:00:00.123456Z");
    static VotingRules rules(int limit,long cooldown) {
        return new VotingRules(0,0,cooldown,0,limit,List.of(),ZoneId.of("UTC"),0,VotingRules.RepeatPolicy.REPLACE,true,250,1,1,
                Map.of("test",new VotingRules.Reason("test","test",Set.of(VoteType.values()))));
    }
    static VoterFacts facts() { return new VoterFacts(true,true,108000,NOW.minusSeconds(604800),true,true,true); }
    static VoteRequest request(UUID voter,UUID target,VoteType type,String comment) { return new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.SUBMIT,type,"test",comment); }
    static void lifecycle(StorageBackend backend) throws Exception {
        UUID voter=UUID.randomUUID(),target=UUID.randomUUID();var first=request(voter,target,VoteType.POSITIVE,"first");
        var receipt=backend.vote("global",first,facts(),rules(10,0),NOW);assertFalse(receipt.duplicate());assertEquals(1,receipt.currentView().profile().visibleScore());
        assertTrue(backend.vote("global",first,null,rules(1,100),NOW).duplicate());
        assertEquals(1,backend.votingView(target,"global").profile().revision());
        var conflicting=new VoteRequest(first.operationId(),voter,target,VoteRequest.Action.SUBMIT,VoteType.NEGATIVE,"test","first");
        assertEquals(VoteRejectedException.Code.ID_CONFLICT,assertThrows(VoteRejectedException.class,()->backend.vote("global",conflicting,facts(),rules(10,0),NOW)).code());
        var replace=backend.vote("global",request(voter,target,VoteType.NEGATIVE,"replace"),facts(),rules(10,0),NOW.plusSeconds(1));
        assertEquals(-1,replace.currentView().profile().communityScore());assertEquals(new VoteStatistics(0,1,1,2),replace.currentView().statistics());
        var revoke=new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.REVOKE,null,"","revoke");
        assertEquals(0,backend.vote("global",revoke,facts(),rules(10,0),NOW.plusSeconds(2)).currentView().profile().visibleScore());
        var page=backend.votingHistory(voter,"global",VotePage.Direction.GIVEN,1,2);assertEquals(3,page.totalEntries());assertEquals(2,page.entries().size());assertTrue(page.entries().stream().noneMatch(VotePage.Item::active));
        assertEquals(1,backend.votingHistory(target,"global",VotePage.Direction.RECEIVED,2,2).entries().size());
        var cast=backend.vote("global",request(voter,target,VoteType.POSITIVE,"again"),facts(),rules(10,0),NOW.plusSeconds(3));assertEquals(VoteEntry.Action.CAST,cast.entry().action());
        assertEquals(new VoteStatistics(1,0,1,4),cast.currentView().statistics());assertEquals(4,cast.currentView().profile().revision());
        assertEquals(0,backend.votingHistory(target,"other",VotePage.Direction.RECEIVED,1,10).totalEntries());
        assertEquals(1,backend.votingHistory(target,"global",VotePage.Direction.RECEIVED,1,10).entries().stream().filter(VotePage.Item::active).count());
        assertThrows(VoteRejectedException.class,()->backend.votingHistory(target,"global",VotePage.Direction.RECEIVED,0,10));
    }
    static void concurrent(StorageBackend backend) throws Exception {
        UUID target=UUID.randomUUID(),voter=UUID.randomUUID();var duplicate=request(voter,target,VoteType.POSITIVE,"duplicate");
        try(var workers=Executors.newFixedThreadPool(8)) {
            List<Callable<VoteReceipt>> work=new ArrayList<>();for(int i=0;i<24;i++) work.add(()->backend.vote("global",duplicate,facts(),rules(100,0),NOW));
            var results=workers.invokeAll(work);int fresh=0;for(var result:results) if(!result.get().duplicate()) fresh++;assertEquals(1,fresh);
            work.clear();for(int i=0;i<24;i++) { UUID next=UUID.randomUUID();work.add(()->backend.vote("global",request(next,target,VoteType.POSITIVE,"concurrent"),facts(),rules(100,0),NOW)); }
            for(var result:workers.invokeAll(work)) assertFalse(result.get().duplicate());
            var view=backend.votingView(target,"global");assertEquals(25,view.profile().visibleScore());assertEquals(25,view.profile().revision());assertEquals(new VoteStatistics(25,0,25,25),view.statistics());
        }
    }
    static void limits(StorageBackend backend) throws Exception {
        UUID voter=UUID.randomUUID(),target=UUID.randomUUID();backend.vote("global",request(voter,target,VoteType.POSITIVE,"first"),facts(),rules(3,7200),NOW);
        assertEquals(VoteRejectedException.Code.COOLDOWN,assertThrows(VoteRejectedException.class,()->backend.vote("global",request(voter,UUID.randomUUID(),VoteType.POSITIVE,"second"),facts(),rules(3,7200),NOW.plusSeconds(7199))).code());
        backend.vote("global",request(voter,target,VoteType.NEGATIVE,"replace"),facts(),rules(3,7200),NOW.plusSeconds(7200));
        backend.vote("global",new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.REVOKE,null,"",""),facts(),rules(3,7200),NOW.plusSeconds(14400));
        assertEquals(VoteRejectedException.Code.DAILY_LIMIT,assertThrows(VoteRejectedException.class,()->backend.vote("global",request(voter,target,VoteType.POSITIVE,"again"),facts(),rules(3,7200),NOW.plusSeconds(21600))).code());
        assertEquals(3,backend.votingHistory(voter,"global",VotePage.Direction.GIVEN,1,10).totalEntries());
        backend.vote("global",request(voter,target,VoteType.POSITIVE,"next day"),facts(),rules(3,7200),NOW.plusSeconds(86400));
    }
}
