package org.dextur.reputation.storage;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.*;
import org.dextur.reputation.domain.policy.*;
import static org.junit.jupiter.api.Assertions.*;

final class InitialEligibilityContract {
    static final UUID VOTER=UUID.fromString("aaa12345-0000-4000-8000-000000000001");
    static final Instant NOW=ProtectionFixtures.NOW;
    static void configure(StorageBackend backend,Path directory)throws Exception {
        backend.tiers(TierConfiguration.load(directory));backend.features(PolicyConfiguration.load(directory));
    }
    static void force(StorageBackend backend,UUID voter,boolean enabled,Instant now)throws Exception {
        backend.administer(PolicyContract.override(voter,InitialEligibility.KEY,enabled?AdministrationData.Override.FORCE_ALLOW:AdministrationData.Override.DEFAULT,now));
    }
    static VotingRules rules(int daily,long cooldown,long pair,VotingRules.RepeatPolicy repeat) {
        var base=VotingContract.rules(daily,cooldown);
        return new VotingRules(18000,86400,cooldown,pair,daily,List.of(),base.zone(),0,repeat,true,250,1,1,base.reasons());
    }
    static void check(StorageBackend backend,Path directory)throws Exception {
        configure(backend,directory);var rules=rules(2,60,0,VotingRules.RepeatPolicy.REPLACE);var facts=ProtectionFixtures.young();UUID target=UUID.randomUUID();
        assertEquals(VoteRejectedException.Code.PLAYTIME,assertThrows(VoteRejectedException.class,()->backend.vote("global",VotingContract.request(VOTER,target,VoteType.POSITIVE,"before"),facts,rules,NOW)).code());
        force(backend,VOTER,true,NOW);assertTrue(InitialEligibility.enabled(backend.snapshot().administration().get(VOTER,"global")));
        var first=backend.vote("global",VotingContract.request(VOTER,target,VoteType.POSITIVE,"first"),facts,rules,NOW);
        assertFalse(first.duplicate());
        assertEquals(VoteRejectedException.Code.COOLDOWN,assertThrows(VoteRejectedException.class,()->backend.vote("global",VotingContract.request(VOTER,UUID.randomUUID(),VoteType.POSITIVE,"cooldown"),facts,rules,NOW.plusSeconds(1))).code());
        // VoteRequest itself permits self requests; the voting engine must continue rejecting them.
        var self=new VoteRequest(UUID.randomUUID(),VOTER,VOTER,VoteRequest.Action.SUBMIT,VoteType.POSITIVE,"test","");
        assertEquals(VoteRejectedException.Code.SELF,assertThrows(VoteRejectedException.class,()->backend.vote("global",self,facts,rules,NOW.plusSeconds(61))).code());
        assertEquals(VoteRejectedException.Code.REPEAT_DISABLED,assertThrows(VoteRejectedException.class,()->backend.vote("global",VotingContract.request(VOTER,target,VoteType.NEGATIVE,"repeat"),facts,rules(10,0,0,VotingRules.RepeatPolicy.ONCE),NOW.plusSeconds(61))).code());
        assertEquals(VoteRejectedException.Code.PAIR_COOLDOWN,assertThrows(VoteRejectedException.class,()->backend.vote("global",VotingContract.request(VOTER,target,VoteType.NEGATIVE,"pair"),facts,rules(10,0,120,VotingRules.RepeatPolicy.REPLACE),NOW.plusSeconds(61))).code());
        assertEquals(VoteRejectedException.Code.PLAYTIME,assertThrows(VoteRejectedException.class,()->backend.vote("other",VotingContract.request(VOTER,target,VoteType.POSITIVE,"scope"),facts,rules,NOW.plusSeconds(61))).code());
        assertEquals(VoteRejectedException.Code.PLAYTIME,assertThrows(VoteRejectedException.class,()->backend.vote("global",VotingContract.request(UUID.randomUUID(),target,VoteType.POSITIVE,"other player"),facts,rules,NOW.plusSeconds(61))).code());
        var denied=new VoterFacts(true,true,0,NOW.minusSeconds(1),false,false,false);
        assertEquals(VoteRejectedException.Code.PERMISSION,assertThrows(VoteRejectedException.class,()->backend.vote("global",VotingContract.request(VOTER,target,VoteType.NEGATIVE,"permission"),denied,rules,NOW.plusSeconds(61))).code());
        backend.vote("global",VotingContract.request(VOTER,UUID.randomUUID(),VoteType.NEGATIVE,"second"),facts,rules,NOW.plusSeconds(61));
        assertEquals(VoteRejectedException.Code.DAILY_LIMIT,assertThrows(VoteRejectedException.class,()->backend.vote("global",VotingContract.request(VOTER,UUID.randomUUID(),VoteType.POSITIVE,"daily"),facts,rules,NOW.plusSeconds(122))).code());
        backend.administer(PolicyContract.admin(VOTER,AdministrationData.Type.LOCK,0,NOW.plusSeconds(123)));
        assertThrows(PolicyDeniedException.class,()->backend.vote("global",VotingContract.request(VOTER,target,VoteType.NEGATIVE,"lock"),facts,rules,NOW.plusSeconds(124)));
        backend.administer(PolicyContract.admin(VOTER,AdministrationData.Type.UNLOCK,0,NOW.plusSeconds(125)));force(backend,VOTER,false,NOW.plusSeconds(126));
        assertEquals(VoteRejectedException.Code.PLAYTIME,assertThrows(VoteRejectedException.class,()->backend.vote("global",VotingContract.request(VOTER,target,VoteType.NEGATIVE,"off"),facts,rules,NOW.plusSeconds(200))).code());
        var audit=backend.snapshot().administration().get(VOTER,"global").audit();assertEquals(4,audit.size());assertEquals(InitialEligibility.KEY,audit.getFirst().request().policy());
    }
}
