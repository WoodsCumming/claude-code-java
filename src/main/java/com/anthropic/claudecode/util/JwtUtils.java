package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * JWT utilities and token refresh scheduling.
 * Translated from src/bridge/jwtUtils.ts
 */
@Slf4j
public class JwtUtils {



    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Refresh buffer: request a new token this many ms before expiry. */
    private static final long TOKEN_REFRESH_BUFFER_MS = 5 * 60 * 1000L;

    /** Fallback refresh interval when the new token's expiry is unknown (30 minutes). */
    private static final long FALLBACK_REFRESH_INTERVAL_MS = 30 * 60 * 1000L;

    /** Max consecutive failures before giving up on the refresh chain. */
    private static final int MAX_REFRESH_FAILURES = 3;

    /** Retry delay when getAccessToken returns null. */
    private static final long REFRESH_RETRY_DELAY_MS = 60_000L;

    private static final String SK_ANT_PREFIX = "sk-ant-si-";

    /**
     * Format a millisecond duration as a human-readable string (e.g. "5m 30s").
     * Translated from formatDuration() in jwtUtils.ts
     */
    public static String formatDuration(long ms) {
        if (ms < 60_000) return (ms / 1000) + "s";
        long m = ms / 60_000;
        long s = Math.round((ms % 60_000) / 1000.0);
        return s > 0 ? m + "m " + s + "s" : m + "m";
    }

    /**
     * Decode a JWT's payload segment without verifying the signature.
     * Strips the {@code sk-ant-si-} session-ingress prefix if present.
     * Returns the parsed JSON payload as a Map, or {@code null} if the
     * token is malformed or the payload is not valid JSON.
     *
     * Translated from decodeJwtPayload() in jwtUtils.ts
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodeJwtPayload(String token) {
        if (token == null || token.isEmpty()) return null;
        String jwt = token.startsWith(SK_ANT_PREFIX) ? token.substring(SK_ANT_PREFIX.length()) : token;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3 || parts[1].isEmpty()) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            String json = new String(decoded, StandardCharsets.UTF_8);
            return OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decode the {@code exp} (expiry) claim from a JWT without verifying the signature.
     *
     * @return The {@code exp} value in Unix seconds, or {@code null} if unparseable.
     * Translated from decodeJwtExpiry() in jwtUtils.ts
     */
    public static Long decodeJwtExpiry(String token) {
        Map<String, Object> payload = decodeJwtPayload(token);
        if (payload == null) return null;
        Object exp = payload.get("exp");
        if (exp instanceof Number) {
            return ((Number) exp).longValue();
        }
        return null;
    }

    // ─── Token refresh scheduler ──────────────────────────────────────────────

    /**
     * Result of {@link #createTokenRefreshScheduler}.
     * Translated from the return type of createTokenRefreshScheduler() in jwtUtils.ts
     */
    public interface TokenRefreshScheduler {
        void schedule(String sessionId, String token);
        void scheduleFromExpiresIn(String sessionId, long expiresInSeconds);
        void cancel(String sessionId);
        void cancelAll();
    }

