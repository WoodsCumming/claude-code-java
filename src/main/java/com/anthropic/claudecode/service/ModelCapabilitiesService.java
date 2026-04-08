package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lombok.Data;

/**
 * Model capabilities service.
 * Translated from src/utils/model/modelCapabilities.ts
 *
 * Fetches and caches model capabilities (max tokens, context windows, etc.)
 */
@Slf4j
@Service
public class ModelCapabilitiesService {



    private static final String CACHE_FILENAME = "model-capabilities.json";
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private final ObjectMapper objectMapper;

    // In-memory cache
    private List<ModelCapability> cachedCapabilities;
    private long cacheTimestamp = 0;

    @Autowired
    public ModelCapabilitiesService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get model capabilities.
     * Translated from getModelCapabilities() in modelCapabilities.ts
     */
    public List<ModelCapability> getModelCapabilities() {
        // Check in-memory cache
        if (cachedCapabilities != null && !isCacheExpired()) {
            return cachedCapabilities;
        }

        // Try to load from disk cache
        List<ModelCapability> diskCache = loadFromDiskCache();
        if (diskCache != null) {
            cachedCapabilities = diskCache;
            return cachedCapabilities;
        }

        // Return default capabilities
        return getDefaultCapabilities();
    }

    /**
     * Get the max input tokens for a model.
     */
    public int getMaxInputTokens(String modelId) {
        List<ModelCapability> capabilities = getModelCapabilities();
        return capabilities.stream()
            .filter(c -> modelId.startsWith(c.getId()))
            .mapToInt(c -> c.getMaxInputTokens() != null ? c.getMaxInputTokens() : 200_000)
            .findFirst()
            .orElse(200_000);
    }

    /**
     * Get the max output tokens for a model.
     */
    public int getMaxOutputTokens(String modelId) {
        List<ModelCapability> capabilities = getModelCapabilities();
        return capabilities.stream()
            .filter(c -> modelId.startsWith(c.getId()))
            .mapToInt(c -> c.getMaxTokens() != null ? c.getMaxTokens() : 8192)
            .findFirst()
            .orElse(8192);
    }

    private boolean isCacheExpired() {
        return System.currentTimeMillis() - cacheTimestamp > CACHE_TTL_MS;
    }

    private List<ModelCapability> loadFromDiskCache() {
        String cachePath = EnvUtils.getClaudeConfigHomeDir() + "/cache/" + CACHE_FILENAME;
        File cacheFile = new File(cachePath);

        if (!cacheFile.exists()) return null;

        try {
            Map<String, Object> data = objectMapper.readValue(cacheFile, Map.class);
            Long timestamp = ((Number) data.get("timestamp")).longValue();
            if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) return null;

            List<Map<String, Object>> models = (List<Map<String, Object>>) data.get("models");
            if (models == null) return null;

            List<ModelCapability> capabilities = new ArrayList<>();
            for (Map<String, Object> model : models) {
                ModelCapability cap = new ModelCapability();
                cap.setId((String) model.get("id"));
                if (model.get("max_input_tokens") != null) {
                    cap.setMaxInputTokens(((Number) model.get("max_input_tokens")).intValue());
                }
                if (model.get("max_tokens") != null) {
                    cap.setMaxTokens(((Number) model.get("max_tokens")).intValue());
                }
                capabilities.add(cap);
            }

            cacheTimestamp = timestamp;
            return capabilities;
        } catch (Exception e) {
            log.debug("Could not load model capabilities cache: {}", e.getMessage());
            return null;
        }
    }

    private List<ModelCapability> getDefaultCapabilities() {
        return List.of(
            new ModelCapability("claude-opus-4-6", 1_048_576, 32_000),
            new ModelCapability("claude-sonnet-4-6", 200_000, 64_000),
            new ModelCapability("claude-haiku-4-5", 200_000, 8_192)
        );
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ModelCapability {
        private String id;
        private Integer maxInputTokens;
        private Integer maxTokens;


        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public void setMaxInputTokens(Integer v) { maxInputTokens = v; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer v) { maxTokens = v; }
    }
}
