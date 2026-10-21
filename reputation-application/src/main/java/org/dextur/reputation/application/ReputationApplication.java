package org.dextur.reputation.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.dextur.reputation.api.ReputationService;
import org.dextur.reputation.api.ReputationSnapshot;
import org.dextur.reputation.domain.PlayerLanguage;
import org.dextur.reputation.domain.ProfileRepository;

public final class ReputationApplication implements ReputationService, AutoCloseable {
    @FunctionalInterface public interface Operation<T> { T run() throws Exception; }
    private final ProfileRepository repository;
    private final Settings settings;
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.BiConsumer<ReputationSnapshot,Boolean>> additionalObservers=new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile java.util.function.BiConsumer<ReputationSnapshot,Boolean> profileObserver=(profile,committed)->{};
    private final ThreadPoolExecutor executor;
    private final BoundedCache<UUID, ReputationSnapshot> profiles;
    private final BoundedCache<UUID, PlayerLanguage> languages;
    private final Object[] profileLocks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final Object[] languageLocks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final java.util.concurrent.atomic.AtomicLong observerWarning = new java.util.concurrent.atomic.AtomicLong();

    public ReputationApplication(ProfileRepository repository, Settings settings) {
        this(repository, settings, () -> { });
    }

    public ReputationApplication(ProfileRepository repository, Settings settings, Runnable cleanup) {
        this.repository = repository; this.settings = settings;
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(settings.workers(), settings.workers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.queueSize()), r -> {
                    Thread thread = new Thread(r, "DexturReputation-db-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { cleanup.run(); }
        };
        profiles = new BoundedCache<>(settings.cacheSize(), Duration.ofSeconds(settings.cacheSeconds()));
        languages = new BoundedCache<>(settings.cacheSize(), Duration.ofSeconds(settings.cacheSeconds()));
    }

    public <T> CompletableFuture<T> submit(Operation<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try { result.complete(operation.run()); }
                catch (Throwable e) { result.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        return result;
    }

    @Override public CompletionStage<ReputationSnapshot> getProfile(UUID playerId) {
        Objects.requireNonNull(playerId);
        if (executor.isShutdown()) return CompletableFuture.failedFuture(new IllegalStateException("Service closed"));
        Optional<ReputationSnapshot> cached = profiles.get(playerId);
        if (cached.isPresent()) return CompletableFuture.completedFuture(cached.get());
        return submit(() -> withProfiles(playerId, playerId, () -> {
            ReputationSnapshot profile = repository.getOrCreate(playerId, settings.scope());
            updateProfile(profile,false);
            return profile;
        }));
    }

    /** Always goes to the database; concurrent language mutations cannot corrupt a language cache. */
    public CompletionStage<PlayerLanguage> language(UUID playerId) {
        return submit(() -> {
            synchronized (languageLock(playerId)) {
                PlayerLanguage language = repository.language(playerId, settings.defaultLanguage());
                languages.put(playerId, language);
                return language;
            }
        });
    }

    public CompletionStage<Void> setLanguage(UUID playerId, PlayerLanguage language) {
        Objects.requireNonNull(language);
        return submit(() -> {
            synchronized (languageLock(playerId)) {
                repository.setLanguage(playerId, language);
                languages.put(playerId, language);
            }
            return null;
        });
    }

    public Optional<ReputationSnapshot> cachedProfile(UUID playerId) { return profiles.get(playerId); }
    public PlayerLanguage cachedLanguage(UUID playerId) { return languages.get(playerId).orElse(settings.defaultLanguage()); }
    public void forget(UUID playerId) { profiles.remove(playerId); languages.remove(playerId); }
    public int queuedOperations() { return executor.getQueue().size(); }
    private Object languageLock(UUID playerId) { return languageLocks[Math.floorMod(playerId.hashCode(), languageLocks.length)]; }

    /** Drains accepted writes before pool disposal. Throws if the deadline expires. */
    @Override public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(settings.shutdownSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Database workers still draining; pool must remain open until they finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted draining database workers", e);
        } finally { profiles.clear(); languages.clear(); }
    }

    <T> T withProfiles(UUID first, UUID second, Operation<T> operation) throws Exception {
        int a = Math.floorMod(first.hashCode(), profileLocks.length), b = Math.floorMod(second.hashCode(), profileLocks.length);
        synchronized (profileLocks[Math.min(a,b)]) { synchronized (profileLocks[Math.max(a,b)]) { return operation.run(); } }
    }
    <T> T withPlayers(java.util.Collection<UUID> players,Operation<T> work)throws Exception{int[] stripes=players.stream().mapToInt(id->Math.floorMod(id.hashCode(),profileLocks.length)).distinct().sorted().toArray();return withStripes(stripes,0,work);}
    <T> T withAllProfiles(Operation<T> work)throws Exception{return withStripes(java.util.stream.IntStream.range(0,profileLocks.length).toArray(),0,work);}
    private <T>T withStripes(int[] stripes,int index,Operation<T> work)throws Exception{if(index==stripes.length)return work.run();synchronized(profileLocks[stripes[index]]){return withStripes(stripes,index+1,work);}}
    public void observeProfiles(java.util.function.BiConsumer<ReputationSnapshot,Boolean> observer) { profileObserver=Objects.requireNonNull(observer); }
    void updateProfile(ReputationSnapshot profile) { updateProfile(profile,true); }
    void updateProfile(ReputationSnapshot profile,boolean committed) {
        profiles.put(profile.playerId(), profile);
        notifyObserver(profileObserver,profile,committed);
        for(var observer:additionalObservers)notifyObserver(observer,profile,committed);
    }
    private void notifyObserver(java.util.function.BiConsumer<ReputationSnapshot,Boolean> observer,ReputationSnapshot profile,boolean committed) {
        try { observer.accept(profile,committed); }
        catch(Throwable error) {
            long now=System.nanoTime(),previous=observerWarning.get();
            if((previous==0||now-previous>=TimeUnit.MINUTES.toNanos(1))&&observerWarning.compareAndSet(previous,now))
                System.getLogger(ReputationApplication.class.getName()).log(System.Logger.Level.WARNING,"Profile observer failed: {0}",error.getClass().getSimpleName());
        }
    }

    public AutoCloseable addProfileObserver(java.util.function.BiConsumer<ReputationSnapshot,Boolean> observer){if(additionalObservers.size()>=16)throw new IllegalStateException("Profile observer limit");additionalObservers.add(Objects.requireNonNull(observer));return ()->additionalObservers.remove(observer);}

    public boolean terminated() { return executor.isTerminated(); }
}