    /**
     * Creates a token refresh scheduler that proactively refreshes session tokens
     * before they expire. Used by both the standalone bridge and the REPL bridge.
     *
     * When a token is about to expire, the scheduler calls {@code onRefresh} with the
     * session ID and the bridge's OAuth access token. The caller is responsible
     * for delivering the token to the appropriate transport.
     *
     * Translated from createTokenRefreshScheduler() in jwtUtils.ts
     *
     * @param executor         Scheduled executor for timer management
     * @param getAccessToken   Supplier returning the current OAuth access token (may be null)
     * @param onRefresh        Callback invoked with (sessionId, oauthToken) on refresh
     * @param label            Label used in debug log messages
     * @param refreshBufferMs  How long before expiry to fire refresh (default 5 min)
     */
    public static TokenRefreshScheduler createTokenRefreshScheduler(
            ScheduledExecutorService executor,
            Supplier<String> getAccessToken,
            BiConsumer<String, String> onRefresh,
            String label,
            long refreshBufferMs) {

        // timers: sessionId → ScheduledFuture
        ConcurrentHashMap<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Integer> failureCounts = new ConcurrentHashMap<>();
        // Generation counter per session — incremented by schedule() and cancel()
        ConcurrentHashMap<String, AtomicInteger> generations = new ConcurrentHashMap<>();

        return new TokenRefreshScheduler() {

            private int nextGeneration(String sessionId) {
                return generations.computeIfAbsent(sessionId, k -> new AtomicInteger(0))
                        .incrementAndGet();
            }

            @Override
            public void schedule(String sessionId, String token) {
                Long expiry = decodeJwtExpiry(token);
                if (expiry == null) {
                    // Token is not a decodable JWT — preserve any existing timer
                    log.debug("[{}:token] Could not decode JWT expiry for sessionId={}, token prefix={}…, keeping existing timer",
                            label, sessionId, token.length() >= 15 ? token.substring(0, 15) : token);
                    return;
                }

                // Clear any existing refresh timer
                ScheduledFuture<?> existing = timers.remove(sessionId);
                if (existing != null) existing.cancel(false);

                int gen = nextGeneration(sessionId);

                String expiryIso = java.time.Instant.ofEpochSecond(expiry).toString();
                long delayMs = expiry * 1000 - System.currentTimeMillis() - refreshBufferMs;

                if (delayMs <= 0) {
                    log.debug("[{}:token] Token for sessionId={} expires={} (past or within buffer), refreshing immediately",
                            label, sessionId, expiryIso);
                    executor.execute(() -> doRefresh(sessionId, gen));
                    return;
                }

                log.debug("[{}:token] Scheduled token refresh for sessionId={} in {} (expires={}, buffer={}s)",
                        label, sessionId, formatDuration(delayMs), expiryIso, refreshBufferMs / 1000);

                ScheduledFuture<?> future = executor.schedule(
                        () -> doRefresh(sessionId, gen), delayMs, TimeUnit.MILLISECONDS);
                timers.put(sessionId, future);
            }

            @Override
            public void scheduleFromExpiresIn(String sessionId, long expiresInSeconds) {
                ScheduledFuture<?> existing = timers.remove(sessionId);
                if (existing != null) existing.cancel(false);

                int gen = nextGeneration(sessionId);
                // Clamp to 30s floor to avoid tight-looping
                long delayMs = Math.max(expiresInSeconds * 1000 - refreshBufferMs, 30_000);

                log.debug("[{}:token] Scheduled token refresh for sessionId={} in {} (expires_in={}s, buffer={}s)",
                        label, sessionId, formatDuration(delayMs), expiresInSeconds, refreshBufferMs / 1000);

                ScheduledFuture<?> future = executor.schedule(
                        () -> doRefresh(sessionId, gen), delayMs, TimeUnit.MILLISECONDS);
                timers.put(sessionId, future);
            }

            private void doRefresh(String sessionId, int gen) {
                String oauthToken = null;
                try {
                    oauthToken = getAccessToken.get();
                } catch (Exception err) {
                    log.debug("[{}:token] getAccessToken threw for sessionId={}: {}",
                            label, sessionId, err.getMessage());
                }

                // Check if stale (session cancelled or rescheduled while we were running)
                AtomicInteger genCounter = generations.get(sessionId);
                if (genCounter == null || genCounter.get() != gen) {
                    log.debug("[{}:token] doRefresh for sessionId={} stale (gen {} vs {}), skipping",
                            label, sessionId, gen, genCounter != null ? genCounter.get() : "null");
                    return;
                }

                if (oauthToken == null) {
                    int failures = failureCounts.merge(sessionId, 1, Integer::sum);
                    log.debug("[{}:token] No OAuth token available for refresh, sessionId={} (failure {}/{})",
                            label, sessionId, failures, MAX_REFRESH_FAILURES);
                    if (failures < MAX_REFRESH_FAILURES) {
                        ScheduledFuture<?> retryFuture = executor.schedule(
                                () -> doRefresh(sessionId, gen), REFRESH_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
                        timers.put(sessionId, retryFuture);
                    }
                    return;
                }

                // Reset failure counter on successful token retrieval
                failureCounts.remove(sessionId);

                log.debug("[{}:token] Refreshing token for sessionId={}: new token prefix={}…",
                        label, sessionId, oauthToken.length() >= 15 ? oauthToken.substring(0, 15) : oauthToken);
                onRefresh.accept(sessionId, oauthToken);

                // Schedule a follow-up refresh so long-running sessions stay authenticated
                ScheduledFuture<?> followUpFuture = executor.schedule(
                        () -> doRefresh(sessionId, gen), FALLBACK_REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                timers.put(sessionId, followUpFuture);
                log.debug("[{}:token] Scheduled follow-up refresh for sessionId={} in {}",
                        label, sessionId, formatDuration(FALLBACK_REFRESH_INTERVAL_MS));
            }

            @Override
            public void cancel(String sessionId) {
                nextGeneration(sessionId);
                ScheduledFuture<?> future = timers.remove(sessionId);
                if (future != null) future.cancel(false);
                failureCounts.remove(sessionId);
            }

            @Override
            public void cancelAll() {
                // Bump all generations so in-flight doRefresh calls are invalidated
                generations.values().forEach(AtomicInteger::incrementAndGet);
                timers.values().forEach(f -> f.cancel(false));
                timers.clear();
                failureCounts.clear();
            }
        };
    }

    /**
     * Convenience overload using the default refresh buffer of 5 minutes.
     */
    public static TokenRefreshScheduler createTokenRefreshScheduler(
            ScheduledExecutorService executor,
            Supplier<String> getAccessToken,
            BiConsumer<String, String> onRefresh,
            String label) {
        return createTokenRefreshScheduler(executor, getAccessToken, onRefresh, label, TOKEN_REFRESH_BUFFER_MS);
    }

    private JwtUtils() {}
}
