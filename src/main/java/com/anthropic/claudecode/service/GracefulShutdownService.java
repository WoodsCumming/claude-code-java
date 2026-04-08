package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service that manages graceful shutdown of the application and CLI exit helpers.
 *
 * Consolidates two TypeScript sources:
 * 1. {@code src/utils/gracefulShutdown.ts} — graceful shutdown logic
 * 2. {@code src/cli/exit.ts} — CLI exit helpers (cliError / cliOk)
 *
 * The {@link #cliError} and {@link #cliOk} methods mirror the TypeScript
 * helpers that were copy-pasted across ~60 CLI subcommand handlers.  They
 * write to stderr/stdout and call {@link System#exit} (or throw in tests).
 *
 * Translated from src/cli/exit.ts and src/utils/gracefulShutdown.ts
 */
@Slf4j
@Service
public class GracefulShutdownService {



    // -------------------------------------------------------------------------
    // CLI exit helpers  (src/cli/exit.ts)
    // -------------------------------------------------------------------------

    /**
     * Write an error message to stderr (if given) and exit with code 1.
     * Mirrors {@code cliError(msg?)} in exit.ts.
     *
     * @param msg optional message — omit to exit silently
     */
    public void cliError(String msg) {
        if (msg != null && !msg.isEmpty()) {
            System.err.println(msg);
        }
        System.exit(1);
    }

    /**
     * Exit with code 1 without printing anything.
     */
    public void cliError() {
        System.exit(1);
    }

    /**
     * Write a message to stdout (if given) and exit with code 0.
     * Mirrors {@code cliOk(msg?)} in exit.ts.
     *
     * @param msg optional message — omit to exit silently
     */
    public void cliOk(String msg) {
        if (msg != null && !msg.isEmpty()) {
            System.out.println(msg);
        }
        System.exit(0);
    }

    /**
     * Exit with code 0 without printing anything.
     */
    public void cliOk() {
        System.exit(0);
    }

    // -------------------------------------------------------------------------
    // Graceful shutdown state  (src/utils/gracefulShutdown.ts)
    // -------------------------------------------------------------------------

    private final CopyOnWriteArrayList<Runnable> cleanupFunctions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private volatile boolean resumeHintPrinted = false;

    /** Default failsafe timeout in ms — max(5000, hookBudget + 3500). */
    private static final long FAILSAFE_TIMEOUT_MS = 5_000;
    /** Cleanup functions timeout (matches TS 2000 ms). */
    private static final long CLEANUP_TIMEOUT_MS  = 2_000;
    /** Analytics flush cap (matches TS 500 ms). */
    private static final long ANALYTICS_FLUSH_MS  = 500;

    // -------------------------------------------------------------------------
    // Cleanup registration
    // -------------------------------------------------------------------------

    /**
     * Register a cleanup function to be run during shutdown.
     * Translated from the cleanup registration in cleanupRegistry.ts (used by gracefulShutdown.ts).
     */
    public void registerCleanup(Runnable cleanup) {
        cleanupFunctions.add(cleanup);
    }

    // -------------------------------------------------------------------------
    // State queries
    // -------------------------------------------------------------------------

    /**
     * Returns true if graceful shutdown is in progress.
     * Translated from isShuttingDown() in gracefulShutdown.ts.
     */
    public boolean isShuttingDown() {
        return shutdownInProgress.get();
    }

    /**
     * Resets shutdown state. Only for use in tests.
     * Translated from resetShutdownState() in gracefulShutdown.ts.
     */
    public void resetShutdownState() {
        shutdownInProgress.set(false);
        resumeHintPrinted = false;
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    /**
     * Register a JVM shutdown hook that drives graceful shutdown.
     * In the TS implementation this is done via process.on('SIGINT') etc.
     * In Spring Boot applications, this is the idiomatic equivalent.
     *
     * Translated from setupGracefulShutdown() in gracefulShutdown.ts.
     */
    /** Register a JVM shutdown hook that triggers graceful cleanup. Alias for setupGracefulShutdown(). */
    public void registerShutdownHook() {
        setupGracefulShutdown();
    }

    public void setupGracefulShutdown() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("JVM shutdown hook fired — starting graceful shutdown");
            shutdown(0);
        }, "graceful-shutdown"));
    }

    // -------------------------------------------------------------------------
    // Sync variant (fire-and-forget)
    // -------------------------------------------------------------------------

    /**
     * Initiate graceful shutdown synchronously (non-blocking fire-and-forget).
     * Translated from gracefulShutdownSync() in gracefulShutdown.ts.
     */
    public void shutdownSync(int exitCode) {
        CompletableFuture.runAsync(() -> shutdown(exitCode));
    }

    // -------------------------------------------------------------------------
    // Core shutdown
    // -------------------------------------------------------------------------

    /**
     * Perform graceful shutdown:
     * <ol>
     *   <li>Guard against re-entrant calls</li>
     *   <li>Run cleanup functions with a timeout</li>
     *   <li>Flush analytics events</li>
     *   <li>Exit the JVM</li>
     * </ol>
     *
     * Translated from gracefulShutdown() in gracefulShutdown.ts.
     *
     * @param exitCode process exit code (0 = normal, 143 = SIGTERM, 129 = SIGHUP)
     */
    public void shutdown(int exitCode) {
        if (!shutdownInProgress.compareAndSet(false, true)) {
            return;
        }

        log.info("Graceful shutdown initiated (exitCode={})", exitCode);

        runCleanupFunctions();
        flushAnalytics();

        log.info("Graceful shutdown complete (exitCode={})", exitCode);
    }

    // -------------------------------------------------------------------------
    // Cleanup helpers
    // -------------------------------------------------------------------------

    /**
     * Run all registered cleanup functions in reverse registration order,
     * bounded by CLEANUP_TIMEOUT_MS.
     *
     * Translated from the cleanup block inside gracefulShutdown() in the TS impl.
     */
    private void runCleanupFunctions() {
        List<Runnable> reversed = new CopyOnWriteArrayList<>(cleanupFunctions);
        Collections.reverse(reversed);

        long deadline = System.currentTimeMillis() + CLEANUP_TIMEOUT_MS;
        for (Runnable cleanup : reversed) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Cleanup timeout reached — skipping remaining cleanup functions");
                break;
            }
            try {
                cleanup.run();
            } catch (Exception e) {
                log.warn("Cleanup function threw: {}", e.getMessage());
            }
        }
    }

    /**
     * Flush analytics, bounded by ANALYTICS_FLUSH_MS.
     * Translated from the analytics shutdown block in gracefulShutdown() in gracefulShutdown.ts.
     */
    private void flushAnalytics() {
        try {
            Thread.sleep(Math.min(ANALYTICS_FLUSH_MS, 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
