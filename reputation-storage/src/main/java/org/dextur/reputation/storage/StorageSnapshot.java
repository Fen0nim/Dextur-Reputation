package org.dextur.reputation.storage;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.domain.PlayerLanguage;

/** Canonically ordered portable representation, including all scopes and preference timestamps. */
public record StorageSnapshot(List<ReputationSnapshot> profiles, List<Preference> preferences,
        List<org.dextur.reputation.api.VoteEntry> votes, ProtectionSnapshot protection) {
    public StorageSnapshot(List<ReputationSnapshot> profiles, List<Preference> preferences, List<org.dextur.reputation.api.VoteEntry> votes) { this(profiles,preferences,votes,ProtectionSnapshot.empty()); }
    public StorageSnapshot(List<ReputationSnapshot> profiles, List<Preference> preferences) { this(profiles, preferences, List.of()); }
    public record Preference(UUID playerId, PlayerLanguage language, Instant updatedAt) {
        public Preference { Objects.requireNonNull(playerId); Objects.requireNonNull(language); Objects.requireNonNull(updatedAt); }
    }
    public StorageSnapshot {
        Objects.requireNonNull(protection);
        Set<String> precise = protection.scores().stream().map(org.dextur.reputation.domain.protection.ProtectionData.Score::key).collect(java.util.stream.Collectors.toSet());
        Set<String> ids = new HashSet<>();
        for (var p : profiles) {
            if (!p.scopeId().matches("[a-z0-9_-]{1,32}") || !p.tier().matches("[A-Z_]{1,32}")
                    || p.revision() < 0 || p.updatedAt().isBefore(p.createdAt())
                    || !ids.add(p.playerId() + "/" + p.scopeId())) throw new IllegalArgumentException("Invalid or duplicate profile");
        }
        Set<UUID> players = new HashSet<>();
        for (var p : preferences) if (!players.add(p.playerId())) throw new IllegalArgumentException("Duplicate preference");
        profiles = profiles.stream().sorted(Comparator.comparing(p -> p.playerId() + "/" + p.scopeId())).toList();
        preferences = preferences.stream().sorted(Comparator.comparing(p -> p.playerId().toString())).toList();
        votes = votes.stream().sorted(Comparator.comparingLong(org.dextur.reputation.api.VoteEntry::sequence)).toList();
        Set<UUID> operations = new HashSet<>();
        Map<String, org.dextur.reputation.api.VoteEntry> pairs = new HashMap<>();
        Map<String, Long> scores = new HashMap<>();
        long sequence = 0;
        for (var vote : votes) {
            String target = vote.targetId() + "/" + vote.scope();
            if (!operations.add(vote.operationId()) || vote.sequence() <= sequence
                    || !ids.contains(target) || !ids.contains(vote.voterId() + "/" + vote.scope())) throw new IllegalArgumentException("Invalid vote journal identity");
            var request = new org.dextur.reputation.api.VoteRequest(vote.operationId(),vote.voterId(),vote.targetId(),
                    vote.action()==org.dextur.reputation.api.VoteEntry.Action.REVOKE ? org.dextur.reputation.api.VoteRequest.Action.REVOKE : org.dextur.reputation.api.VoteRequest.Action.SUBMIT,
                    vote.action()==org.dextur.reputation.api.VoteEntry.Action.REVOKE ? null : vote.type(),
                    vote.action()==org.dextur.reputation.api.VoteEntry.Action.REVOKE ? "" : vote.reason(),vote.comment());
            if (!org.dextur.reputation.domain.VotingEngine.hash(vote.scope(),request).equals(vote.requestHash())
                    || (vote.calculationVersion()==1 && vote.action()!=org.dextur.reputation.api.VoteEntry.Action.REVOKE && vote.effectiveWeight()!=vote.baseWeight()))
                throw new IllegalArgumentException("Invalid vote request hash or base calculation");
            var previous = pairs.get(vote.pairKey());
            if (!Objects.equals(vote.previousOperationId(), previous == null ? null : previous.operationId())
                    || (vote.action() == org.dextur.reputation.api.VoteEntry.Action.CAST) != (previous == null || previous.action() == org.dextur.reputation.api.VoteEntry.Action.REVOKE))
                throw new IllegalArgumentException("Invalid vote journal chain");
            long contribution = previous == null || previous.action() == org.dextur.reputation.api.VoteEntry.Action.REVOKE ? 0 : previous.contribution();
            if (!precise.contains(target) && (Math.addExact(Math.subtractExact(vote.communityBefore(), contribution), vote.contribution()) != vote.communityAfter()
                    || (scores.containsKey(target) && scores.get(target) != vote.communityBefore()))) throw new IllegalArgumentException("Inconsistent vote calculation");
            scores.put(target, vote.communityAfter()); pairs.put(vote.pairKey(), vote); sequence = vote.sequence();
        }
        for (var profile : profiles) {
            Long score = scores.get(profile.playerId() + "/" + profile.scopeId());
            if (!precise.contains(profile.playerId()+"/"+profile.scopeId()) && score != null && (score != profile.communityScore() || Math.addExact(profile.communityScore(),profile.systemScore()) != profile.visibleScore())) throw new IllegalArgumentException("Profile/journal mismatch");
        }
        ProtectionValidation.validate(profiles,votes,protection);
    }
    public static StorageSnapshot empty() { return new StorageSnapshot(List.of(), List.of()); }
    public boolean isEmpty() { return profiles.isEmpty() && preferences.isEmpty() && votes.isEmpty() && protection.isEmpty(); }
    static Instant now() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
