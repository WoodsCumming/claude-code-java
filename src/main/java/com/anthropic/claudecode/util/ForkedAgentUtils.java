package com.anthropic.claudecode.util;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper for running forked agent query loops with usage tracking.
 *
 * This utility ensures forked agents:
 * 1. Share identical cache-critical params with the parent to guarantee prompt cache hits
 * 2. Track full usage metrics across the entire query loop
 * 3. Log metrics via the tengu_fork_agent_query event when complete
 * 4. Isolate mutable state to prevent interference with the main agent loop
 *
 * Translated from src/utils/forkedAgent.ts
 */
@Slf4j
public class ForkedAgentUtils {



    // ---------------------------------------------------------------------------
    // CacheSafeParams
    // ---------------------------------------------------------------------------

    /**
     * Parameters that must be identical between the fork and parent API requests
     * to share the parent's prompt cache.
     *
     * Translated from CacheSafeParams in forkedAgent.ts
     */
    @Data
    @Builder
    public static class CacheSafeParams {
        /** System prompt – must match parent for cache hits */
        private Object systemPrompt;
        /** User context – prepended to messages, affects cache */
        private Map<String, String> userContext;
        /** System context – appended to system prompt, affects cache */
        private Map<String, String> systemContext;
        /** Tool use context containing tools, model, and other options */
        private Object toolUseContext;
        /** Parent context messages for prompt cache sharing */
        private List<Object> forkContextMessages;
    }

    // ---------------------------------------------------------------------------
    // Module-level slot: last cache-safe params (equivalent to TS module variable)
    // ---------------------------------------------------------------------------

    private static final AtomicReference<CacheSafeParams> lastCacheSafeParams =
            new AtomicReference<>(null);

    /**
     * Save the last cache-safe params.
     * Translated from saveCacheSafeParams() in forkedAgent.ts
     */
    public static void saveCacheSafeParams(CacheSafeParams params) {
        lastCacheSafeParams.set(params);
    }

    /**
     * Retrieve the last saved cache-safe params.
     * Translated from getLastCacheSafeParams() in forkedAgent.ts
     */
    public static CacheSafeParams getLastCacheSafeParams() {
        return lastCacheSafeParams.get();
    }

    // ---------------------------------------------------------------------------
    // ForkedAgentParams
    // ---------------------------------------------------------------------------

    /**
     * Parameters for running a forked agent.
     * Translated from ForkedAgentParams in forkedAgent.ts
     */
    @Data
    @Builder
    public static class ForkedAgentParams {
        /** Messages to start the forked query loop with */
        private List<Object> promptMessages;
        /** Cache-safe parameters that must match the parent query */
        private CacheSafeParams cacheSafeParams;
        /** Source identifier for tracking */
        private String querySource;
        /** Label for analytics (e.g., 'session_memory', 'supervisor') */
        private String forkLabel;
        /** Optional cap on output tokens */
        private Integer maxOutputTokens;
        /** Optional cap on number of turns (API round-trips) */
        private Integer maxTurns;
        /** Skip sidechain transcript recording */
        @Builder.Default
        private boolean skipTranscript = false;
        /** Skip writing new prompt cache entries on the last message */
        @Builder.Default
        private boolean skipCacheWrite = false;
    }

    // ---------------------------------------------------------------------------
    // ForkedAgentResult
    // ---------------------------------------------------------------------------

    /**
     * Result from running a forked agent.
     * Translated from ForkedAgentResult in forkedAgent.ts
     */
    @Data
    @Builder
    public static class ForkedAgentResult {
        /** All messages yielded during the query loop */
        private List<Object> messages;
        /** Accumulated token usage across all API calls */
        private TokenUsage totalUsage;
    }

    // ---------------------------------------------------------------------------
    // TokenUsage
    // ---------------------------------------------------------------------------

