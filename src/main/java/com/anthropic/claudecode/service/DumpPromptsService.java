package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.SlowOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dump prompts service for debugging.
 * Translated from src/services/api/dumpPrompts.ts
 *
 * Caches API requests for debugging purposes.
 */
@Slf4j
@Service
public class DumpPromptsService {



    private static final int MAX_CACHED_REQUESTS = 5;

    private final List<ApiRequestEntry> cachedApiRequests = new CopyOnWriteArrayList<>();

    /**
     * Get the last API requests.
     * Translated from getLastApiRequests() in dumpPrompts.ts
     */
    public List<ApiRequestEntry> getLastApiRequests() {
        return Collections.unmodifiableList(cachedApiRequests);
    }

    /**
     * Clear the API request cache.
     * Translated from clearApiRequestCache() in dumpPrompts.ts
     */
    public void clearApiRequestCache() {
        cachedApiRequests.clear();
    }

    /**
     * Add an API request to the cache.
     * Translated from addApiRequestToCache() in dumpPrompts.ts
     */
    public void addApiRequestToCache(Object requestData) {
        cachedApiRequests.add(new ApiRequestEntry(
            java.time.Instant.now().toString(),
            requestData
        ));
        while (cachedApiRequests.size() > MAX_CACHED_REQUESTS) {
            cachedApiRequests.remove(0);
        }
    }

    /**
     * Get the dump prompts path.
     * Translated from getDumpPromptsPath() in dumpPrompts.ts
     */
    public String getDumpPromptsPath(String sessionId) {
        return EnvUtils.getClaudeConfigHomeDir()
            + "/debug-prompts/"
            + (sessionId != null ? sessionId : "default");
    }

    /**
     * Create a dump prompts fetch wrapper.
     * Translated from createDumpPromptsFetch() in dumpPrompts.ts
     */
    public void dumpPrompts(String sessionId, Object request) {
        try {
            String dir = getDumpPromptsPath(sessionId);
            new File(dir).mkdirs();
            String path = dir + "/request-" + System.currentTimeMillis() + ".json";
            Files.writeString(Paths.get(path), SlowOperations.jsonStringify(request));
        } catch (Exception e) {
            log.debug("Could not dump prompts: {}", e.getMessage());
        }
    }

    public record ApiRequestEntry(String timestamp, Object request) {}
}
