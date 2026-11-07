package org.dextur.reputation.storage;

import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;
import static org.junit.jupiter.api.Assertions.*;

final class ReasonHistoryContract {
    static final UUID TARGET=UUID.fromString("00000000-0000-0000-0000-000000004444");
    static void check(StorageBackend b)throws Exception {
        var a=UUID.randomUUID();var second=UUID.randomUUID();var reasons=new HashMap<String,VotingRules.Reason>();
        for(String id:List.of("friendly","rude","archived"))reasons.put(id,new VotingRules.Reason(id,"reason-"+id,Set.of(VoteType.values())));
        var old=VotingContract.rules(100,0);var rules=new VotingRules(0,0,0,0,100,List.of(),ZoneId.of("UTC"),0,old.repeatPolicy(),true,250,1,1,reasons);
        var now=VotingContract.NOW;
        b.vote("global",vote(a,VoteType.POSITIVE,"friendly"),VotingContract.facts(),rules,now);
        b.vote("global",vote(a,VoteType.NEGATIVE,"rude"),VotingContract.facts(),rules,now.plusSeconds(1));
        b.vote("global",new VoteRequest(UUID.randomUUID(),a,TARGET,VoteRequest.Action.REVOKE,null,"",""),VotingContract.facts(),rules,now.plusSeconds(2));
        b.vote("global",vote(second,VoteType.POSITIVE,"friendly"),VotingContract.facts(),rules,now.plusSeconds(3));
        b.vote("global",vote(a,VoteType.POSITIVE,"archived"),VotingContract.facts(),rules,now.plusSeconds(4));
        var before=b.snapshot();var all=b.reasonHistory(TARGET,"global",query(null,null,false,1,2));
        assertEquals(5,all.total());assertEquals(2,all.entries().size());assertEquals("archived",all.entries().getFirst().entry().reason());
        assertEquals(new ReasonHistory.Group("friendly",VoteType.POSITIVE,1,2),all.groups().stream().filter(g->g.reason().equals("friendly")).findFirst().orElseThrow());
        assertEquals(new ReasonHistory.Group("rude",VoteType.NEGATIVE,0,2),all.groups().stream().filter(g->g.reason().equals("rude")).findFirst().orElseThrow());
        assertEquals(2,b.reasonHistory(TARGET,"global",query(null,null,true,1,36)).total());
        assertEquals(2,b.reasonHistory(TARGET,"global",query(VoteType.NEGATIVE,null,false,1,36)).total());
        assertEquals(1,b.reasonHistory(TARGET,"global",query(VoteType.POSITIVE,"friendly",true,1,36)).total());
        assertEquals(1,b.reasonHistory(TARGET,"global",query(null,"archived",false,1,36)).total()); // No configuration lookup: removed reasons survive.
        assertEquals(1,b.reasonHistory(TARGET,"global",query(null,null,false,3,2)).entries().size());
        assertEquals(0,b.reasonHistory(TARGET,"other",query(null,null,false,1,36)).total());
        assertEquals(1,b.reasonHistory(second,"global",new ReasonHistory.Query(VotePage.Direction.GIVEN,null,null,false,1,36)).total());
        assertEquals(before,b.snapshot()); // Read-only projection does not alter score, Trust or audit.
    }
    static VoteRequest vote(UUID voter,VoteType type,String reason){return new VoteRequest(UUID.randomUUID(),voter,TARGET,VoteRequest.Action.SUBMIT,type,reason,"");}
    static ReasonHistory.Query query(VoteType type,String reason,boolean active,int page,int size){return new ReasonHistory.Query(VotePage.Direction.RECEIVED,type,reason,active,page,size);}
}
