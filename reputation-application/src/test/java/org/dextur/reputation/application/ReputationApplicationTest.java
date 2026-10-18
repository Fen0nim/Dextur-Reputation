package org.dextur.reputation.application;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.ProfileRepository;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReputationApplicationTest {
    static Settings settings() {
        return new Settings("global", PlayerLanguage.ru_RU, 10, 60, 1, 1, 2, false, null);
    }
    static class Repository implements ProfileRepository {
        final AtomicReference<String> thread = new AtomicReference<>();
        PlayerLanguage language = PlayerLanguage.ru_RU;
        public ReputationSnapshot getOrCreate(UUID id, String scope) {
            thread.set(Thread.currentThread().getName());
            return new ReputationSnapshot(id, scope, 0, 0, 0, "NEUTRAL", 0, Instant.EPOCH, Instant.EPOCH);
        }
        public PlayerLanguage language(UUID id, PlayerLanguage fallback) { return language; }
        public void setLanguage(UUID id, PlayerLanguage value) { language = value; }
    }
    @Test void sqlPortRunsOffCallerAndClosedApiFails() throws Exception {
        Repository repository = new Repository();
        AtomicBoolean cleaned = new AtomicBoolean();
        ReputationApplication app = new ReputationApplication(repository, settings(), () -> cleaned.set(true));
        UUID player = UUID.randomUUID();
        assertEquals(0, app.getProfile(player).toCompletableFuture().get(2, TimeUnit.SECONDS).visibleScore());
        assertTrue(repository.thread.get().startsWith("DexturReputation-db-"));
        assertEquals(0, app.cachedProfile(player).orElseThrow().visibleScore());
        app.close();
        assertTrue(cleaned.get()); assertTrue(app.terminated());
        assertThrows(CompletionException.class, () -> app.getProfile(player).toCompletableFuture().join());
    }
    @Test void saturationRejectsWithoutExecutingOnCallerAndDrainsAcceptedWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ReputationApplication app = new ReputationApplication(new Repository(), settings())) {
            CompletableFuture<Void> active = app.submit(() -> { started.countDown(); release.await(); return null; });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            AtomicBoolean queued = new AtomicBoolean();
            CompletableFuture<Void> second = app.submit(() -> { queued.set(true); return null; });
            CompletableFuture<Void> rejected = app.submit(() -> fail("must not run"));
            assertTrue(rejected.isCompletedExceptionally());
            release.countDown(); active.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
            assertTrue(queued.get());
        } finally { release.countDown(); }
    }
    @Test void failuresPropagateWithoutWritingFakeCacheEntries() {
        Repository repository = new Repository() {
            @Override public ReputationSnapshot getOrCreate(UUID id, String scope) { throw new IllegalStateException("offline"); }
        };
        try (ReputationApplication app = new ReputationApplication(repository, settings())) {
            UUID id = UUID.randomUUID();
            assertThrows(CompletionException.class, () -> app.getProfile(id).toCompletableFuture().join());
            assertTrue(app.cachedProfile(id).isEmpty());
        }
    }
    @Test void savedLanguageUpdatesOnlyAfterSuccessfulWrite() {
        Repository repository = new Repository();
        try (ReputationApplication app = new ReputationApplication(repository, settings())) {
            UUID id = UUID.randomUUID();
            app.setLanguage(id, PlayerLanguage.en_US).toCompletableFuture().join();
            assertEquals(PlayerLanguage.en_US, app.language(id).toCompletableFuture().join());
            assertEquals(PlayerLanguage.en_US, app.cachedLanguage(id));
        }
    }
}