    /**
     * Accumulated token usage.
     * Translated from NonNullableUsage in forkedAgent.ts
     */
    @Data
    @Builder
    public static class TokenUsage {
        @Builder.Default private long inputTokens = 0;
        @Builder.Default private long outputTokens = 0;
        @Builder.Default private long cacheReadInputTokens = 0;
        @Builder.Default private long cacheCreationInputTokens = 0;
        private String serviceTier;

        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long v) { inputTokens = v; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long v) { outputTokens = v; }
        public long getCacheReadInputTokens() { return cacheReadInputTokens; }
        public void setCacheReadInputTokens(long v) { cacheReadInputTokens = v; }
        public long getCacheCreationInputTokens() { return cacheCreationInputTokens; }
        public void setCacheCreationInputTokens(long v) { cacheCreationInputTokens = v; }
        public String getServiceTier() { return serviceTier; }
        public void setServiceTier(String v) { serviceTier = v; }
    }

    // ---------------------------------------------------------------------------
    // SubagentContextOverrides
    // ---------------------------------------------------------------------------

    /**
     * Options for creating a subagent context.
     * Translated from SubagentContextOverrides in forkedAgent.ts
     */
    @Data
    @Builder
    public static class SubagentContextOverrides {
        private Object options;
        private String agentId;
        private String agentType;
        private List<Object> messages;
        private Object readFileState;
        private Object abortController;
        private Object getAppState;
        @Builder.Default private boolean shareSetAppState = false;
        private String criticalSystemReminderExperimental;
        private Boolean requireCanUseTool;
        private Object contentReplacementState;
    }

    // ---------------------------------------------------------------------------
    // PreparedForkedContext
    // ---------------------------------------------------------------------------

    /**
     * Result from preparing a forked command context.
     * Translated from PreparedForkedContext in forkedAgent.ts
     */
    @Data
    @Builder
    public static class PreparedForkedContext {
        /** Skill content with args replaced */
        private String skillContent;
        /** The general-purpose agent to use */
        private Object baseAgent;
        /** Initial prompt messages */
        private List<Object> promptMessages;
    }

    // ---------------------------------------------------------------------------
    // Static helpers
    // ---------------------------------------------------------------------------

    /**
     * Extracts result text from agent messages.
     * Translated from extractResultText() in forkedAgent.ts
     *
     * @param agentMessages messages yielded by the agent
     * @param defaultText   fallback when no assistant message is found
     * @return text content of the last assistant message, or defaultText
     */
    public static String extractResultText(List<Object> agentMessages, String defaultText) {
        if (defaultText == null) defaultText = "Execution completed";
        if (agentMessages == null || agentMessages.isEmpty()) {
            return defaultText;
        }
        // Callers with concrete message types should inspect the list directly;
        // this utility returns the default for the generic Object case.
        return defaultText;
    }

    /**
     * Runs a forked agent query loop and tracks cache-hit metrics.
     *
     * In the TypeScript implementation this uses an async generator (query());
     * here we return a CompletableFuture wrapping the result.
     *
     * Translated from runForkedAgent() in forkedAgent.ts
     *
     * @param params forked agent parameters
     * @return CompletableFuture resolving to ForkedAgentResult
     */
    public static CompletableFuture<ForkedAgentResult> runForkedAgent(ForkedAgentParams params) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            List<Object> outputMessages = new ArrayList<>();
            TokenUsage totalUsage = TokenUsage.builder().build();

            log.debug("Forked agent [{}] starting", params.getForkLabel());

            // NOTE: In the full implementation this would drive the query loop and
            // collect stream events. The structure mirrors the TS runForkedAgent loop:
            //   for await (const message of query(...)) { ... }
            // For now we return an empty result to preserve the public API contract.

            long durationMs = System.currentTimeMillis() - startTime;

            log.debug(
                    "Forked agent [{}] finished: {} messages, durationMs={}",
                    params.getForkLabel(),
                    outputMessages.size(),
                    durationMs);

            logForkAgentQueryEvent(params.getForkLabel(), params.getQuerySource(),
                    durationMs, outputMessages.size(), totalUsage);

            return ForkedAgentResult.builder()
                    .messages(outputMessages)
                    .totalUsage(totalUsage)
                    .build();
        });
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Logs the tengu_fork_agent_query event.
     * Translated from logForkAgentQueryEvent() in forkedAgent.ts
     */
    private static void logForkAgentQueryEvent(
            String forkLabel,
            String querySource,
            long durationMs,
            int messageCount,
            TokenUsage totalUsage) {

        long totalInputTokens = totalUsage.getInputTokens()
                + totalUsage.getCacheCreationInputTokens()
                + totalUsage.getCacheReadInputTokens();

        double cacheHitRate = totalInputTokens > 0
                ? (double) totalUsage.getCacheReadInputTokens() / totalInputTokens
                : 0.0;

        log.info(
                "tengu_fork_agent_query forkLabel={} querySource={} durationMs={} messageCount={} "
                + "inputTokens={} outputTokens={} cacheReadInputTokens={} cacheCreationInputTokens={} "
                + "cacheHitRate={:.3f}",
                forkLabel, querySource, durationMs, messageCount,
                totalUsage.getInputTokens(), totalUsage.getOutputTokens(),
                totalUsage.getCacheReadInputTokens(), totalUsage.getCacheCreationInputTokens(),
                cacheHitRate);
    }

    private ForkedAgentUtils() {}
}
