package org.dextur.reputation.domain;

import java.util.List;
import org.dextur.reputation.api.*;

/** Read-only journal projection. Active counts use the authoritative active index, never event totals. */
public final class ReasonHistory {
    private ReasonHistory() { }
    public static String canonical(String reason) { return reason == null ? "" : reason; }
    public record Query(VotePage.Direction direction, VoteType type, String reason, boolean activeOnly, int page, int size) {
        public Query {
            if(direction==null || reason!=null&&!reason.matches("[a-z0-9_-]{0,32}") || page<1 || page>1000000 || size<1 || size>36)
                throw new VoteRejectedException(VoteRejectedException.Code.INVALID_PAGE);
        }
        public Query page(int value){return new Query(direction,type,reason,activeOnly,value,size);}
    }
    public record Group(String reason, VoteType type, long active, long historical) { }
    public record Page(List<Group> groups,List<VotePage.Item> entries,long total,Query query) {
        public Page { groups=List.copyOf(groups);entries=List.copyOf(entries); }
    }
}
