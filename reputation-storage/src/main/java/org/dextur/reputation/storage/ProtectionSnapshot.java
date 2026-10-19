package org.dextur.reputation.storage;

import java.util.*;
import org.dextur.reputation.domain.protection.ProtectionData.*;

/** Complete private portable state, included in the same atomic JSON/SQL migration unit. */
public record ProtectionSnapshot(List<Trust> trusts,List<Pair> pairs,List<Score> scores,List<Assessment> assessments,List<Audit> audit,List<ScopeState> scopes) {
    public ProtectionSnapshot {
        trusts=unique(trusts,Trust::key);pairs=unique(pairs,Pair::key);scores=unique(scores,Score::key);
        assessments=unique(assessments,a->a.voteId().toString());scopes=unique(scopes,ScopeState::scope);
        audit=audit.stream().sorted(Comparator.comparing(Audit::scope).thenComparingLong(Audit::sequence)).toList();
        Set<UUID> ids=new HashSet<>();Map<String,Long> sequence=new HashMap<>();Map<String,Long> targetScores=new HashMap<>();
        for(var event:audit) {
            if(!ids.add(event.id())||event.sequence()!=sequence.getOrDefault(event.scope(),0L)+1) throw new IllegalArgumentException("Private audit identity/sequence mismatch");
            String target=event.target()+"/"+event.scope();
            if(targetScores.containsKey(target)&&targetScores.get(target)!=event.beforeUnits()) throw new IllegalArgumentException("Private audit score chain mismatch");
            targetScores.put(target,event.afterUnits());sequence.put(event.scope(),event.sequence());
        }
        for(var score:scores) if(!Objects.equals(targetScores.get(score.key()),score.currentUnits())) throw new IllegalArgumentException("Precise score/audit mismatch");
        for(var scope:scopes) if(scope.nextAudit()!=sequence.getOrDefault(scope.scope(),0L)+1||scope.revision()!=scope.nextAudit()-1) throw new IllegalArgumentException("Private audit metadata mismatch");
    }
    private static <T> List<T> unique(List<T> input,java.util.function.Function<T,String> key) {
        var rows=new TreeMap<String,T>();for(T item:input) if(rows.put(key.apply(item),item)!=null) throw new IllegalArgumentException("Duplicate private record");return List.copyOf(rows.values());
    }
    public static ProtectionSnapshot empty() { return new ProtectionSnapshot(List.of(),List.of(),List.of(),List.of(),List.of(),List.of()); }
    public boolean isEmpty() { return trusts.isEmpty()&&pairs.isEmpty()&&scores.isEmpty()&&assessments.isEmpty()&&audit.isEmpty()&&scopes.isEmpty(); }
    public ProtectionSnapshot apply(Change change) {
        var audits=new ArrayList<>(audit);audits.add(change.audit());
        return new ProtectionSnapshot(upsert(trusts,change.trust(),Trust::key),upsert(pairs,change.pair(),Pair::key),upsert(scores,change.score(),Score::key),
                replace(assessments,change.assessments(),a->a.voteId().toString()),audits,upsert(scopes,change.scope(),ScopeState::scope));
    }
    public ProtectionSnapshot analysis(ScopeState scope,Audit event) {
        var events=new ArrayList<>(audit);events.add(event);
        return new ProtectionSnapshot(trusts,pairs,scores,assessments,events,upsert(scopes,scope,ScopeState::scope));
    }
    public ProtectionSnapshot trust(Trust trust) { return new ProtectionSnapshot(upsert(trusts,trust,Trust::key),pairs,scores,assessments,audit,scopes); }
    private static <T> List<T> upsert(List<T> source,T value,java.util.function.Function<T,String> key) { return value==null?source:replace(source,List.of(value),key); }
    private static <T> List<T> replace(List<T> source,List<T> changes,java.util.function.Function<T,String> key) {
        var map=new TreeMap<String,T>();for(T row:source) map.put(key.apply(row),row);for(T row:changes) map.put(key.apply(row),row);return List.copyOf(map.values());
    }
}
