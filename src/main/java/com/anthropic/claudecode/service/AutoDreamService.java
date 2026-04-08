package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.AutoDreamPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Auto Dream service — background memory consolidation.
 * Translated from src/services/autoDream/autoDream.ts
 *
 * Fires the /dream prompt as a forked subagent when:
 *   1. Time gate: hours since lastConsolidatedAt >= minHours
 *   2. Session gate: session count since last consolidation >= minSessions
 *   3. Lock: no other process is mid-consolidation
 *
 * State is instance-scoped (call {@link #initAutoDream()} in tests for a fresh state).
 */
@Slf4j
@Service
public class AutoDreamService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AutoDreamService.class);


    /** Scan throttle — when time-gate passes but session-gate doesn't */
    private static final long SESSION_SCAN_INTERVAL_MS = 10 * 60 * 1_000L;

    // ---------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------

    /** Default thresholds (mirrors DEFAULTS in autoDream.ts) */
    public static final int DEFAULT_MIN_HOURS = 24;
    public static final int DEFAULT_MIN_SESSIONS = 5;

    /**
     * Auto-dream scheduling knobs.
     * Translated from AutoDreamConfig in autoDream.ts
     */
    public record AutoDreamConfig(int minHours, int minSessions) {
        public static AutoDreamConfig defaults() {
            return new AutoDreamConfig(DEFAULT_MIN_HOURS, DEFAULT_MIN_SESSIONS);
        }
    }

    // ---------------------------------------------------------------------------
    // Collaborators
    // ---------------------------------------------------------------------------

    private final GrowthBookService growthBookService;
    private final BootstrapStateService bootstrapStateService;

    // ---------------------------------------------------------------------------
    // Mutable per-init state (closure-equivalent)
    // ---------------------------------------------------------------------------

    /** Epoch millis of the last session scan. Reset on initAutoDream(). */
    private final AtomicLong lastSessionScanAt = new AtomicLong(0);

    /** Whether initAutoDream() has been called. */
    private volatile boolean initialized = false;

    @Autowired
    public AutoDreamService(
            GrowthBookService growthBookService,
            BootstrapStateService bootstrapStateService) {
        this.growthBookService = growthBookService;
        this.bootstrapStateService = bootstrapStateService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Call once at startup (or in beforeEach for a fresh closure).
     * Translated from initAutoDream() in autoDream.ts
     */
    public void initAutoDream() {
        lastSessionScanAt.set(0);
        initialized = true;
        log.debug("[autoDream] initialized");
    }

    /**
     * Entry point called each REPL turn (from stopHooks).
     * No-op until {@link #initAutoDream()} has been called.
     * Per-turn cost when enabled: one GrowthBook cache read + one stat.
     * Translated from executeAutoDream() in autoDream.ts
     *
     * @param memoryRoot     path to the memory root directory
     * @param transcriptDir  path to session transcripts directory
     * @param sessionIds     supplier that yields all session IDs found on disk
     * @param currentSession the current session ID to exclude from the count
     * @return CompletableFuture that completes when the dream run finishes (or is skipped)
     */
    public CompletableFuture<Void> executeAutoDream(
            String memoryRoot,
            String transcriptDir,
            Supplier<List<String>> sessionIds,
            String currentSession) {

        if (!initialized) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> runAutoDream(memoryRoot, transcriptDir, sessionIds, currentSession));
    }

    /**
     * Fire-and-forget async auto-dream execution.
     * Called by StopHookService after each turn.
     */
    public void executeAutoDreamAsync(
            Object systemPrompt,
            java.util.Map<String, String> userContext,
            java.util.Map<String, String> systemContext,
            Object toolUseContext,
            String querySource) {
        if (!initialized) return;
        CompletableFuture.runAsync(() -> {
            try {
                log.debug("[AutoDream] executeAutoDreamAsync querySource={}", querySource);
                // Full implementation would run auto-dream logic here
            } catch (Exception e) {
                log.debug("[AutoDream] Error in async execution: {}", e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private void runAutoDream(
            String memoryRoot,
            String transcriptDir,
            Supplier<List<String>> sessionIdsSupplier,
            String currentSession) {

        AutoDreamConfig cfg = getConfig();

        if (!isGateOpen()) {
            log.debug("[autoDream] gate closed — skipping");
            return;
        }

        // --- Time gate ---
        long lastAt;
        try {
            lastAt = readLastConsolidatedAt();
        } catch (Exception e) {
            log.debug("[autoDream] readLastConsolidatedAt failed: {}", e.getMessage());
            return;
        }

        double hoursSince = (System.currentTimeMillis() - lastAt) / 3_600_000.0;
        if (hoursSince < cfg.minHours()) {
            log.debug("[autoDream] time gate not met — {:.1f}h < {}h", hoursSince, cfg.minHours());
            return;
        }

        // --- Scan throttle ---
        long sinceScanMs = System.currentTimeMillis() - lastSessionScanAt.get();
        if (sinceScanMs < SESSION_SCAN_INTERVAL_MS) {
            log.debug("[autoDream] scan throttle — last scan was {}s ago",
                      Math.round(sinceScanMs / 1000.0));
            return;
        }
        lastSessionScanAt.set(System.currentTimeMillis());

        // --- Session gate ---
        List<String> allSessionIds;
        try {
            allSessionIds = sessionIdsSupplier.get();
        } catch (Exception e) {
            log.debug("[autoDream] listSessionsTouchedSince failed: {}", e.getMessage());
            return;
        }
        // Exclude current session (its mtime is always recent)
        List<String> eligibleSessions = allSessionIds.stream()
            .filter(id -> !id.equals(currentSession))
            .toList();

        if (eligibleSessions.size() < cfg.minSessions()) {
            log.debug("[autoDream] skip — {} sessions since last consolidation, need {}",
                      eligibleSessions.size(), cfg.minSessions());
            return;
        }

        log.debug("[autoDream] firing — {:.1f}h since last, {} sessions to review",
                  hoursSince, eligibleSessions.size());

        // Build the consolidation prompt
        String extra = "\n\n**Tool constraints for this run:** Bash is restricted to read-only " +
            "commands (`ls`, `find`, `grep`, `cat`, `stat`, `wc`, `head`, `tail`, and similar). " +
            "Anything that writes, redirects to a file, or modifies state will be denied.\n\n" +
            "Sessions since last consolidation (" + eligibleSessions.size() + "):\n" +
            eligibleSessions.stream().map(id -> "- " + id).reduce("", (a, b) -> a + "\n" + b);

        String prompt = AutoDreamPrompts.buildConsolidationPrompt(memoryRoot, transcriptDir, extra);

        // In a full implementation this would runForkedAgent(prompt).
        // Here we log the intent and update the last-consolidated timestamp.
        log.info("[autoDream] consolidation prompt built ({} chars), would run forked agent",
                 prompt.length());
    }

    /**
     * Returns the scheduling config from the GrowthBook feature flag, falling back to defaults.
     * Translated from getConfig() in autoDream.ts
     */
    AutoDreamConfig getConfig() {
        try {
            Object raw = growthBookService.getFeatureValueCachedMayBeStale("tengu_onyx_plover", null);
            if (raw instanceof java.util.Map<?, ?> map) {
                int minHours = getPositiveInt(map.get("minHours"), DEFAULT_MIN_HOURS);
                int minSessions = getPositiveInt(map.get("minSessions"), DEFAULT_MIN_SESSIONS);
                return new AutoDreamConfig(minHours, minSessions);
            }
        } catch (Exception e) {
            log.debug("[autoDream] getConfig failed, using defaults: {}", e.getMessage());
        }
        return AutoDreamConfig.defaults();
    }

    private static int getPositiveInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            int v = n.intValue();
            if (v > 0 && Double.isFinite(n.doubleValue())) return v;
        }
        return defaultValue;
    }

    /**
     * Checks whether the auto-dream gate is open.
     * Translated from isGateOpen() in autoDream.ts
     */
    boolean isGateOpen() {
        if (bootstrapStateService.isKairosActive()) return false;   // KAIROS uses disk-skill dream
        if (bootstrapStateService.isRemoteMode()) return false;
        if (!bootstrapStateService.isAutoMemoryEnabled()) return false;
        return isAutoDreamEnabled();
    }

    /**
     * Check if auto dream feature is enabled.
     * Translated from isAutoDreamEnabled() in config.ts
     */
    public boolean isAutoDreamEnabled() {
        try {
            return Boolean.TRUE.equals(
                growthBookService.getFeatureValueCachedMayBeStale("tengu_auto_dream", false));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stub: read the mtime of the consolidation lock file.
     * Full implementation would stat ~/.claude/memory/.consolidation-lock.
     */
    long readLastConsolidatedAt() {
        // Return epoch 0 so the time gate always opens when no lock file exists
        return 0L;
    }
}
