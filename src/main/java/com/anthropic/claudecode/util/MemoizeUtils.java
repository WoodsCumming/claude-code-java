package com.anthropic.claudecode.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Memoization utilities.
 * Translated from src/utils/memoize.ts
 */
public class MemoizeUtils {

    /**
     * Create a memoized function with TTL (time-to-live).
     * Translated from memoizeWithTTL() in memoize.ts
     *
     * @param fn              The function to memoize
     * @param cacheLifetimeMs Cache lifetime in milliseconds
     * @return A memoized version of the function
     */
    public static <K, V> Function<K, V> memoizeWithTTL(
            Function<K, CompletableFuture<V>> fn,
            long cacheLifetimeMs) {

        Map<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();

        return key -> {
            CacheEntry<V> entry = cache.get(key);
            long now = System.currentTimeMillis();

            if (entry != null && (now - entry.timestamp) < cacheLifetimeMs) {
                return entry.value;
            }

            // Compute the value
            try {
                V value = fn.apply(key).get(30, TimeUnit.SECONDS);
                cache.put(key, new CacheEntry<>(value, now));
                return value;
            } catch (Exception e) {
                throw new RuntimeException("Memoized function failed: " + e.getMessage(), e);
            }
        };
    }

    /**
     * Create a simple memoized function with LRU cache.
     * Translated from memoizeWithLRU() in memoize.ts
     */
    public static <K, V> Function<K, V> memoizeWithLRU(
            Function<K, V> fn,
            int maxSize) {

        Map<K, V> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > maxSize;
                }
            }
        );

        return key -> cache.computeIfAbsent(key, fn);
    }

    private record CacheEntry<V>(V value, long timestamp) {}

    private MemoizeUtils() {}
}
