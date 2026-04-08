package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Git availability check utilities.
 * Translated from src/utils/plugins/gitAvailability.ts
 */
@Slf4j
public class GitAvailability {



    private static final AtomicReference<Boolean> cachedGitAvailable = new AtomicReference<>(null);

    /**
     * Check if git is available.
     * Translated from isGitAvailable() in gitAvailability.ts
     */
    public static CompletableFuture<Boolean> isGitAvailable() {
        Boolean cached = cachedGitAvailable.get();
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                boolean available = finished && process.exitValue() == 0;
                cachedGitAvailable.set(available);
                return available;
            } catch (Exception e) {
                cachedGitAvailable.set(false);
                return false;
            }
        });
    }

    private GitAvailability() {}
}
