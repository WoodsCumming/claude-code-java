package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.CachePaths;
import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Cleanup service for removing old files and caches.
 * Translated from src/utils/cleanup.ts
 *
 * Handles cleanup of old message files, tool results, and other temporary data.
 */
@Slf4j
@Service
public class CleanupService {



    private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000; // 30 days

    private final PasteStoreService pasteStoreService;

    @Autowired
    public CleanupService(PasteStoreService pasteStoreService) {
        this.pasteStoreService = pasteStoreService;
    }

    /**
     * Clean up old message files in the background.
     * Translated from cleanupOldMessageFilesInBackground() in cleanup.ts
     */
    public CompletableFuture<Void> cleanupOldMessageFilesInBackground() {
        return CompletableFuture.runAsync(() -> {
            log.debug("Running background cleanup");

            // Clean up old paste files
            pasteStoreService.cleanupOldPastes(MAX_AGE_MS);

            // Clean up old cache files
            cleanupOldCacheFiles();

            log.debug("Background cleanup complete");
        });
    }

    /**
     * Clean up NPM cache for Anthropic packages.
     * Translated from cleanupNpmCacheForAnthropicPackages() in cleanup.ts
     */
    public CompletableFuture<Void> cleanupNpmCacheForAnthropicPackages() {
        return CompletableFuture.runAsync(() -> {
            // In a full implementation, this would clean npm cache
            log.debug("Cleaning npm cache for Anthropic packages");
        });
    }

    private void cleanupOldCacheFiles() {
        String cacheDir = CachePaths.getBaseCacheDir();
        File cacheDirFile = new File(cacheDir);
        if (!cacheDirFile.exists()) return;

        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        cleanupOldFiles(cacheDirFile, cutoff);
    }

    private void cleanupOldFiles(File dir, long cutoff) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                cleanupOldFiles(file, cutoff);
            } else if (file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }
}
