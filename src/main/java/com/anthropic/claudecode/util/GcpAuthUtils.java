package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GCP authentication utilities.
 * Translated from GCP-related functions in src/utils/auth.ts
 *
 * Handles GCP credential checking, refreshing, and caching.
 */
@Slf4j
public final class GcpAuthUtils {



    /** Short timeout for the GCP credentials probe (5 seconds). */
    private static final long GCP_CREDENTIALS_CHECK_TIMEOUT_MS = 5_000;

    /** Timeout for GCP auth refresh command (3 minutes). */
    private static final long GCP_AUTH_REFRESH_TIMEOUT_MS = 3 * 60 * 1_000;

    /** Default GCP credential TTL - 1 hour to match typical ADC token lifetime. */
    private static final long DEFAULT_GCP_CREDENTIAL_TTL = 60 * 60 * 1_000;

    /** Cached result of last credential refresh, with expiry timestamp. */
    private static volatile Boolean cachedRefreshResult = null;
    private static final AtomicLong cacheExpiry = new AtomicLong(0L);

    private GcpAuthUtils() {}

    /**
     * Check if GCP credentials are currently valid by attempting to get an access token.
     * Mirrors checkGcpCredentialsValid() in auth.ts.
     *
     * Uses a probe that executes {@code gcloud auth application-default print-access-token}
     * with a short timeout to avoid hanging when no credentials are configured.
     */
    public static CompletableFuture<Boolean> checkGcpCredentialsValid() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "gcloud", "auth", "application-default", "print-access-token"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean finished = process.waitFor(
                    GCP_CREDENTIALS_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS
                );
                if (!finished) {
                    process.destroyForcibly();
                    log.debug("GCP credentials check timed out");
                    return false;
                }
                return process.exitValue() == 0;
            } catch (IOException | InterruptedException e) {
                log.debug("GCP credentials check failed: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Run gcpAuthRefresh command to perform interactive authentication.
     * Mirrors refreshGcpAuth() in auth.ts.
     *
     * @param gcpAuthRefresh the shell command to run (e.g. "gcloud auth application-default login")
     * @return CompletableFuture that resolves to true on success, false on failure
     */
    public static CompletableFuture<Boolean> refreshGcpAuth(String gcpAuthRefresh) {
        log.debug("Running GCP auth refresh command");
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", gcpAuthRefresh);
                pb.inheritIO();
                Process process = pb.start();
                boolean finished = process.waitFor(
                    GCP_AUTH_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS
                );
                if (!finished) {
                    process.destroyForcibly();
                    log.error("GCP auth refresh timed out after 3 minutes. "
                        + "Run your auth command manually in a separate terminal.");
                    return false;
                }
                boolean success = process.exitValue() == 0;
                if (success) {
                    log.debug("GCP auth refresh completed successfully");
                } else {
                    log.error("Error running gcpAuthRefresh command");
                }
                return success;
            } catch (IOException | InterruptedException e) {
                log.error("Failed to run GCP auth refresh: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Refresh GCP credentials if needed.
     * Memoized with TTL to avoid excessive refresh attempts.
     * Mirrors refreshGcpCredentialsIfNeeded() in auth.ts.
     *
     * @param gcpAuthRefresh the refresh command, or null if not configured
     * @return CompletableFuture resolving to true if refresh was performed
     */
    public static CompletableFuture<Boolean> refreshGcpCredentialsIfNeeded(String gcpAuthRefresh) {
        long now = System.currentTimeMillis();
        long expiry = cacheExpiry.get();
        if (cachedRefreshResult != null && now < expiry) {
            return CompletableFuture.completedFuture(cachedRefreshResult);
        }

        if (gcpAuthRefresh == null || gcpAuthRefresh.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }

        return checkGcpCredentialsValid().thenCompose(isValid -> {
            if (isValid) {
                log.debug("GCP credentials are valid, skipping auth refresh command");
                updateCache(false, now);
                return CompletableFuture.completedFuture(false);
            }
            return refreshGcpAuth(gcpAuthRefresh).thenApply(refreshed -> {
                updateCache(refreshed, now);
                return refreshed;
            });
        });
    }

    /**
     * Clear the credential refresh cache.
     * Mirrors clearGcpCredentialsCache() in auth.ts.
     */
    public static void clearGcpCredentialsCache() {
        cachedRefreshResult = null;
        cacheExpiry.set(0L);
    }

    /**
     * Determine whether gcpAuthRefresh comes from project settings (security check).
     * Mirrors isGcpAuthRefreshFromProjectSettings() in auth.ts.
     *
     * @param gcpAuthRefreshValue the configured command
     * @param projectGcpAuthRefresh the project-level configured command (may be null)
     * @param localGcpAuthRefresh the local-level configured command (may be null)
     * @return true if the command originates from project or local settings
     */
    public static boolean isGcpAuthRefreshFromProjectSettings(
            String gcpAuthRefreshValue,
            String projectGcpAuthRefresh,
            String localGcpAuthRefresh) {
        if (gcpAuthRefreshValue == null) {
            return false;
        }
        return gcpAuthRefreshValue.equals(projectGcpAuthRefresh)
            || gcpAuthRefreshValue.equals(localGcpAuthRefresh);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void updateCache(boolean result, long now) {
        cachedRefreshResult = result;
        cacheExpiry.set(now + DEFAULT_GCP_CREDENTIAL_TTL);
    }
}
