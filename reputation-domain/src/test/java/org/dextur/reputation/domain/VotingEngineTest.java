package org.dextur.reputation.domain;

import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.dextur.reputation.api.VoteRejectedException.Code.*;

class VotingEngineTest {
    final UUID voter=UUID.randomUUID(),target=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-18T12:00:00Z");
    VotingRules rules(long play,long age,long cooldown,long pair,int limit,VotingRules.RepeatPolicy repeat,boolean revoke) {
        return new VotingRules(play,age,cooldown,pair,limit,List.of(new VotingRules.Threshold(100,10)),ZoneId.of("UTC"),0,repeat,revoke,20,2,3,
                Map.of("test",new VotingRules.Reason("test","test",Set.of(VoteType.values()))));
    }
    VotingRules rules() { return rules(108000,604800,7200,7200,3,VotingRules.RepeatPolicy.REPLACE,true); }
    VoterFacts facts() { return new VoterFacts(true,true,108000,now.minusSeconds(604800),true,true,true); }
    ReputationSnapshot profile(UUID id,long community,long system,long revision) { return new ReputationSnapshot(id,"global",community,system,community+system,"NEUTRAL",revision,now.minusSeconds(1000000),now); }
    VotingEngine.Context context(VoteEntry previous,Instant last,int used) {
        return new VotingEngine.Context(profile(voter,0,0,0),new ReputationView(profile(target,previous==null?7:previous.communityAfter(),5,previous==null?0:previous.sequence()),new VoteStatistics(0,0,0,0)),previous,last,used,previous==null?1:previous.sequence()+1);
    }
    VoteRequest request(VoteType type,String comment) { return new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.SUBMIT,type,"test",comment); }
    void rejects(VoteRejectedException.Code code,VoteRequest request,VoterFacts facts,VotingRules rules,VotingEngine.Context context) {
        assertEquals(code,assertThrows(VoteRejectedException.class,()->VotingEngine.evaluate("global",request,facts,rules,now,context)).code());
    }
    @Test void deterministicWeightsReplaceAndRevokePreserveBaselineAndSystem() {
        var initial=VotingEngine.evaluate("global",request(VoteType.POSITIVE,"a"),facts(),rules(),now,context(null,null,0));
        assertEquals(9,initial.profile().communityScore());assertEquals(14,initial.profile().visibleScore());assertEquals(1,initial.profile().revision());
        var open=rules(0,0,0,0,10,VotingRules.RepeatPolicy.REPLACE,true);
        var replace=VotingEngine.evaluate("global",request(VoteType.NEGATIVE,"b"),facts(),open,now,context(initial.entry(),now,1));
        assertEquals(4,replace.profile().communityScore());assertEquals(VoteEntry.Action.REPLACE,replace.entry().action());assertEquals(initial.entry().operationId(),replace.entry().previousOperationId());
        var revoke=new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.REVOKE,null,"","why");
        var result=VotingEngine.evaluate("global",revoke,facts(),open,now,context(replace.entry(),now,2));
        assertEquals(7,result.profile().communityScore());assertEquals(12,result.profile().visibleScore());assertEquals(0,result.entry().effectiveWeight());
        assertEquals(new VoteStatistics(0,0,1,3),VotingEngine.statistics(List.of(initial.entry(),replace.entry(),result.entry()),target,"global"));
    }
    @Test void eligibilityAndPermissionsCannotBeSuppliedThroughRequest() {
        var request=request(VoteType.POSITIVE,"");var c=context(null,null,0);
        rejects(SELF,new VoteRequest(UUID.randomUUID(),voter,voter,VoteRequest.Action.SUBMIT,VoteType.POSITIVE,"test",""),facts(),rules(),c);
        rejects(OFFLINE,request,new VoterFacts(false,true,108000,now.minusSeconds(604800),true,true,true),rules(),c);
        rejects(UNKNOWN_PLAYER,request,new VoterFacts(true,false,108000,now.minusSeconds(604800),true,true,true),rules(),c);
        rejects(PERMISSION,request,new VoterFacts(true,true,108000,now.minusSeconds(604800),false,true,true),rules(),c);
        rejects(PLAYTIME,request,new VoterFacts(true,true,107999,now.minusSeconds(604800),true,true,true),rules(),c);
        rejects(ACCOUNT_AGE,request,new VoterFacts(true,true,108000,now.minusSeconds(604799),true,true,true),rules(),c);
        assertNotNull(VotingEngine.evaluate("global",request,facts(),rules(),now,c));
    }
    @Test void cooldownPairCooldownDailyAndSameVoteAreIndependent() {
        var c=context(null,now.minusSeconds(7199),0);rejects(COOLDOWN,request(VoteType.POSITIVE,""),facts(),rules(),c);
        var error=assertThrows(VoteRejectedException.class,()->VotingEngine.evaluate("global",request(VoteType.POSITIVE,""),facts(),rules(),now,c));assertEquals(1,error.remainingSeconds());
        rejects(DAILY_LIMIT,request(VoteType.POSITIVE,""),facts(),rules(),context(null,now.minusSeconds(7200),3));
        var entry=VotingEngine.evaluate("global",request(VoteType.POSITIVE,""),facts(),rules(),now,context(null,null,0)).entry();
        rejects(PAIR_COOLDOWN,request(VoteType.NEGATIVE,""),facts(),rules(0,0,0,1,10,VotingRules.RepeatPolicy.REPLACE,true),context(entry,null,0));
        rejects(SAME_VOTE,request(VoteType.POSITIVE,""),facts(),rules(),context(entry,null,0));
        rejects(REPEAT_DISABLED,request(VoteType.NEGATIVE,""),facts(),rules(0,0,0,0,10,VotingRules.RepeatPolicy.ONCE,true),context(entry,null,0));
        var revoke=new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.REVOKE,null,"","");
        rejects(NO_ACTIVE_VOTE,revoke,facts(),rules(),context(null,null,0));
        rejects(REVOKE_DISABLED,revoke,facts(),rules(0,0,0,0,10,VotingRules.RepeatPolicy.REPLACE,false),context(entry,null,0));
    }
    @Test void validatesReasonsCommentsAndIdempotencyBinding() {
        rejects(REASON,new VoteRequest(UUID.randomUUID(),voter,target,VoteRequest.Action.SUBMIT,VoteType.POSITIVE,"missing",""),facts(),rules(),context(null,null,0));
        for(String comment:List.of("x".repeat(21),"a\nb","a\u0000b","\ud800")) rejects(COMMENT,request(VoteType.POSITIVE,comment),facts(),rules(),context(null,null,0));
        var r=request(VoteType.POSITIVE,"<red>literal</red>");var e=VotingEngine.evaluate("global",r,facts(),rules(),now,context(null,null,0)).entry();
        VotingEngine.checkDuplicate("global",r,e);
        assertThrows(VoteRejectedException.class,()->VotingEngine.checkDuplicate("other",r,e));
        assertThrows(VoteRejectedException.class,()->VotingEngine.checkDuplicate("global",new VoteRequest(r.operationId(),voter,target,VoteRequest.Action.SUBMIT,VoteType.NEGATIVE,"test",r.comment()),e));
    }
    @Test void quotaResetsByConfiguredLocalCalendarAcrossDst() {
        var rules=new VotingRules(0,0,0,0,3,List.of(new VotingRules.Threshold(25,4),new VotingRules.Threshold(1000,10)),ZoneId.of("Europe/Berlin"),4,VotingRules.RepeatPolicy.REPLACE,true,20,1,1,Map.of("test",new VotingRules.Reason("test","test",Set.of(VoteType.values()))));
        assertEquals(3,rules.dailyLimit(24));assertEquals(4,rules.dailyLimit(25));assertEquals(10,rules.dailyLimit(1000));
        Instant transition=Instant.parse("2026-03-29T01:30:00Z");
        assertEquals(23,Duration.between(rules.dayStart(transition),rules.dayEnd(transition)).toHours());
        assertTrue(rules.dayStart(transition).isBefore(transition));assertTrue(rules.dayEnd(transition).isAfter(transition));
    }
}
