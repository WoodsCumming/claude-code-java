package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Beta session tracing utilities.
 * Translated from src/utils/telemetry/betaSessionTracing.ts
 */
@Slf4j
public class BetaSessionTracing {



    // Track hashes we've already logged this session
    private static final Set<String> seenHashes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Track last reported message hash per query source
    private static final Map<String, String> lastReportedMessageHash = new ConcurrentHashMap<>();

    /**
     * Check if beta tracing is enabled.
     * Translated from isBetaTracingEnabled() in betaSessionTracing.ts
     */
    public static boolean isBetaTracingEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("ENABLE_BETA_TRACING_DETAILED"))
            && System.getenv("BETA_TRACING_ENDPOINT") != null;
    }

    /**
     * Truncate content for tracing.
     * Translated from truncateContent() in betaSessionTracing.ts
     */
    public static String truncateContent(String content, int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength) + "...[truncated]";
    }

    /**
     * Check if a hash has been seen before.
     */
    public static boolean isNewHash(String hash) {
        return seenHashes.add(hash);
    }

    /**
     * Get the last reported message hash for a query source.
     */
    public static Optional<String> getLastReportedMessageHash(String querySource) {
        return Optional.ofNullable(lastReportedMessageHash.get(querySource));
    }

    /**
     * Update the last reported message hash.
     */
    public static void updateLastReportedMessageHash(String querySource, String hash) {
        lastReportedMessageHash.put(querySource, hash);
    }

    /**
     * Clear session state.
     */
    public static void clearSessionState() {
        seenHashes.clear();
        lastReportedMessageHash.clear();
    }

    public record LLMRequestNewContext(
        int newMessageCount,
        List<String> newMessages
    ) {}

    private BetaSessionTracing() {}
}
