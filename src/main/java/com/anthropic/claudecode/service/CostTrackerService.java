package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for session cost tracking, persistence, and formatting.
 * Translated from src/cost-tracker.ts
 *
 * Aggregates token usage and costs per model, saves/restores from project config,
 * and formats cost summaries for display.
 */
@Slf4j
@Service
public class CostTrackerService {



    private final CostTracker costTracker;
    private final ProjectConfigService projectConfigService;
    private final BootstrapStateService bootstrapStateService;

    // Per-model usage accumulation
    private final ConcurrentHashMap<String, ModelUsage> modelUsageMap = new ConcurrentHashMap<>();

    @Autowired
    public CostTrackerService(CostTracker costTracker,
                               ProjectConfigService projectConfigService,
                               BootstrapStateService bootstrapStateService) {
        this.costTracker = costTracker;
        this.projectConfigService = projectConfigService;
        this.bootstrapStateService = bootstrapStateService;
    }

    // =========================================================================
    // Session cost persistence
    // =========================================================================

    /**
     * Get stored cost state from project config for a specific session.
     * Returns null if the session ID does not match the last saved session.
     * Translated from getStoredSessionCosts() in cost-tracker.ts
     */
    public StoredCostState getStoredSessionCosts(String sessionId) {
        ProjectConfigService.ProjectConfig config = projectConfigService.getCurrentProjectConfig();
        if (!sessionId.equals(config.getLastSessionId())) {
            return null;
        }

        // Reconstruct ModelUsage from stored map (lastModelUsage is Map<String, Object>)
        Map<String, ModelUsage> resolvedUsage = null;
        if (config.getLastModelUsage() != null) {
            resolvedUsage = new HashMap<>();
            for (Map.Entry<String, Object> entry : config.getLastModelUsage().entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) raw;
                    resolvedUsage.put(entry.getKey(), new ModelUsage(
                        toLong(m.get("inputTokens")),
                        toLong(m.get("outputTokens")),
                        toLong(m.get("cacheReadInputTokens")),
                        toLong(m.get("cacheCreationInputTokens")),
                        toLong(m.get("webSearchRequests")),
                        toDouble(m.get("costUSD")),
                        0L,
                        0L
                    ));
                }
            }
        }

        return new StoredCostState(
            0.0, // lastCost not stored in current ProjectConfig schema
            config.getLastAPIDuration() != null ? config.getLastAPIDuration() : 0L,
            0L, 0L, 0, 0, null,
            resolvedUsage
        );
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    /**
     * Restore cost state from project config when resuming a session.
     * Only restores if the session ID matches the last saved session.
     * Returns true if cost state was restored.
     * Translated from restoreCostStateForSession() in cost-tracker.ts
     */
    public boolean restoreCostStateForSession(String sessionId) {
        StoredCostState data = getStoredSessionCosts(sessionId);
        if (data == null) return false;
        costTracker.restoreState(data);
        if (data.modelUsage() != null) {
            modelUsageMap.putAll(data.modelUsage());
        }
        return true;
    }

    /**
     * Save the current session's costs to project config.
     * Translated from saveCurrentSessionCosts() in cost-tracker.ts
     */
    public void saveCurrentSessionCosts() {
        saveCurrentSessionCosts(null);
    }

    public void saveCurrentSessionCosts(FpsMetrics fpsMetrics) {
        CostTracker.SessionStats stats = costTracker.getStats();

        // Serialize model usage (strip runtime-only contextWindow/maxOutputTokens)
        Map<String, Object> storedModelUsage = new HashMap<>();
        for (Map.Entry<String, ModelUsage> entry : modelUsageMap.entrySet()) {
            ModelUsage u = entry.getValue();
            Map<String, Object> usageMap = new LinkedHashMap<>();
            usageMap.put("inputTokens", u.inputTokens());
            usageMap.put("outputTokens", u.outputTokens());
            usageMap.put("cacheReadInputTokens", u.cacheReadInputTokens());
            usageMap.put("cacheCreationInputTokens", u.cacheCreationInputTokens());
            usageMap.put("webSearchRequests", u.webSearchRequests());
            usageMap.put("costUSD", u.costUSD());
            storedModelUsage.put(entry.getKey(), usageMap);
        }

        projectConfigService.updateProjectConfig(current -> {
            current.setLastAPIDuration(stats.totalAPIDurationMs());
            current.setLastSessionId(bootstrapStateService.getSessionId());
            current.setLastModelUsage(storedModelUsage);
            return current;
        });
    }

    // =========================================================================
    // Cost accumulation
    // =========================================================================

    /**
     * Add token usage and cost to the current session totals.
     * Also accumulates per-model usage.
     * Translated from addToTotalSessionCost() in cost-tracker.ts
     */
    public double addToTotalSessionCost(double cost, TokenUsage usage, String model) {
        ModelUsage modelUsage = addToTotalModelUsage(cost, usage, model);
        costTracker.addUsageWithModel(cost, usage, modelUsage, model);
        return cost;
    }

    private ModelUsage addToTotalModelUsage(double cost, TokenUsage usage, String model) {
        ModelUsage existing = modelUsageMap.computeIfAbsent(model, k -> new ModelUsage(
            0, 0, 0, 0, 0, 0.0, 0, 0));

        ModelUsage updated = new ModelUsage(
            existing.inputTokens() + usage.inputTokens(),
            existing.outputTokens() + usage.outputTokens(),
            existing.cacheReadInputTokens() + usage.cacheReadInputTokens(),
            existing.cacheCreationInputTokens() + usage.cacheCreationInputTokens(),
            existing.webSearchRequests() + usage.webSearchRequests(),
            existing.costUSD() + cost,
            0,  // contextWindow — filled lazily
            0   // maxOutputTokens — filled lazily
        );
        modelUsageMap.put(model, updated);
        return updated;
    }

    // =========================================================================
    // Display formatting
    // =========================================================================

    /**
     * Format total cost as a dimmed multi-line summary string.
     * Translated from formatTotalCost() in cost-tracker.ts
     */
    public String formatTotalCost() {
        CostTracker.SessionStats stats = costTracker.getStats();

        boolean hasUnknownCost = costTracker.isHasUnknownModelCost();
        String costDisplay = formatCost(stats.totalCostUsd(), 4)
            + (hasUnknownCost
               ? " (costs may be inaccurate due to usage of unknown models)"
               : "");

        String modelUsageDisplay = formatModelUsage();

        int linesAdded = (int) stats.totalLinesAdded();
        int linesRemoved = (int) stats.totalLinesRemoved();

        return String.format(
            "Total cost:            %s%n"
            + "Total duration (API):  %s%n"
            + "Total duration (wall): %s%n"
            + "Total code changes:    %d %s added, %d %s removed%n"
            + "%s",
            costDisplay,
            formatDuration(stats.totalAPIDurationMs()),
            formatDuration(stats.totalDurationMs()),
            linesAdded, linesAdded == 1 ? "line" : "lines",
            linesRemoved, linesRemoved == 1 ? "line" : "lines",
            modelUsageDisplay
        );
    }

    private String formatModelUsage() {
        if (modelUsageMap.isEmpty()) {
            return "Usage:                 0 input, 0 output, 0 cache read, 0 cache write";
        }

        // Accumulate by canonical short name
        Map<String, ModelUsage> byShortName = new LinkedHashMap<>();
        for (Map.Entry<String, ModelUsage> entry : modelUsageMap.entrySet()) {
            String shortName = getCanonicalName(entry.getKey());
            ModelUsage existing = byShortName.getOrDefault(shortName, new ModelUsage(
                0, 0, 0, 0, 0, 0.0, 0, 0));
            ModelUsage u = entry.getValue();
            byShortName.put(shortName, new ModelUsage(
                existing.inputTokens() + u.inputTokens(),
                existing.outputTokens() + u.outputTokens(),
                existing.cacheReadInputTokens() + u.cacheReadInputTokens(),
                existing.cacheCreationInputTokens() + u.cacheCreationInputTokens(),
                existing.webSearchRequests() + u.webSearchRequests(),
                existing.costUSD() + u.costUSD(),
                u.contextWindow(),
                u.maxOutputTokens()
            ));
        }

        StringBuilder result = new StringBuilder("Usage by model:");
        for (Map.Entry<String, ModelUsage> entry : byShortName.entrySet()) {
            ModelUsage u = entry.getValue();
            String usageStr = String.format(
                "  %s input, %s output, %s cache read, %s cache write%s (%s)",
                formatNumber(u.inputTokens()),
                formatNumber(u.outputTokens()),
                formatNumber(u.cacheReadInputTokens()),
                formatNumber(u.cacheCreationInputTokens()),
                u.webSearchRequests() > 0
                    ? ", " + formatNumber(u.webSearchRequests()) + " web search" : "",
                formatCost(u.costUSD(), 4)
            );
            String label = String.format("%21s", entry.getKey() + ":");
            result.append('\n').append(label).append(usageStr);
        }
        return result.toString();
    }

    static String formatCost(double cost, int maxDecimalPlaces) {
        if (cost > 0.5) {
            return String.format("$%.2f", Math.round(cost * 100.0) / 100.0);
        }
        return "$" + String.format("%." + maxDecimalPlaces + "f", cost);
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long minutes = ms / 60_000;
        long seconds = (ms % 60_000) / 1000;
        return minutes + "m " + seconds + "s";
    }

    private static String formatNumber(long n) {
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String getCanonicalName(String model) {
        // Derive short name: strip version suffixes
        if (model.contains("claude-opus")) return "claude-opus";
        if (model.contains("claude-sonnet")) return "claude-sonnet";
        if (model.contains("claude-haiku")) return "claude-haiku";
        return model;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Stored cost state (persisted in project config, per-session).
     * Translated from StoredCostState in cost-tracker.ts
     */
    public record StoredCostState(
        double totalCostUSD,
        long totalAPIDuration,
        long totalAPIDurationWithoutRetries,
        long totalToolDuration,
        int totalLinesAdded,
        int totalLinesRemoved,
        Long lastDuration,
        Map<String, ModelUsage> modelUsage
    ) {}

    /**
     * Per-model usage accumulator.
     * Translated from ModelUsage in agentSdkTypes.ts
     */
    public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        long webSearchRequests,
        double costUSD,
        long contextWindow,
        long maxOutputTokens
    ) {}

    /**
     * Stored model usage (without runtime-only contextWindow/maxOutputTokens).
     */
    public record StoredModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        long webSearchRequests,
        double costUSD
    ) {}

    /**
     * Token usage from an API response.
     */
    public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        long webSearchRequests
    ) {}

    /**
     * FPS performance metrics (for saveCurrentSessionCosts).
     */
    public record FpsMetrics(double averageFps, double low1PctFps) {}
}
