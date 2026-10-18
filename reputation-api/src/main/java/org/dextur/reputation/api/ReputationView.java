package org.dextur.reputation.api;

/** Profile and voting statistics from one consistent storage read. */
public record ReputationView(ReputationSnapshot profile, VoteStatistics statistics) { }
