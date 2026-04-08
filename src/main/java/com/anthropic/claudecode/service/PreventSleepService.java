package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prevents macOS from sleeping while Claude is working.
 * Translated from src/services/preventSleep.ts
 *
 * <p>Uses the built-in {@code caffeinate} command to create a power assertion
 * that prevents idle sleep. The caffeinate process is spawned with a timeout
 * and periodically restarted. This provides self-healing behaviour: if the JVM
 * process is killed unexpectedly, the orphaned caffeinate will automatically
 * exit after the timeout.</p>
 *
 * <p>Only active on macOS — all methods are no-ops on other platforms.</p>
 */
@Slf4j
@Service
public class PreventSleepService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PreventSleepService.class);


    // ── Constants ─────────────────────────────────────────────────────────────

    /** Caffeinate timeout in seconds; process auto-exits after this duration. */
    private static final int CAFFEINATE_TIMEOUT_SECONDS = 300; // 5 minutes

    /**
     * Restart interval: restart caffeinate before it expires.
     * 4 minutes gives plenty of buffer before the 5-minute timeout.
     */
    private static final long RESTART_INTERVAL_MS = 4L * 60 * 1_000;

    // ── State ─────────────────────────────────────────────────────────────────

    private final AtomicInteger refCount = new AtomicInteger(0);

    private volatile Process caffeinateProcess = null;
    private ScheduledFuture<?> restartTask = null;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "prevent-sleep-scheduler");
        t.setDaemon(true); // Don't keep the JVM alive (mirrors .unref() in Node)
        return t;
    });

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Increment the reference count and start preventing sleep if needed.
     * Call this when starting work that should keep the Mac awake.
     * Translated from startPreventSleep() in preventSleep.ts
     */
    public void startPreventSleep() {
        if (refCount.incrementAndGet() == 1) {
            spawnCaffeinate();
            startRestartInterval();
        }
    }

    /**
     * Decrement the reference count and allow sleep if no more work is pending.
     * Call this when work completes.
     * Translated from stopPreventSleep() in preventSleep.ts
     */
    public void stopPreventSleep() {
        if (refCount.get() > 0 && refCount.decrementAndGet() == 0) {
            stopRestartInterval();
            killCaffeinate();
        }
    }

    /**
     * Force stop preventing sleep regardless of reference count.
     * Use this for cleanup on JVM shutdown.
     * Translated from forceStopPreventSleep() in preventSleep.ts
     */
    public void forceStopPreventSleep() {
        refCount.set(0);
        stopRestartInterval();
        killCaffeinate();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void startRestartInterval() {
        if (!isMacOs()) return;
        if (restartTask != null) return;

        restartTask = scheduler.scheduleAtFixedRate(() -> {
            if (refCount.get() > 0) {
                log.debug("Restarting caffeinate to maintain sleep prevention");
                killCaffeinate();
                spawnCaffeinate();
            }
        }, RESTART_INTERVAL_MS, RESTART_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopRestartInterval() {
        if (restartTask != null) {
            restartTask.cancel(false);
            restartTask = null;
        }
    }

    private void spawnCaffeinate() {
        if (!isMacOs()) return;
        if (caffeinateProcess != null) return;

        try {
            // -i: Create an assertion to prevent idle sleep (least aggressive; display can sleep)
            // -t: Timeout — caffeinate exits automatically; provides self-healing on SIGKILL
            caffeinateProcess = new ProcessBuilder(
                    "caffeinate", "-i", "-t", String.valueOf(CAFFEINATE_TIMEOUT_SECONDS))
                    .inheritIO()
                    .start();

            final Process thisProc = caffeinateProcess;
            // Monitor for unexpected exit
            Thread.ofVirtual().start(() -> {
                try {
                    thisProc.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (caffeinateProcess == thisProc) {
                    caffeinateProcess = null;
                }
            });

            log.debug("Started caffeinate to prevent sleep");
        } catch (Exception e) {
            // Silently fail — caffeinate not available or spawn failed
            log.debug("caffeinate spawn error: {}", e.getMessage());
            caffeinateProcess = null;
        }
    }

    private void killCaffeinate() {
        Process proc = caffeinateProcess;
        if (proc != null) {
            caffeinateProcess = null;
            try {
                // SIGKILL for immediate termination (SIGTERM could be delayed)
                proc.destroyForcibly();
                log.debug("Stopped caffeinate, allowing sleep");
            } catch (Exception e) {
                // Process may have already exited
                log.debug("caffeinate kill error (may already be dead): {}", e.getMessage());
            }
        }
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
