package org.dextur.reputation.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Small synchronized LRU cache. Reads never start IO or extend expiration. */
public final class BoundedCache<K, V> {
    private record Entry<V>(V value, long expires) { }
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private final int maximum;
    private final long ttl;
    private final LongSupplier clock;

    public BoundedCache(int maximum, Duration ttl) { this(maximum, ttl, System::nanoTime); }
    public BoundedCache(int maximum, Duration ttl, LongSupplier clock) {
        if (maximum < 1 || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Invalid cache limits");
        this.maximum = maximum; this.ttl = ttl.toNanos(); this.clock = clock;
    }
    public synchronized Optional<V> get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (clock.getAsLong() - entry.expires() >= 0) { entries.remove(key); return Optional.empty(); }
        return Optional.of(entry.value());
    }
    public synchronized void put(K key, V value) {
        entries.put(key, new Entry<>(value, clock.getAsLong() + ttl));
        while (entries.size() > maximum) entries.remove(entries.keySet().iterator().next());
    }
    public synchronized void remove(K key) { entries.remove(key); }
    public synchronized void clear() { entries.clear(); }
}
