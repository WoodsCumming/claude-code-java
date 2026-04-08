package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.*;

/**
 * Global registry for cleanup functions.
 * Translated from src/utils/cleanupRegistry.ts
 */
@Slf4j
public class CleanupRegistry {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CleanupRegistry.class);


    private static final Set<Runnable> cleanupFunctions = new LinkedHashSet<>();

    /**
     * Register a cleanup function.
     * Translated from registerCleanup() in cleanupRegistry.ts
     *
     * @return Unregister function
     */
    public static synchronized Runnable registerCleanup(Runnable cleanupFn) {
        cleanupFunctions.add(cleanupFn);
        return () -> {
            synchronized (CleanupRegistry.class) {
                cleanupFunctions.remove(cleanupFn);
            }
        };
    }

    /**
     * Run all registered cleanup functions.
     * Translated from runCleanupFunctions() in cleanupRegistry.ts
     */
    public static void runCleanupFunctions() {
        List<Runnable> functions;
        synchronized (CleanupRegistry.class) {
            functions = new ArrayList<>(cleanupFunctions);
        }

        List<CompletableFuture<Void>> futures = functions.stream()
            .map(fn -> CompletableFuture.runAsync(() -> {
                try {
                    fn.run();
                } catch (Exception e) {
                    log.warn("Cleanup function failed: {}", e.getMessage());
                }
            }))
            .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Cleanup timed out or failed: {}", e.getMessage());
        }
    }

    /**
     * Clear all registered cleanup functions (for testing).
     */
    public static synchronized void clear() {
        cleanupFunctions.clear();
    }

    private CleanupRegistry() {}
}
