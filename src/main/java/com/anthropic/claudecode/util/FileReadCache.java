package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * File read cache with automatic invalidation.
 * Translated from src/utils/fileReadCache.ts
 *
 * Caches file contents and invalidates based on modification time.
 */
@Slf4j
public class FileReadCache {



    private static final int MAX_CACHE_SIZE = 1000;
    private static final FileReadCache INSTANCE = new FileReadCache();

    private final Map<String, CachedFileData> cache;

    private FileReadCache() {
        this.cache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedFileData> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
        );
    }

    public static FileReadCache getInstance() {
        return INSTANCE;
    }

    /**
     * Read a file with caching.
     * Translated from FileReadCache.readFile() in fileReadCache.ts
     */
    public CachedFileData readFile(String filePath) throws IOException {
        File file = new File(filePath);
        long mtime = file.lastModified();

        CachedFileData cached = cache.get(filePath);
        if (cached != null && cached.mtime == mtime) {
            return cached;
        }

        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        String encoding = FileReadUtils2.detectEncoding(filePath);

        CachedFileData data = new CachedFileData(content, encoding, mtime);
        cache.put(filePath, data);
        return data;
    }

    /**
     * Invalidate a cached file.
     */
    public void invalidate(String filePath) {
        cache.remove(filePath);
    }

    /**
     * Clear the entire cache.
     */
    public void clear() {
        cache.clear();
    }

    public record CachedFileData(String content, String encoding, long mtime) {}
}
