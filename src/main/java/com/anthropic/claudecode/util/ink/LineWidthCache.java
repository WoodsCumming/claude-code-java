package com.anthropic.claudecode.util.ink;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java equivalent of line-width-cache.ts.
 *
 * Caches the display-width of individual text lines to avoid re-measuring
 * hundreds of unchanged lines on every token during streaming.
 *
 * The TypeScript source uses a plain {@code Map<string, number>} with a
 * MAX_CACHE_SIZE eviction strategy (full-clear when limit is reached).
 * We replicate the same strategy here using a bounded {@link LinkedHashMap}.
 */
public final class LineWidthCache {

    private static final int MAX_CACHE_SIZE = 4096;

    /** Module-level cache shared across all callers (mirrors the TS module-scope {@code cache}). */
    private static final Map<String, Integer> CACHE = new LinkedHashMap<>(MAX_CACHE_SIZE * 2) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            // We mirror the TS full-clear strategy: evict when size exceeds limit.
            // LinkedHashMap does not support full-clear via removeEldestEntry, so
            // we handle eviction manually in lineWidth() instead. Return false here.
            return false;
        }
    };

    private LineWidthCache() {}

    /**
     * Returns the display width (in terminal columns) of a single line of text.
     * Lines are cached; the cache is fully cleared when it reaches
     * {@value #MAX_CACHE_SIZE} entries to match the TypeScript behaviour.
     *
     * @param line a single line of text (must not contain {@code '\n'})
     * @return display width in columns
     */
    public static int lineWidth(String line) {
        Integer cached = CACHE.get(line);
        if (cached != null) return cached;

        int width = InkStringWidth.stringWidth(line);

        if (CACHE.size() >= MAX_CACHE_SIZE) {
            CACHE.clear();
        }
        CACHE.put(line, width);
        return width;
    }

    /** Clears the module-level cache. Primarily for testing. */
    public static void clearCache() {
        CACHE.clear();
    }
}
