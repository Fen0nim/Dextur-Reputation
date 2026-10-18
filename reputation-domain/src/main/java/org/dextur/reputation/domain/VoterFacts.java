package org.dextur.reputation.domain;

import java.time.Instant;

/** Captured by the trusted platform adapter, never from command arguments. Age means server first-join age. */
public record VoterFacts(boolean online, boolean targetKnown, long playtimeSeconds, Instant firstJoined,
        boolean positiveAllowed, boolean negativeAllowed, boolean revokeAllowed) { }
