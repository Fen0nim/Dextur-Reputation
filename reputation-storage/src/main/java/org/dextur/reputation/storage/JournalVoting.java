package org.dextur.reputation.storage;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.*;
import org.dextur.reputation.domain.*;

/** In-memory journal projection for JSON and portable snapshot validation. SQL uses indexed queries. */
final class JournalVoting {
    record Result(StorageSnapshot state, VoteReceipt receipt) { }
    private JournalVoting() { }
    static ReputationSnapshot profile(StorageSnapshot state, UUID player, String scope, Instant now) {
        return state.profiles().stream().filter(p -> p.playerId().equals(player) && p.scopeId().equals(scope)).findFirst()
                .orElseGet(() -> new ReputationSnapshot(player, scope, 0, 0, 0, "NEUTRAL", 0, now, now));
    }
    static ReputationView view(StorageSnapshot state, UUID player, String scope, Instant now) {
        var stats=VotingEngine.statistics(state.votes(), player, scope);
        if(!state.protection().isEmpty()) {
            var active=ProtectionValidation.active(state.votes(),state.protection()).values().stream().filter(v->v.scope().equals(scope)&&v.targetId().equals(player)).toList();
            stats=new VoteStatistics(active.stream().filter(v->v.type()==VoteType.POSITIVE).count(),active.stream().filter(v->v.type()==VoteType.NEGATIVE).count(),stats.uniqueVoters(),stats.historyEntries());
        }
        return new ReputationView(profile(state, player, scope, now),stats);
    }
    static Result apply(StorageSnapshot state, String scope, VoteRequest request, VoterFacts facts, VotingRules rules, Instant now) {
        var existing = state.votes().stream().filter(v -> v.operationId().equals(request.operationId())).findFirst().orElse(null);
        if (existing != null) {
            VotingEngine.checkDuplicate(scope, request, existing);
            return new Result(state, new VoteReceipt(existing, true, view(state, request.targetId(), scope, now)));
        }
        var voter = profile(state, request.voterId(), scope, now);
        var target = view(state, request.targetId(), scope, now);
        VoteEntry pair = null; Instant lastGlobal = null; int used = 0;
        Instant start = rules.dayStart(now), end = rules.dayEnd(now);
        for (var entry : state.votes()) if (entry.scope().equals(scope) && entry.voterId().equals(request.voterId())) {
            if (entry.targetId().equals(request.targetId())) pair = entry;
            if (lastGlobal == null || entry.createdAt().isAfter(lastGlobal)) lastGlobal = entry.createdAt();
            if (!entry.createdAt().isBefore(start) && entry.createdAt().isBefore(end)) used++;
        }
        long sequence = state.votes().isEmpty() ? 1 : Math.incrementExact(state.votes().getLast().sequence());
        var mutation = VotingEngine.evaluate(scope, request, facts, rules, now, new VotingEngine.Context(voter, target, pair, lastGlobal, used, sequence));
        var profiles = new ArrayList<>(state.profiles());
        profiles.removeIf(p -> p.scopeId().equals(scope) && (p.playerId().equals(request.voterId()) || p.playerId().equals(request.targetId())));
        profiles.add(voter); profiles.add(mutation.profile());
        var votes = new ArrayList<>(state.votes()); votes.add(mutation.entry());
        var next = new StorageSnapshot(profiles, state.preferences(), votes);
        return new Result(next, new VoteReceipt(mutation.entry(), false, view(next, request.targetId(), scope, now)));
    }
    static VotePage history(StorageSnapshot state, UUID player, String scope, VotePage.Direction direction, int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw new VoteRejectedException(VoteRejectedException.Code.INVALID_PAGE);
        Map<String, VoteEntry> latest = ProtectionValidation.active(state.votes(),state.protection());
        var filtered = state.votes().reversed().stream().filter(v -> v.scope().equals(scope)
                && (direction == VotePage.Direction.RECEIVED ? v.targetId() : v.voterId()).equals(player)).toList();
        return new VotePage(filtered.stream().skip(Math.multiplyExact((long) page - 1, size)).limit(size)
                .map(v -> new VotePage.Item(v, latest.containsKey(v.pairKey()) && latest.get(v.pairKey()).operationId().equals(v.operationId()) && v.action() != VoteEntry.Action.REVOKE)).toList(), page, size, filtered.size());
    }
}
