package org.dextur.reputation.domain;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.dextur.reputation.api.*;
import static org.dextur.reputation.api.VoteRejectedException.Code.*;

/** Deterministic Phase 2 calculation v1. No Trust, inferred abuse detection or tier algorithms. */
public final class VotingEngine {
    public record Context(ReputationSnapshot voter, ReputationView target,
            VoteEntry lastPair, Instant lastGlobal, int usedToday, long sequence) { }
    public record Mutation(VoteEntry entry, ReputationSnapshot profile) { }
    private VotingEngine() { }
    public static String hash(String scope, VoteRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
            for (String value : List.of(scope, request.voterId().toString(), request.targetId().toString(), request.action().name(),
                    request.type() == null ? "" : request.type().name(), request.reason(), request.comment())) {
                byte[] field = value.getBytes(StandardCharsets.UTF_8); output.writeInt(field.length); output.write(field);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static void checkDuplicate(String scope, VoteRequest request, VoteEntry existing) {
        if (!hash(scope, request).equals(existing.requestHash())) throw new VoteRejectedException(ID_CONFLICT);
    }
    public static Mutation evaluate(String scope, VoteRequest request, VoterFacts facts, VotingRules rules, Instant now, Context context) {
        if (request.voterId().equals(request.targetId())) throw new VoteRejectedException(SELF);
        if (facts == null || !facts.online()) throw new VoteRejectedException(OFFLINE);
        if (!facts.targetKnown()) throw new VoteRejectedException(UNKNOWN_PLAYER);
        if ((request.action() == VoteRequest.Action.REVOKE ? !facts.revokeAllowed()
                : request.type() == VoteType.POSITIVE ? !facts.positiveAllowed() : !facts.negativeAllowed())) throw new VoteRejectedException(PERMISSION);
        if (request.comment().codePointCount(0, request.comment().length()) > rules.maximumCommentLength()
                || request.comment().codePoints().anyMatch(c -> Character.isISOControl(c) || (c >= 0xd800 && c <= 0xdfff))) throw new VoteRejectedException(COMMENT);
        if (facts.playtimeSeconds() < rules.minimumPlaytimeSeconds()) throw new VoteRejectedException(PLAYTIME);
        if (facts.firstJoined() == null || facts.firstJoined().isAfter(now)
                || facts.firstJoined().plusSeconds(rules.minimumAgeSeconds()).isAfter(now)) throw new VoteRejectedException(ACCOUNT_AGE);
        VoteEntry previous = context.lastPair();
        VoteEntry active = previous != null && previous.action() != VoteEntry.Action.REVOKE ? previous : null;
        if (request.action() == VoteRequest.Action.REVOKE) {
            if (!rules.allowRevoke()) throw new VoteRejectedException(REVOKE_DISABLED);
            if (active == null) throw new VoteRejectedException(NO_ACTIVE_VOTE);
        } else {
            var reason = rules.reasons().get(request.reason());
            if (reason == null || !reason.types().contains(request.type())) throw new VoteRejectedException(REASON);
            if (previous != null && rules.repeatPolicy() == VotingRules.RepeatPolicy.ONCE) throw new VoteRejectedException(REPEAT_DISABLED);
            if (active != null && active.type() == request.type() && active.reason().equals(request.reason()) && active.comment().equals(request.comment())) throw new VoteRejectedException(SAME_VOTE);
        }
        cooldown(context.lastGlobal(), rules.cooldownSeconds(), now, COOLDOWN);
        if (previous != null) cooldown(previous.createdAt(), rules.pairCooldownSeconds(), now, PAIR_COOLDOWN);
        if (context.usedToday() >= rules.dailyLimit(context.voter().visibleScore())) throw new VoteRejectedException(DAILY_LIMIT);
        boolean revoke = request.action() == VoteRequest.Action.REVOKE;
        VoteType type = revoke ? active.type() : request.type();
        long base = revoke ? active.baseWeight() : type == VoteType.POSITIVE ? rules.positiveWeight() : rules.negativeWeight();
        long oldContribution = active == null ? 0 : active.contribution();
        var old = context.target().profile();
        long contribution = revoke ? 0 : Math.multiplyExact(type.sign(), base);
        long community = Math.addExact(Math.subtractExact(old.communityScore(), oldContribution), contribution);
        var entry = new VoteEntry(request.operationId(), context.sequence(), scope, request.voterId(), request.targetId(),
                revoke ? VoteEntry.Action.REVOKE : active == null ? VoteEntry.Action.CAST : VoteEntry.Action.REPLACE,
                type, revoke ? active.reason() : request.reason(), request.comment(), now,
                previous == null ? null : previous.operationId(), base, revoke ? 0 : base, 1, hash(scope, request), old.communityScore(), community);
        var profile = new ReputationSnapshot(old.playerId(), scope, community, old.systemScore(), Math.addExact(community, old.systemScore()),
                old.tier(), Math.incrementExact(old.revision()), old.createdAt(), now.isBefore(old.updatedAt()) ? old.updatedAt() : now);
        return new Mutation(entry, profile);
    }
    private static void cooldown(Instant last, long seconds, Instant now, VoteRejectedException.Code code) {
        if (last != null && now.isBefore(last.plusSeconds(seconds))) {
            Duration duration = Duration.between(now, last.plusSeconds(seconds));
            long remaining = Math.max(1, duration.getSeconds() + (duration.getNano() == 0 ? 0 : 1));
            throw new VoteRejectedException(code, remaining);
        }
    }
    public static VoteStatistics statistics(List<VoteEntry> events, UUID target, String scope) {
        Map<String, VoteEntry> latest = new HashMap<>(); Set<UUID> voters = new HashSet<>(); long total = 0;
        for (var entry : events) if (entry.scope().equals(scope) && entry.targetId().equals(target)) {
            voters.add(entry.voterId()); latest.put(entry.pairKey(), entry); total++;
        }
        long positive = latest.values().stream().filter(e -> e.action() != VoteEntry.Action.REVOKE && e.type() == VoteType.POSITIVE).count();
        long negative = latest.values().stream().filter(e -> e.action() != VoteEntry.Action.REVOKE && e.type() == VoteType.NEGATIVE).count();
        return new VoteStatistics(positive, negative, voters.size(), total);
    }
}
