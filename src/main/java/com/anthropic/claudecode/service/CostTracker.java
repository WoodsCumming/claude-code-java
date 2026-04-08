package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.*;

/**
 * Thread-safe cost and token usage accumulator for a session.
 * Translated from src/costHook.ts (React hook) and supporting state in bootstrap/state.ts
 *
 * The TypeScript version uses a React useEffect hook (useCostSummary) to print
 * cost on process exit and save session costs. In Java/Spring we expose the same
 * capabilities as a service + a @PreDestroy lifecycle method.
 */
@Slf4j
@Service
public class CostTracker {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CostTracker.class);


    // Atomic accumulators for all session-level metrics
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicLong totalCacheReadInputTokens = new AtomicLong(0);
    private final AtomicLong totalCacheCreationInputTokens = new AtomicLong(0);
    private final AtomicLong totalAPIDurationMs = new AtomicLong(0);
    private final AtomicLong totalAPIDurationWithoutRetriesMs = new AtomicLong(0);
    private final AtomicLong totalToolDurationMs = new AtomicLong(0);
    private final AtomicLong totalLinesAdded = new AtomicLong(0);
    private final AtomicLong totalLinesRemoved = new AtomicLong(0);
    private final AtomicLong totalWebSearchRequests = new AtomicLong(0);
    private final AtomicReference<Double> totalCostUsd = new AtomicReference<>(0.0);
    private final AtomicBoolean hasUnknownModelCost = new AtomicBoolean(false);
    private final long startTime = System.currentTimeMillis();

    // =========================================================================
    // Accumulation methods
    // =========================================================================

    /**
     * Record token usage and cost for a single API call.
     * Translated from addToTotalSessionCost() + addToTotalModelUsage() in cost-tracker.ts
     */
    public void addUsage(long inputTokens, long outputTokens,
                          long cacheReadTokens, long cacheCreationTokens,
                          long apiDurationMs, long apiDurationWithoutRetriesMs) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCacheReadInputTokens.addAndGet(cacheReadTokens);
        totalCacheCreationInputTokens.addAndGet(cacheCreationTokens);
        totalAPIDurationMs.addAndGet(apiDurationMs);
        totalAPIDurationWithoutRetriesMs.addAndGet(apiDurationWithoutRetriesMs);
    }

    /**
     * Record usage with pre-computed cost and model usage object.
     */
    public void addUsageWithModel(double cost, CostTrackerService.TokenUsage usage,
                                   CostTrackerService.ModelUsage modelUsage, String model) {
        totalInputTokens.addAndGet(usage.inputTokens());
        totalOutputTokens.addAndGet(usage.outputTokens());
        totalCacheReadInputTokens.addAndGet(usage.cacheReadInputTokens());
        totalCacheCreationInputTokens.addAndGet(usage.cacheCreationInputTokens());
        totalWebSearchRequests.addAndGet(usage.webSearchRequests());
        totalCostUsd.updateAndGet(prev -> prev + cost);
    }

    /**
     * Add to API duration totals.
     * Translated from addToTotalDurationState() in bootstrap/state.ts
     */
    public void addToTotalDuration(long durationMs, long durationWithoutRetriesMs) {
        totalAPIDurationMs.addAndGet(durationMs);
        totalAPIDurationWithoutRetriesMs.addAndGet(durationWithoutRetriesMs);
    }

    /**
     * Add tool execution duration.
     * Translated from addToToolDuration() in bootstrap/state.ts
     */
    public void addToolDuration(long durationMs) {
        totalToolDurationMs.addAndGet(durationMs);
    }

    /**
     * Add lines-changed counts (for code edit tracking).
     * Translated from addToTotalLinesChanged() in bootstrap/state.ts
     */
    public void addLinesChanged(int linesAdded, int linesRemoved) {
        totalLinesAdded.addAndGet(linesAdded);
        totalLinesRemoved.addAndGet(linesRemoved);
    }

    /**
     * Add cost directly (without usage breakdown).
     */
    public void addCost(double cost) {
        totalCostUsd.updateAndGet(prev -> prev + cost);
    }

    public void addWebSearchRequest() {
        totalWebSearchRequests.incrementAndGet();
    }

    // =========================================================================
    // State flags
    // =========================================================================

    public boolean isHasUnknownModelCost() {
        return hasUnknownModelCost.get();
    }

    public void setHasUnknownModelCost(boolean value) {
        hasUnknownModelCost.set(value);
    }

    // =========================================================================
    // State read
    // =========================================================================

    public double getTotalCostUsd() {
        return totalCostUsd.get();
    }

    public long getTotalAPIDurationMs() {
        return totalAPIDurationMs.get();
    }

    public long getTotalAPIDurationWithoutRetriesMs() {
        return totalAPIDurationWithoutRetriesMs.get();
    }

    public long getTotalToolDurationMs() {
        return totalToolDurationMs.get();
    }

    /**
     * Wall-clock duration since session start.
     * Translated from getTotalDuration() in bootstrap/state.ts (Date.now() - startTime)
     */
    public long getTotalDurationMs() {
        return System.currentTimeMillis() - startTime;
    }

    public SessionStats getStats() {
        return new SessionStats(
            totalInputTokens.get(),
            totalOutputTokens.get(),
            totalCacheReadInputTokens.get(),
            totalCacheCreationInputTokens.get(),
            totalAPIDurationMs.get(),
            totalAPIDurationWithoutRetriesMs.get(),
            totalToolDurationMs.get(),
            getTotalDurationMs(),
            totalLinesAdded.get(),
            totalLinesRemoved.get(),
            (long) totalWebSearchRequests.get(),
            totalCostUsd.get()
        );
    }

    // =========================================================================
    // State reset
    // =========================================================================

    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCacheReadInputTokens.set(0);
        totalCacheCreationInputTokens.set(0);
        totalAPIDurationMs.set(0);
        totalAPIDurationWithoutRetriesMs.set(0);
        totalToolDurationMs.set(0);
        totalLinesAdded.set(0);
        totalLinesRemoved.set(0);
        totalWebSearchRequests.set(0);
        totalCostUsd.set(0.0);
        hasUnknownModelCost.set(false);
    }

    /**
     * Restore state from persisted cost data (e.g. on session resume).
     * Translated from setCostStateForRestore() in bootstrap/state.ts
     */
    public void restoreState(CostTrackerService.StoredCostState data) {
        totalCostUsd.set(data.totalCostUSD());
        totalAPIDurationMs.set(data.totalAPIDuration());
        totalAPIDurationWithoutRetriesMs.set(data.totalAPIDurationWithoutRetries());
        totalToolDurationMs.set(data.totalToolDuration());
        totalLinesAdded.set(data.totalLinesAdded());
        totalLinesRemoved.set(data.totalLinesRemoved());
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Snapshot of all session cost/usage metrics.
     */
    public record SessionStats(
        long totalInputTokens,
        long totalOutputTokens,
        long totalCacheReadInputTokens,
        long totalCacheCreationInputTokens,
        long totalAPIDurationMs,
        long totalAPIDurationWithoutRetriesMs,
        long totalToolDurationMs,
        long totalDurationMs,
        long totalLinesAdded,
        long totalLinesRemoved,
        long totalWebSearchRequests,
        double totalCostUsd
    ) {
        public String formatSummary() {
            return String.format(
                "Cost: $%.4f | Tokens: %d in / %d out | Duration: %.1fs",
                totalCostUsd,
                totalInputTokens,
                totalOutputTokens,
                totalDurationMs / 1000.0
            );
        }
    }
}
