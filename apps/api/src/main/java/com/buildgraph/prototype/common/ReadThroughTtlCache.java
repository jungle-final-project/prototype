package com.buildgraph.prototype.common;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Small in-memory read-through TTL cache for expensive, mostly-read responses in a single API instance.
 *
 * <ul>
 *   <li>ttl <= 0 disables caching and always calls the loader.</li>
 *   <li>Same-key misses use a single-flight lock to avoid duplicating heavy loader work.</li>
 *   <li>Optional jitter spreads expiry times so many entries do not expire at the same instant.</li>
 *   <li>The stale-while-revalidate path can return an expired value immediately while one background refresh runs.</li>
 * </ul>
 */
public final class ReadThroughTtlCache<K, V> {
    private record Entry<V>(V value, long expiresAtNanos, long staleExpiresAtNanos) {
    }

    private final Map<K, Entry<V>> store = new ConcurrentHashMap<>();
    private final Map<K, Object> inFlight = new ConcurrentHashMap<>();
    // 무효화 세대 — 로더가 도는 동안 remove/clear가 끼어들면 그 로더 결과는 커밋 전 스냅샷일 수
    // 있다. 세대가 바뀌었으면 되채우지 않아 read-your-writes를 보존한다(값은 호출자에게만 반환).
    private final Map<K, Long> invalidationEpochs = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong globalInvalidationEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final long ttlNanos;
    private final long jitterNanos;
    private final int maxSize;
    private final LongSupplier jitterSupplier;

    public ReadThroughTtlCache(Duration ttl, int maxSize) {
        this(ttl, Duration.ZERO, maxSize);
    }

    public ReadThroughTtlCache(Duration ttl, Duration jitter, int maxSize) {
        this(ttl, jitter, maxSize, null);
    }

    ReadThroughTtlCache(Duration ttl, Duration jitter, int maxSize, LongSupplier jitterSupplier) {
        this.ttlNanos = positiveNanos(ttl);
        this.jitterNanos = positiveNanos(jitter);
        this.maxSize = Math.max(1, maxSize);
        this.jitterSupplier = jitterSupplier == null ? this::randomJitterNanos : jitterSupplier;
    }

    /**
     * Returns a fresh cached value, or blocks one same-key caller group while loader builds and stores it.
     * Null loader results are not cached.
     */
    public V get(K key, Supplier<V> loader) {
        if (ttlNanos <= 0L) {
            return loader.get();
        }
        return loadFreshOrBlock(key, loader, 0L);
    }

    /**
     * Rebuilds and stores the value even when a fresh entry already exists.
     * Used by prewarm jobs so hot keys can have their TTL extended before user traffic hits expiry.
     */
    public V refresh(K key, Supplier<V> loader, Duration staleTtl) {
        if (ttlNanos <= 0L) {
            return loader.get();
        }
        return loadAndBlock(key, loader, positiveNanos(staleTtl), true);
    }

    /**
     * Returns a stale value immediately after fresh TTL expiry while one background refresh rebuilds the entry.
     * If no usable stale value exists, callers fall back to the normal single-flight blocking load.
     */
    public V getStaleWhileRevalidate(K key, Supplier<V> loader, Duration staleTtl, Executor refreshExecutor) {
        if (ttlNanos <= 0L) {
            return loader.get();
        }
        long staleNanos = positiveNanos(staleTtl);
        if (staleNanos <= 0L || refreshExecutor == null) {
            return loadFreshOrBlock(key, loader, 0L);
        }

        Entry<V> hit = store.get(key);
        long now = System.nanoTime();
        if (hit != null) {
            if (hit.expiresAtNanos() > now) {
                return hit.value();
            }
            if (hit.staleExpiresAtNanos() > now) {
                refreshInBackground(key, loader, staleNanos, refreshExecutor);
                return hit.value();
            }
        }
        return loadFreshOrBlock(key, loader, staleNanos);
    }

    private V loadFreshOrBlock(K key, Supplier<V> loader, long staleNanos) {
        return loadAndBlock(key, loader, staleNanos, false);
    }

    private V loadAndBlock(K key, Supplier<V> loader, long staleNanos, boolean forceRefresh) {
        Object lock = inFlight.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            try {
                Entry<V> fresh = store.get(key);
                if (!forceRefresh && fresh != null && fresh.expiresAtNanos() > System.nanoTime()) {
                    return fresh.value();
                }
                return loadAndStore(key, loader, staleNanos);
            } finally {
                inFlight.remove(key);
            }
        }
    }

    private void refreshInBackground(K key, Supplier<V> loader, long staleNanos, Executor refreshExecutor) {
        Object lock = new Object();
        Object existing = inFlight.putIfAbsent(key, lock);
        if (existing != null) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                synchronized (lock) {
                    try {
                        loadAndStore(key, loader, staleNanos);
                    } finally {
                        inFlight.remove(key);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            inFlight.remove(key);
        }
    }

    private V loadAndStore(K key, Supplier<V> loader, long staleNanos) {
        long keyEpochBefore = keyInvalidationEpoch(key);
        long globalEpochBefore = globalInvalidationEpoch.get();
        V value = loader.get();
        if (value == null) {
            return null;
        }
        // 로더 시작 이후 이 키(또는 전체)가 무효화됐으면 결과를 캐시에 되채우지 않는다 —
        // [미스 → 로더가 커밋 전 스냅샷 read] → [쓰기 커밋 + invalidate] → [옛 스냅샷 put]
        // 순서로 방금 담은 부품이 빠진 draft가 TTL 동안 서빙되던 경합을 막는다.
        if (keyInvalidationEpoch(key) == keyEpochBefore && globalInvalidationEpoch.get() == globalEpochBefore) {
            if (store.size() >= maxSize) {
                store.clear();
            }
            long expiresAtNanos = expiresAtNanos();
            store.put(key, new Entry<>(value, expiresAtNanos, staleExpiresAtNanos(expiresAtNanos, staleNanos)));
        }
        return value;
    }

    private long keyInvalidationEpoch(K key) {
        Long epoch = invalidationEpochs.get(key);
        return epoch == null ? 0L : epoch;
    }

    private long expiresAtNanos() {
        return saturatedAdd(System.nanoTime(), saturatedAdd(ttlNanos, boundedJitterNanos()));
    }

    private long staleExpiresAtNanos(long expiresAtNanos, long staleNanos) {
        return staleNanos <= 0L ? expiresAtNanos : saturatedAdd(expiresAtNanos, staleNanos);
    }

    private long boundedJitterNanos() {
        if (jitterNanos <= 0L) {
            return 0L;
        }
        long value = Math.max(0L, jitterSupplier.getAsLong());
        return Math.min(value, jitterNanos);
    }

    private long randomJitterNanos() {
        if (jitterNanos <= 0L) {
            return 0L;
        }
        if (jitterNanos == Long.MAX_VALUE) {
            return ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        }
        return ThreadLocalRandom.current().nextLong(jitterNanos + 1L);
    }

    private static long positiveNanos(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return 0L;
        }
        try {
            return Math.max(0L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    public void remove(K key) {
        // 세대 맵이 무한히 크지 않게 상한에서 비우되, 전역 세대를 올려 진행 중 로더의 되채움을 막는다.
        if (invalidationEpochs.size() >= maxSize * 4) {
            invalidationEpochs.clear();
            globalInvalidationEpoch.incrementAndGet();
        }
        invalidationEpochs.merge(key, 1L, Long::sum);
        store.remove(key);
    }

    public void clear() {
        globalInvalidationEpoch.incrementAndGet();
        store.clear();
    }
}
