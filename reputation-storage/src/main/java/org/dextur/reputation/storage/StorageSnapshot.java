package org.dextur.reputation.storage;

import java.time.Instant;
import java.util.*;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.domain.PlayerLanguage;

/** Canonically ordered portable representation, including all scopes and preference timestamps. */
public record StorageSnapshot(List<ReputationSnapshot> profiles, List<Preference> preferences) {
    public record Preference(UUID playerId, PlayerLanguage language, Instant updatedAt) {
        public Preference { Objects.requireNonNull(playerId); Objects.requireNonNull(language); Objects.requireNonNull(updatedAt); }
    }
    public StorageSnapshot {
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
    }
    public static StorageSnapshot empty() { return new StorageSnapshot(List.of(), List.of()); }
    public boolean isEmpty() { return profiles.isEmpty() && preferences.isEmpty(); }
    static Instant now() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
