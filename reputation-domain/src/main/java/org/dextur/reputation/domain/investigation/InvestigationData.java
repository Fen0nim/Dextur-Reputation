package org.dextur.reputation.domain.investigation;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;

/** Internal moderation DTOs. Never registered as a player-facing service. */
public final class InvestigationData {
    private InvestigationData() { }
    public enum Action { INVALIDATE, RESTORE, APPROVE, REJECT, RECALCULATE_REPUTATION, RECALCULATE_TRUST, RECALCULATE_TIER, RECALCULATE_ALL, RESOLVE_FLAG }
    public enum Kind { MODERATION, AUDIT, FLAG, BULK, DIAGNOSTIC }
    public enum Section { INSPECT, VOTES, ADMIN, TRUST, RECOVERY, FLAGS, RELATIONS, AUDIT, PENDING, VOTE, OPERATION, BULKS, RECALCULATE, TRUST_HISTORY }
    public record Rules(int pageSize,int maximumBulk,int batchSize,int confirmThreshold,int tokenSeconds,int maximumTokens,
            int relationEvents,int relationNeighbors,int graphBudget,int recalculationEvents,int diagnosticRetentionDays,
            int maximumRecords,int jsonWarningPercent,boolean requireReason,boolean consoleAllowed) {
        public Rules {
            if(pageSize<1||pageSize>28||maximumBulk<1||maximumBulk>1000||batchSize<1||batchSize>50||batchSize>maximumBulk||confirmThreshold<1||confirmThreshold>maximumBulk||tokenSeconds<5||tokenSeconds>600||maximumTokens<1||maximumTokens>4096||relationEvents<1||relationEvents>10000||relationNeighbors<1||relationNeighbors>100||graphBudget<1||graphBudget>100000||recalculationEvents<1||recalculationEvents>100000||diagnosticRetentionDays<1||diagnosticRetentionDays>3650||maximumRecords<100||maximumRecords>1000000||jsonWarningPercent<10||jsonWarningPercent>95)throw new IllegalArgumentException("INV_CONFIGURATION");
        }
    }
    public record Filter(UUID voter,UUID target,VoteType type,String status,Instant before,Instant after,String severity,String source,UUID flag,String search) {
        public Filter(UUID voter,UUID target,VoteType type,String status,Instant before,Instant after,String severity,String source,UUID flag){this(voter,target,type,status,before,after,severity,source,flag,"");}
        public Filter { search=clean(search,128);status=word(status);severity=word(severity);source=word(source);if(!status.isEmpty()&&!Set.of("ACCEPTED","SUSPICIOUS","PENDING","INVALIDATED","SUPERSEDED","REVOKED","SUSPICION","PENDING_REVIEW","CONFIRMED_ABUSE","FALSE_POSITIVE","APPLIED","NO_CHANGE","RUNNING","PAUSED","COMPLETED","CANCELLED").contains(status)||!severity.isEmpty()&&!Set.of("LOW","MEDIUM","HIGH").contains(severity))throw new IllegalArgumentException("INV_FILTER");if(before!=null&&after!=null&&before.isBefore(after))throw new IllegalArgumentException("INV_FILTER"); }
        public static Filter empty(){return new Filter(null,null,null,"",null,null,"","",null);}
        public boolean scoped(){return voter!=null||target!=null||flag!=null;}
    }
    public record Query(Section section,String scope,UUID player,UUID id,Filter filter,int page,int size) {
        public Query { Objects.requireNonNull(section);checkScope(scope);Objects.requireNonNull(filter);if(page<1||page>1000000||size<1||size>28)throw new IllegalArgumentException("INV_PAGE"); }
    }
    public record Row(UUID id,Map<String,String> values) { public Row {Objects.requireNonNull(id);values=Map.copyOf(values);} }
    public record Report(Map<String,String> summary,List<Row> rows,long total,String fingerprint) {
        public Report { summary=Map.copyOf(summary);rows=List.copyOf(rows);Objects.requireNonNull(fingerprint); }
    }
    public record Request(UUID operation,String scope,Action action,UUID subject,String actor,String reason,long expectedRevision,String resolution,Instant at) {
        public Request { Objects.requireNonNull(operation);checkScope(scope);Objects.requireNonNull(action);Objects.requireNonNull(subject);checkActor(actor);reason=clean(reason,1000);resolution=word(resolution);Objects.requireNonNull(at);if(expectedRevision< -1)throw new IllegalArgumentException("INV_REVISION");if(action==Action.RESOLVE_FLAG&&!Set.of("SUSPICION","PENDING_REVIEW","CONFIRMED_ABUSE","FALSE_POSITIVE").contains(resolution))throw new IllegalArgumentException("INV_FLAG_STATE"); }
    }
    /** Canonical indexed durable record; AUDIT entries are append-only. */
    public record Record(UUID id,Kind kind,String scope,String actor,UUID target,UUID vote,String status,long revision,Instant at,Map<String,String> values,List<UUID> related) {
        public Record { Objects.requireNonNull(id);Objects.requireNonNull(kind);checkScope(scope);checkActor(actor);Objects.requireNonNull(target);Objects.requireNonNull(vote);status=word(status);Objects.requireNonNull(at);if(revision<1||values.size()>96||related.size()>1000)throw new IllegalArgumentException("INV_RECORD");var copy=new TreeMap<String,String>();values.forEach((k,v)->{if(!k.matches("[a-z0-9_.-]{1,64}"))throw new IllegalArgumentException("INV_KEY");copy.put(k,clean(v,k.equals("plan")?262144:8192));});values=Collections.unmodifiableMap(copy);related=List.copyOf(related);if(new HashSet<>(related).size()!=related.size())throw new IllegalArgumentException("INV_DUPLICATE"); }
    }
    public record Mutation(UUID operation,UUID target,ReputationSnapshot profile,String status,boolean duplicate,List<UUID> votes) {public Mutation {votes=List.copyOf(votes);} }
    public record Plan(Action action,String scope,Filter filter,String actor,String reason,List<UUID> subjects,Map<String,Long> deltas,List<UUID> players,List<String> tiers,int pendingChanges,String fingerprint) {
        public Plan { checkScope(scope);checkActor(actor);reason=clean(reason,1000);subjects=List.copyOf(subjects);deltas=Map.copyOf(deltas);players=List.copyOf(players);tiers=List.copyOf(tiers);if(!Set.of(Action.INVALIDATE,Action.RESTORE,Action.RECALCULATE_ALL).contains(action)||!filter.scoped()||subjects.size()>1000)throw new IllegalArgumentException("INV_SCOPE"); }
    }
    public record Batch(Record operation,List<Mutation> mutations) {public Batch {mutations=List.copyOf(mutations);} }
    public static final UUID NONE=new UUID(0,0);
    public static boolean voteAction(Action action){return Set.of(Action.INVALIDATE,Action.RESTORE,Action.APPROVE,Action.REJECT).contains(action);}
    public static String clean(String text,int maximum){Objects.requireNonNull(text);if(text.length()>maximum||text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\t'))throw new IllegalArgumentException("INV_INPUT");return text.strip();}
    private static String word(String text){Objects.requireNonNull(text);if(!text.matches("[A-Z0-9_]{0,64}"))throw new IllegalArgumentException("INV_INPUT");return text;}
    private static void checkScope(String scope){if(scope==null||!scope.matches("[a-z0-9_-]{1,32}"))throw new IllegalArgumentException("INV_SCOPE");}
    private static void checkActor(String actor){if(!Set.of("CONSOLE","SYSTEM").contains(actor))UUID.fromString(actor);}
}
