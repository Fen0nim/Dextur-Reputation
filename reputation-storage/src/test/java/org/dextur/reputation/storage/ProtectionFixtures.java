package org.dextur.reputation.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.application.*;
import org.dextur.reputation.domain.*;
import org.dextur.reputation.domain.protection.*;

final class ProtectionFixtures {
    static final Instant NOW=Instant.parse("2026-10-19T12:00:00.123456Z");
    static ProtectionRules rules(Path directory,boolean burst) throws Exception {
        Files.createDirectories(directory);
        for(String name:List.of("trust.yml","anti-abuse.yml")) try(var in=ProtectionFixtures.class.getResourceAsStream("/"+name)) { Files.write(directory.resolve(name),Objects.requireNonNull(in).readAllBytes()); }
        Path file=directory.resolve("anti-abuse.yml");String text=Files.readString(file).replace("enabled: true","enabled: false").replace("base: {like: 1.0, dislike: 1.0}","base: {like: 0.35, dislike: 0.35}");
        // Fractional base remains enabled; confidence factors are independently disabled.
        text=text.replace("voting-weight:\n  enabled: false","voting-weight:\n  enabled: true");
        if(burst) text=text.replace("burst-protection:\n  enabled: false","burst-protection:\n  enabled: true").replace("suspicious-count: 10","suspicious-count: 2");
        Files.writeString(file,text);return ProtectionConfiguration.load(directory);
    }
    static ProtectionRules defaults(Path directory) throws Exception {
        rules(directory,false);try(var in=ProtectionFixtures.class.getResourceAsStream("/anti-abuse.yml")) { Files.write(directory.resolve("anti-abuse.yml"),in.readAllBytes()); }
        return ProtectionConfiguration.load(directory);
    }
    static VotingRules eligibility() { return VotingContract.rules(10000,0); }
    static VoterFacts mature() { return new VoterFacts(true,true,720000,NOW.minusSeconds(90*86400L),true,true,true); }
    static VoterFacts young() { return new VoterFacts(true,true,0,NOW.minusSeconds(1),true,true,true); }
    static VoteRequest vote(UUID author,UUID target,VoteType type,String comment) { return VotingContract.request(author,target,type,comment); }
}
