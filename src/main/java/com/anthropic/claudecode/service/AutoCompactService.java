package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.util.ModelUtils;
import com.anthropic.claudecode.util.TokenUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Auto-compact service for managing context window.
 * Translated from src/services/compact/autoCompact.ts
 *
 * Automatically compacts the conversation when it approaches
 * the context window limit.
 */
@Slf4j
@Service
public class AutoCompactService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AutoCompactService.class);


    // Reserve this many tokens for output during compaction.
    // Based on p99.99 of compact summary output being 17,387 tokens.
    private static final int MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000;

    // Buffer constants — exported so tests and callers can reference them.
    public static final int AUTOCOMPACT_BUFFER_TOKENS      = 13_000;
    public static final int WARNING_THRESHOLD_BUFFER_TOKENS = 20_000;
    public static final int ERROR_THRESHOLD_BUFFER_TOKENS   = 20_000;
    public static final int MANUAL_COMPACT_BUFFER_TOKENS    = 3_000;

    // Stop trying autocompact after this many consecutive failures (circuit breaker).
    private static final int MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3;

    private final CompactService compactService;
    private final MicroCompactService microCompactService;
    private final SessionMemoryCompactService sessionMemoryCompactService;
    private final PostCompactCleanupService postCompactCleanupService;

    @Autowired
    public AutoCompactService(
            CompactService compactService,
            MicroCompactService microCompactService,
            SessionMemoryCompactService sessionMemoryCompactService,
            PostCompactCleanupService postCompactCleanupService) {
        this.compactService = compactService;
        this.microCompactService = microCompactService;
        this.sessionMemoryCompactService = sessionMemoryCompactService;
        this.postCompactCleanupService = postCompactCleanupService;
    }

    // =========================================================================
    // Context window helpers
    // =========================================================================

    /**
     * Returns the context window size minus the max output tokens reserved for summary.
     * Translated from {@code getEffectiveContextWindowSize()} in autoCompact.ts
     */
    public int getEffectiveContextWindowSize(String model) {
        int reservedForSummary = Math.min(
            ModelUtils.getModelMaxOutputTokens(model),
            MAX_OUTPUT_TOKENS_FOR_SUMMARY
        );

        int contextWindow = ModelUtils.getContextWindowForModel(model);

        // Override via CLAUDE_CODE_AUTO_COMPACT_WINDOW env var.
        String envWindow = System.getenv("CLAUDE_CODE_AUTO_COMPACT_WINDOW");
        if (envWindow != null) {
            try {
                int parsed = Integer.parseInt(envWindow);
                if (parsed > 0) {
                    contextWindow = Math.min(contextWindow, parsed);
                }
            } catch (NumberFormatException e) {
                // Ignore invalid value.
            }
        }

        return contextWindow - reservedForSummary;
    }

    /**
     * Returns the token threshold at which autocompact should fire.
     * Translated from {@code getAutoCompactThreshold()} in autoCompact.ts
     */
    public int getAutoCompactThreshold(String model) {
        int effectiveContextWindow = getEffectiveContextWindowSize(model);
        int autocompactThreshold = effectiveContextWindow - AUTOCOMPACT_BUFFER_TOKENS;

        // Allow percentage-based override for testing.
        String envPercent = System.getenv("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE");
        if (envPercent != null) {
            try {
                double parsed = Double.parseDouble(envPercent);
                if (!Double.isNaN(parsed) && parsed > 0 && parsed <= 100) {
                    int percentageThreshold = (int) Math.floor(effectiveContextWindow * (parsed / 100));
                    return Math.min(percentageThreshold, autocompactThreshold);
                }
            } catch (NumberFormatException e) {
                // Ignore.
            }
        }

        return autocompactThreshold;
    }

    /**
     * Compute the full token-warning state for the given usage and model.
     * Translated from {@code calculateTokenWarningState()} in autoCompact.ts
     */
    public TokenWarningState calculateTokenWarningState(int tokenUsage, String model) {
        int autoCompactThreshold = getAutoCompactThreshold(model);
        int threshold = isAutoCompactEnabled()
                ? autoCompactThreshold
                : getEffectiveContextWindowSize(model);

        int percentLeft = Math.max(0, Math.round(((float)(threshold - tokenUsage) / threshold) * 100));

        int warningThreshold = threshold - WARNING_THRESHOLD_BUFFER_TOKENS;
        int errorThreshold   = threshold - ERROR_THRESHOLD_BUFFER_TOKENS;

        boolean isAboveWarningThreshold     = tokenUsage >= warningThreshold;
        boolean isAboveErrorThreshold       = tokenUsage >= errorThreshold;
        boolean isAboveAutoCompactThreshold = isAutoCompactEnabled() && tokenUsage >= autoCompactThreshold;

        int actualContextWindow  = getEffectiveContextWindowSize(model);
        int defaultBlockingLimit = actualContextWindow - MANUAL_COMPACT_BUFFER_TOKENS;

        // Allow override for testing.
        String blockingLimitOverride = System.getenv("CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE");
        int blockingLimit = defaultBlockingLimit;
        if (blockingLimitOverride != null) {
            try {
                int parsed = Integer.parseInt(blockingLimitOverride);
                if (parsed > 0) {
                    blockingLimit = parsed;
                }
            } catch (NumberFormatException e) {
                // Use default.
            }
        }

        boolean isAtBlockingLimit = tokenUsage >= blockingLimit;

        return new TokenWarningState(
                percentLeft,
                isAboveWarningThreshold,
                isAboveErrorThreshold,
                isAboveAutoCompactThreshold,
                isAtBlockingLimit
        );
    }

    /**
     * Returns true when auto-compact is enabled (env-var and user-config check).
     * Translated from {@code isAutoCompactEnabled()} in autoCompact.ts
     */
    public boolean isAutoCompactEnabled() {
        if (isEnvTruthy("DISABLE_COMPACT") || isEnvTruthy("DISABLE_AUTO_COMPACT")) {
            return false;
        }
        // TODO: also check user config (autoCompactEnabled field in GlobalConfig).
        return true;
    }

    /**
     * Returns true when the current token count is above the auto-compact threshold.
     * Translated from {@code shouldAutoCompact()} in autoCompact.ts
     *
     * @param messages      Current conversation messages.
     * @param model         Model identifier.
     * @param querySource   Query source — recursion guards for "session_memory" and "compact".
     * @param snipTokensFreed Tokens freed by snipping (subtracted before threshold check).
     */
    public boolean shouldAutoCompact(
            List<Message> messages,
            String model,
            String querySource,
            int snipTokensFreed) {

        // Recursion guards.
        if ("session_memory".equals(querySource) || "compact".equals(querySource)) {
            return false;
        }

        if (!isAutoCompactEnabled()) {
            return false;
        }

        int tokenCount = TokenUtils.estimateTokenCount(messages) - snipTokensFreed;

        TokenWarningState warningState = calculateTokenWarningState(tokenCount, model);

        log.debug("autocompact: tokens={} threshold={} effectiveWindow={}",
                tokenCount, getAutoCompactThreshold(model), getEffectiveContextWindowSize(model));

        return warningState.isAboveAutoCompactThreshold();
    }

    /**
     * Convenience overload with no query source and no snip tokens freed.
     */
    public boolean shouldAutoCompact(List<Message> messages, String model) {
        return shouldAutoCompact(messages, model, null, 0);
    }

    // =========================================================================
    // Main autocompact entry point
    // =========================================================================

    /**
     * Compacts if needed, returns the result.
     * Translated from {@code autoCompactIfNeeded()} in autoCompact.ts
     *
     * @param messages        Current messages.
     * @param model           Model to use for compaction summary.
     * @param querySource     Query source (recursion guard).
     * @param tracking        Tracking state carrying the circuit-breaker counter.
     * @param snipTokensFreed Tokens freed by snipping.
     * @return {@link AutoCompactResult} with compaction outcome.
     */
    public CompletableFuture<AutoCompactResult> autoCompactIfNeeded(
            List<Message> messages,
            String model,
            String querySource,
            AutoCompactTrackingState tracking,
            int snipTokensFreed) {

        if (isEnvTruthy("DISABLE_COMPACT")) {
            return CompletableFuture.completedFuture(AutoCompactResult.notCompacted());
        }

        // Circuit breaker: stop retrying after N consecutive failures.
        if (tracking != null
                && tracking.getConsecutiveFailures() != null
                && tracking.getConsecutiveFailures() >= MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES) {
            return CompletableFuture.completedFuture(AutoCompactResult.notCompacted());
        }

        if (!shouldAutoCompact(messages, model, querySource, snipTokensFreed)) {
            return CompletableFuture.completedFuture(AutoCompactResult.notCompacted());
        }

        // Build recompaction info.
        RecompactionInfo recompactionInfo = new RecompactionInfo(
            tracking != null && tracking.isCompacted(),
            tracking != null ? tracking.getTurnCounter() : -1,
            tracking != null ? tracking.getTurnId() : null,
            getAutoCompactThreshold(model),
            querySource
        );

        return CompletableFuture.supplyAsync(() -> {
            try {
                // EXPERIMENT: Try session memory compaction first.
                List<Message> sessionMemoryResult = sessionMemoryCompactService
                    .trySessionMemoryCompaction(messages, null, recompactionInfo.autoCompactThreshold())
                    .join();

                if (sessionMemoryResult != null && !sessionMemoryResult.isEmpty()) {
                    postCompactCleanupService.runPostCompactCleanup(querySource);
                    return new AutoCompactResult(true, sessionMemoryResult, null);
                }

                // Fall back to full compaction.
                List<Message> compacted = compactService
                    .compact(messages, model)
                    .join();

                postCompactCleanupService.runPostCompactCleanup(querySource);

                return new AutoCompactResult(true, compacted, 0);

            } catch (Exception error) {
                log.error("Auto-compact failed", error);
                int prevFailures = (tracking != null && tracking.getConsecutiveFailures() != null)
                        ? tracking.getConsecutiveFailures()
                        : 0;
                int nextFailures = prevFailures + 1;
                if (nextFailures >= MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES) {
                    log.warn("autocompact: circuit breaker tripped after {} consecutive failures "
                             + "— skipping future attempts this session", nextFailures);
                }
                return AutoCompactResult.failed(nextFailures);
            }
        });
    }

    /**
     * Convenience overload with no snip tokens freed.
     */
    public CompletableFuture<AutoCompactResult> autoCompactIfNeeded(
            List<Message> messages,
            String model,
            String querySource,
            AutoCompactTrackingState tracking) {
        return autoCompactIfNeeded(messages, model, querySource, tracking, 0);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Result of an autocompact attempt.
     * Translated from the return type of {@code autoCompactIfNeeded()} in autoCompact.ts
     */
    public static class AutoCompactResult {
        private final boolean wasCompacted;
        private final List<Message> compactedMessages;
        private final Integer consecutiveFailures;

        public AutoCompactResult(boolean wasCompacted, List<Message> compactedMessages, Integer consecutiveFailures) {
            this.wasCompacted = wasCompacted; this.compactedMessages = compactedMessages; this.consecutiveFailures = consecutiveFailures;
        }

        public static AutoCompactResult notCompacted() { return new AutoCompactResult(false, null, null); }
        public static AutoCompactResult failed(int consecutiveFailures) { return new AutoCompactResult(false, null, consecutiveFailures); }

        public boolean isWasCompacted() { return wasCompacted; }
        public List<Message> getCompactedMessages() { return compactedMessages; }
        public Integer getConsecutiveFailures() { return consecutiveFailures; }
    }

    /**
     * Diagnosis context passed into compactConversation.
     * Translated from {@code RecompactionInfo} in autoCompact.ts / compact.ts
     */
    public record RecompactionInfo(
        boolean isRecompactionInChain,
        int turnsSincePreviousCompact,
        String previousCompactTurnId,
        int autoCompactThreshold,
        String querySource
    ) {}

    /**
     * Per-turn autocompact tracking state (circuit-breaker counter etc.).
     * Translated from {@code AutoCompactTrackingState} in autoCompact.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AutoCompactTrackingState {
        /** Whether a compaction has already occurred in this session. */
        private boolean compacted;
        /** Number of turns since the session started (or last compact). */
        private int turnCounter;
        /** Unique ID per turn. */
        private String turnId;
        /** Consecutive autocompact failures — reset on success. Circuit-breaker. */
        private Integer consecutiveFailures;

        public boolean isCompacted() { return compacted; }
        public void setCompacted(boolean v) { compacted = v; }
        public int getTurnCounter() { return turnCounter; }
        public void setTurnCounter(int v) { turnCounter = v; }
        public String getTurnId() { return turnId; }
        public void setTurnId(String v) { turnId = v; }
        public Integer getConsecutiveFailures() { return consecutiveFailures; }
        public void setConsecutiveFailures(Integer v) { consecutiveFailures = v; }
    }

    /**
     * Full token-warning state computed by {@link #calculateTokenWarningState}.
     * Translated from the return type of {@code calculateTokenWarningState()} in autoCompact.ts
     */
    public static class TokenWarningState {
        private final int percentLeft;
        private final boolean isAboveWarningThreshold;
        private final boolean isAboveErrorThreshold;
        private final boolean isAboveAutoCompactThreshold;
        private final boolean isAtBlockingLimit;

        public TokenWarningState(int percentLeft, boolean isAboveWarningThreshold, boolean isAboveErrorThreshold,
                                  boolean isAboveAutoCompactThreshold, boolean isAtBlockingLimit) {
            this.percentLeft = percentLeft; this.isAboveWarningThreshold = isAboveWarningThreshold;
            this.isAboveErrorThreshold = isAboveErrorThreshold;
            this.isAboveAutoCompactThreshold = isAboveAutoCompactThreshold;
            this.isAtBlockingLimit = isAtBlockingLimit;
        }
        public int getPercentLeft() { return percentLeft; }
        public boolean isAboveWarningThreshold() { return isAboveWarningThreshold; }
        public boolean isAboveErrorThreshold() { return isAboveErrorThreshold; }
        public boolean isAboveAutoCompactThreshold() { return isAboveAutoCompactThreshold; }
        public boolean isAtBlockingLimit() { return isAtBlockingLimit; }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private boolean isEnvTruthy(String name) {
        String val = System.getenv(name);
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }
}
