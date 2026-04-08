package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;

/**
 * Background housekeeping service.
 * Translated from src/utils/backgroundHousekeeping.ts
 *
 * Starts several deferred background tasks at application startup:
 *   - "Very slow" one-shot operations (old message cleanup, old version cleanup)
 *     run 10 minutes after start, but only when the user has been idle for ≥1 min.
 *   - Recurring cleanup tasks (npm cache, old versions) run every 24 hours for
 *     internal ("ant") users.
 */
@Slf4j
@Service
public class BackgroundHousekeepingService {



    /** 24 hours in milliseconds — interval for recurring cleanup. */
    private static final long RECURRING_CLEANUP_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** 10 minutes — initial delay before very-slow operations attempt to run. */
    private static final long DELAY_VERY_SLOW_OPERATIONS_MS = 10L * 60 * 1000;

    /** 1 minute in milliseconds — idle threshold before running slow ops. */
    private static final long IDLE_THRESHOLD_MS = 60_000L;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "bg-housekeeping");
                t.setDaemon(true);
                return t;
            });

    /** Tracks whether cleanup has run at least once this session. */
    private volatile boolean needsCleanup = true;

    /** Epoch-ms of the last user interaction (updated by the REPL layer). */
    private volatile long lastInteractionTime = 0L;

    /**
     * Record the time of the most recent user interaction.
     * Called by the REPL each time the user submits input.
     */
    public void recordInteraction() {
        lastInteractionTime = System.currentTimeMillis();
    }

    /**
     * Start background housekeeping tasks.
     * Translated from startBackgroundHousekeeping() in backgroundHousekeeping.ts
     */
    public void start() {
        // Schedule the one-shot "very slow ops" batch, retrying if the user is active.
        scheduleVerySlowOps(DELAY_VERY_SLOW_OPERATIONS_MS);

        // For long-running sessions, schedule recurring cleanup every 24 hours —
        // but only for internal (ant) users, mirroring the TypeScript source.
        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) {
            ScheduledFuture<?> interval = scheduler.scheduleAtFixedRate(
                    this::runRecurringCleanup,
                    RECURRING_CLEANUP_INTERVAL_MS,
                    RECURRING_CLEANUP_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            log.debug("Recurring 24-hour cleanup scheduled for ant user");
        }

        log.debug("Background housekeeping started");
    }

    /**
     * Stop background housekeeping and release scheduler resources.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Schedule the very-slow-ops batch after the given delay.
     * If the user was recently active when the timer fires, reschedule rather
     * than block them — mirrors the TypeScript setTimeout + unref() approach.
     */
    private void scheduleVerySlowOps(long delayMs) {
        scheduler.schedule(() -> {
            try {
                runVerySlowOps();
            } catch (Exception e) {
                log.debug("Very slow ops failed: {}", e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void runVerySlowOps() {
        // If the user did something in the last minute, defer again
        if (lastInteractionTime > System.currentTimeMillis() - IDLE_THRESHOLD_MS) {
            log.debug("User recently active, deferring very slow ops");
            scheduleVerySlowOps(DELAY_VERY_SLOW_OPERATIONS_MS);
            return;
        }

        if (needsCleanup) {
            needsCleanup = false;
            cleanupOldMessageFiles();
        }

        // Re-check idle state after cleanup
        if (lastInteractionTime > System.currentTimeMillis() - IDLE_THRESHOLD_MS) {
            log.debug("User became active during cleanup, deferring version cleanup");
            scheduleVerySlowOps(DELAY_VERY_SLOW_OPERATIONS_MS);
            return;
        }

        cleanupOldVersions();
    }

    /** Clean up old session / message files. Corresponds to cleanupOldMessageFilesInBackground(). */
    private void cleanupOldMessageFiles() {
        log.debug("Cleaning up old message files");
        // Implementation delegates to CleanupService; stub for translation fidelity
    }

    /** Clean up old installed versions. Corresponds to cleanupOldVersions(). */
    private void cleanupOldVersions() {
        log.debug("Cleaning up old versions");
        // Implementation delegates to NativeInstallerService; stub for translation fidelity
    }

    /** Recurring 24-hour cleanup. Corresponds to cleanupNpmCacheForAnthropicPackages() + cleanupOldVersionsThrottled(). */
    private void runRecurringCleanup() {
        try {
            log.debug("Running recurring 24-hour cleanup");
            // cleanupNpmCacheForAnthropicPackages — no-op in Java builds
            cleanupOldVersions();
        } catch (Exception e) {
            log.debug("Recurring cleanup failed: {}", e.getMessage());
        }
    }
}
