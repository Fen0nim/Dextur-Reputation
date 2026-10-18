package org.dextur.reputation.api;

/** Duplicate receipts acknowledge an existing operation without applying it or publishing another event. */
public record VoteReceipt(VoteEntry entry, boolean duplicate, ReputationView currentView) { }
