package org.dextur.reputation.api;

import java.util.List;

public record VotePage(List<Item> entries, int page, int pageSize, long totalEntries) {
    public enum Direction { RECEIVED, GIVEN }
    public record Item(VoteEntry entry, boolean active) { }
    public VotePage { entries = List.copyOf(entries); }
}
