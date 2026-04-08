package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.AllArgsConstructor;
import java.nio.file.Paths;
import java.util.*;

/**
 * LRU cache for file state tracking.
 * Translated from src/utils/fileStateCache.ts
 *
 * Tracks which files have been read by Claude, their content, and timestamps.
 * Used to detect file modifications between reads and edits.
 */
public class FileStateCache {

    public static final int READ_FILE_STATE_CACHE_SIZE = 100;
    private static final int DEFAULT_MAX_CACHE_SIZE_BYTES = 25 * 1024 * 1024; // 25MB

    private final LinkedHashMap<String, FileState> cache;
    private final int maxEntries;
    private int currentSizeBytes;
    private final int maxSizeBytes;

    public FileStateCache() {
        this(READ_FILE_STATE_CACHE_SIZE, DEFAULT_MAX_CACHE_SIZE_BYTES);
    }

    public FileStateCache(int maxEntries, int maxSizeBytes) {
        this.maxEntries = maxEntries;
        this.maxSizeBytes = maxSizeBytes;
        this.currentSizeBytes = 0;
        this.cache = new LinkedHashMap<>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FileState> eldest) {
                if (size() > maxEntries) {
                    currentSizeBytes -= estimateSize(eldest.getValue());
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * Normalize a path key.
     */
    private String normalizeKey(String path) {
        if (path == null) return "";
        return Paths.get(path).normalize().toString();
    }

    public Optional<FileState> get(String path) {
        return Optional.ofNullable(cache.get(normalizeKey(path)));
    }

    public void set(String path, String content, long timestamp) {
        set(path, new FileState(content, timestamp, null, null, null));
    }

    public void set(String path, FileState state) {
        String key = normalizeKey(path);

        // Remove old entry size
        FileState old = cache.get(key);
        if (old != null) {
            currentSizeBytes -= estimateSize(old);
        }

        // Evict if needed
        while (currentSizeBytes + estimateSize(state) > maxSizeBytes && !cache.isEmpty()) {
            Map.Entry<String, FileState> eldest = cache.entrySet().iterator().next();
            currentSizeBytes -= estimateSize(eldest.getValue());
            cache.remove(eldest.getKey());
        }

        cache.put(key, state);
        currentSizeBytes += estimateSize(state);
    }

    public boolean has(String path) {
        return cache.containsKey(normalizeKey(path));
    }

    public boolean delete(String path) {
        String key = normalizeKey(path);
        FileState removed = cache.remove(key);
        if (removed != null) {
            currentSizeBytes -= estimateSize(removed);
            return true;
        }
        return false;
    }

    public void clear() {
        cache.clear();
        currentSizeBytes = 0;
    }

    public int size() {
        return cache.size();
    }

    /**
     * Get all cache keys.
     * Translated from cacheKeys() in fileStateCache.ts
     */
    public java.util.List<String> cacheKeys() {
        return new java.util.ArrayList<>(cache.keySet());
    }

    /**
     * Create a new FileStateCache with size limit.
     * Translated from createFileStateCacheWithSizeLimit() in fileStateCache.ts
     */
    public static FileStateCache createWithSizeLimit(int maxEntries, int maxSizeBytes) {
        return new FileStateCache(maxEntries, maxSizeBytes);
    }

    /** Create a new FileStateCache with the given max entry count. */
    public static FileStateCache withSizeLimit(int maxEntries) {
        return new FileStateCache(maxEntries, Integer.MAX_VALUE);
    }

    private int estimateSize(FileState state) {
        if (state == null || state.getContent() == null) return 1;
        return Math.max(1, state.getContent().getBytes().length);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FileState {
        private String content;
        private long timestamp;
        private Integer offset;
        private Integer limit;
        private Boolean isPartialView;


        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
        public Integer getOffset() { return offset; }
        public void setOffset(Integer v) { offset = v; }
        public Integer getLimit() { return limit; }
        public void setLimit(Integer v) { limit = v; }
        public boolean isIsPartialView() { return isPartialView; }
        public void setIsPartialView(Boolean v) { isPartialView = v; }
    }
}
