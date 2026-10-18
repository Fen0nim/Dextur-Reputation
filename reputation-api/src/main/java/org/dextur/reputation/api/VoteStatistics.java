package org.dextur.reputation.api;

/** Positive/negative = active votes; uniqueVoters = distinct historical voters; historyEntries includes revocations. */
public record VoteStatistics(long positive, long negative, long uniqueVoters, long historyEntries) {
    public VoteStatistics {
        if (positive < 0 || negative < 0 || uniqueVoters < positive + negative || historyEntries < uniqueVoters)
            throw new IllegalArgumentException("Invalid vote statistics");
    }
}
