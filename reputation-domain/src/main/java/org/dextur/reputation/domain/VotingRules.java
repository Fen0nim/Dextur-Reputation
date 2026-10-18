package org.dextur.reputation.domain;

import java.time.*;
import java.util.*;
import org.dextur.reputation.api.VoteType;

public record VotingRules(long minimumPlaytimeSeconds, long minimumAgeSeconds, long cooldownSeconds,
        long pairCooldownSeconds, int baseDailyLimit, List<Threshold> dailyLimits, ZoneId zone,
        int resetHour, RepeatPolicy repeatPolicy, boolean allowRevoke, int maximumCommentLength,
        long positiveWeight, long negativeWeight, Map<String, Reason> reasons) {
    public enum RepeatPolicy { ONCE, REPLACE }
    public record Threshold(long minimumScore, int limit) { }
    public record Reason(String id, String messageKey, Set<VoteType> types) {
        public Reason { types = Set.copyOf(types); }
    }
    public VotingRules {
        dailyLimits = List.copyOf(dailyLimits); reasons = Map.copyOf(reasons);
        Objects.requireNonNull(zone); Objects.requireNonNull(repeatPolicy);
        if (minimumPlaytimeSeconds < 0 || minimumAgeSeconds < 0 || cooldownSeconds < 0 || pairCooldownSeconds < 0
                || baseDailyLimit < 1 || baseDailyLimit > 10000 || resetHour < 0 || resetHour > 23
                || maximumCommentLength < 1 || maximumCommentLength > 2000 || reasons.isEmpty() || reasons.size() > 128
                || positiveWeight < 1 || positiveWeight > 1000000 || negativeWeight < 1 || negativeWeight > 1000000)
            throw new IllegalArgumentException("Invalid voting limits");
        long previous = Long.MIN_VALUE;
        for (Threshold threshold : dailyLimits) {
            if (threshold.minimumScore() <= previous || threshold.limit() < 1 || threshold.limit() > 10000)
                throw new IllegalArgumentException("Daily thresholds must be strictly increasing with valid limits");
            previous = threshold.minimumScore();
        }
        for (var reason : reasons.values()) {
            if (!reason.id().matches("[a-z0-9_-]{1,32}") || !reason.messageKey().matches("[a-z0-9_-]{1,80}")
                    || reason.types().isEmpty() || !reason.id().equals(reasons.get(reason.id()).id())) throw new IllegalArgumentException("Invalid voting reason");
        }
    }
    public int dailyLimit(long score) {
        int result = baseDailyLimit;
        for (var threshold : dailyLimits) if (score >= threshold.minimumScore()) result = threshold.limit();
        return result;
    }
    public Instant dayStart(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate day = local.toLocalDate();
        if (local.toLocalTime().isBefore(LocalTime.of(resetHour, 0))) day = day.minusDays(1);
        return day.atTime(resetHour, 0).atZone(zone).toInstant();
    }
    public Instant dayEnd(Instant now) { return dayStart(now).atZone(zone).toLocalDate().plusDays(1).atTime(resetHour, 0).atZone(zone).toInstant(); }
}
