package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin install counts service.
 * Translated from src/utils/plugins/installCounts.ts
 *
 * Fetches and caches plugin install counts.
 */
@Slf4j
@Service
public class PluginInstallCountsService {



    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final String CACHE_FILENAME = "install-counts-cache.json";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public PluginInstallCountsService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Get install counts for plugins.
     * Translated from getInstallCounts() in installCounts.ts
     */
    public CompletableFuture<Map<String, Integer>> getInstallCounts() {
        return CompletableFuture.supplyAsync(() -> {
            // Try cache first
            Optional<Map<String, Integer>> cached = loadFromCache();
            if (cached.isPresent()) return cached.get();

            // Fetch from remote
            return fetchInstallCounts().join();
        });
    }

    private Optional<Map<String, Integer>> loadFromCache() {
        String cachePath = EnvUtils.getClaudeConfigHomeDir() + "/plugins/" + CACHE_FILENAME;
        File cacheFile = new File(cachePath);

        if (!cacheFile.exists()) return Optional.empty();

        try {
            Map<String, Object> data = objectMapper.readValue(cacheFile, Map.class);
            Long timestamp = ((Number) data.get("timestamp")).longValue();
            if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) return Optional.empty();

            Map<String, Integer> counts = (Map<String, Integer>) data.get("counts");
            return Optional.ofNullable(counts);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private CompletableFuture<Map<String, Integer>> fetchInstallCounts() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // In a full implementation, this would fetch from the stats repo
                return Map.of();
            } catch (Exception e) {
                log.debug("Could not fetch install counts: {}", e.getMessage());
                return Map.of();
            }
        });
    }
}
