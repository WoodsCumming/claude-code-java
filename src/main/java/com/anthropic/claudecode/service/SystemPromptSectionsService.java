package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * System prompt sections service.
 * Translated from src/constants/systemPromptSections.ts
 *
 * Manages memoized system prompt sections that are cached until /clear or /compact.
 */
@Slf4j
@Service
public class SystemPromptSectionsService {



    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final List<SystemPromptSection> sections = new ArrayList<>();

    /**
     * Register a system prompt section.
     * Translated from systemPromptSection() in systemPromptSections.ts
     */
    public void registerSection(String name, Supplier<String> compute, boolean cacheBreak) {
        sections.add(new SystemPromptSection(name, compute, cacheBreak));
    }

    /**
     * Get the value of a system prompt section.
     * Translated from getSystemPromptSectionCache() in state.ts
     */
    public Optional<String> getSectionValue(String name) {
        return Optional.ofNullable(cache.get(name));
    }

    /**
     * Compute and cache all sections.
     */
    public CompletableFuture<Map<String, String>> computeAllSections() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> result = new LinkedHashMap<>();
            for (SystemPromptSection section : sections) {
                String cached = cache.get(section.getName());
                if (cached != null && !section.isCacheBreak()) {
                    result.put(section.getName(), cached);
                } else {
                    String value = section.getCompute().get();
                    if (value != null) {
                        cache.put(section.getName(), value);
                        result.put(section.getName(), value);
                    }
                }
            }
            return result;
        });
    }

    /**
     * Clear all section caches.
     * Translated from clearSystemPromptSections() in state.ts
     */
    public void clearSections() {
        cache.clear();
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SystemPromptSection {
        private String name;
        private Supplier<String> compute;
        private boolean cacheBreak;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public Supplier<String> getCompute() { return compute; }
        public void setCompute(Supplier<String> v) { compute = v; }
        public boolean isCacheBreak() { return cacheBreak; }
        public void setCacheBreak(boolean v) { cacheBreak = v; }
    }
}
