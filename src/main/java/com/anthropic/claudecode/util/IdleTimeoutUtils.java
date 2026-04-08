package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Idle timeout manager for SDK mode.
 * Automatically exits the process after the specified idle duration.
 * Translated from src/utils/idleTimeout.ts
 */
@Slf4j
public class IdleTimeoutUtils {



    private IdleTimeoutUtils() {}

    /**
     * Controls an idle timeout timer.
     * Translated from the return type of createIdleTimeoutManager() in idleTimeout.ts
     */
    public interface IdleTimeoutManager {
        /** Start (or restart) the idle timer. */
        void start();

        /** Stop the idle timer without triggering shutdown. */
        void stop();
    }

    /**
     * Creates an idle timeout manager for SDK mode.
     * Automatically shuts down the application after the specified idle duration.
     * Translated from createIdleTimeoutManager() in idleTimeout.ts
     *
     * @param isIdle Supplier that returns {@code true} when the system is idle
     * @return An {@link IdleTimeoutManager} with start/stop methods
     */
    public static IdleTimeoutManager createIdleTimeoutManager(BooleanSupplier isIdle) {
        // Parse CLAUDE_CODE_EXIT_AFTER_STOP_DELAY environment variable
        String exitAfterStopDelayEnv = System.getenv("CLAUDE_CODE_EXIT_AFTER_STOP_DELAY");
        Long delayMs = parsePositiveLong(exitAfterStopDelayEnv);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idle-timeout");
            t.setDaemon(true);
            return t;
        });

        AtomicReference<ScheduledFuture<?>> timerRef = new AtomicReference<>();
        AtomicLong lastIdleTime = new AtomicLong(0);

        return new IdleTimeoutManager() {

            @Override
            public void start() {
                // Cancel any existing timer
                ScheduledFuture<?> existing = timerRef.getAndSet(null);
                if (existing != null) {
                    existing.cancel(false);
                }

                // Only start timer if delay is configured and valid
                if (delayMs == null) {
                    return;
                }

                lastIdleTime.set(System.currentTimeMillis());

                ScheduledFuture<?> future = scheduler.schedule(() -> {
                    long idleDuration = System.currentTimeMillis() - lastIdleTime.get();
                    if (isIdle.getAsBoolean() && idleDuration >= delayMs) {
                        log.debug("Exiting after {}ms of idle time", delayMs);
                        gracefulShutdown();
                    }
                }, delayMs, TimeUnit.MILLISECONDS);

                timerRef.set(future);
            }

            @Override
            public void stop() {
                ScheduledFuture<?> existing = timerRef.getAndSet(null);
                if (existing != null) {
                    existing.cancel(false);
                }
            }
        };
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Perform a graceful JVM shutdown.
     * Translated from gracefulShutdownSync() in gracefulShutdown.ts
     */
    private static void gracefulShutdown() {
        log.info("Initiating graceful shutdown after idle timeout");
        // Trigger JVM shutdown hooks, then exit
        System.exit(0);
    }
}
