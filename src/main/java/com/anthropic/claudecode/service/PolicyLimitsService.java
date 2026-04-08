package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PolicyLimitsTypes;
import com.anthropic.claudecode.model.PolicyLimitsTypes.PolicyLimitsFetchResult;
import com.anthropic.claudecode.model.PolicyLimitsTypes.PolicyLimitsResponse;
import com.anthropic.claudecode.model.PolicyLimitsTypes.PolicyRestriction;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Policy Limits Service.
 * Translated from src/services/policyLimits/index.ts
 *
 * Fetches organization-level policy restrictions from the API and uses them
 * to disable CLI features. Follows the same patterns as remote managed settings
 * (fail open, ETag caching, background polling, retry logic).
 *
 * Eligibility:
 * - Console users (API key): All eligible
 * - OAuth users (Claude.ai): Only Team and Enterprise/C4E subscribers are eligible
 * - API fails open (non-blocking) - if fetch fails, continues without restrictions
 * - API returns empty restrictions for users without policy limits
 */
@Slf4j
@Service
public class PolicyLimitsService {



    private static final String CACHE_FILENAME = "policy-limits.json";
    private static final long FETCH_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final long POLLING_INTERVAL_MS = 60 * 60 * 1000L; // 1 hour
    private static final long LOADING_PROMISE_TIMEOUT_MS = 30_000;

    /**
     * Policies that default to denied when essential-traffic-only mode is active
     * and the policy cache is unavailable.
     */
    private static final Set<String> ESSENTIAL_TRAFFIC_DENY_ON_MISS =
        Set.of("allow_product_feedback");

    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    /** Session-level cache for policy restrictions. */
    private volatile Map<String, PolicyRestriction> sessionCache;

    /** Background polling scheduler. */
    private ScheduledFuture<?> pollingFuture;
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "policy-limits-poll");
            t.setDaemon(true);
            return t;
        });

    /** Promise resolved when the initial policy limits load completes. */
    private final AtomicReference<CompletableFuture<Void>> loadingFuture = new AtomicReference<>();

    @Autowired
    public PolicyLimitsService(OAuthService oauthService, ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    // ─── Eligibility ─────────────────────────────────────────────

    /**
     * Check if the current user is eligible for policy limits.
     *
     * IMPORTANT: This function must NOT call getSettings() to avoid circular
     * dependencies during settings loading.
     * Translated from isPolicyLimitsEligible() in index.ts
     */
    public boolean isPolicyLimitsEligible() {
        // API key users are always eligible
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return true;
        }

        // OAuth users: only Team and Enterprise subscribers are eligible
        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        if (tokens == null || tokens.getAccessToken() == null) return false;
        if (!tokens.hasClaudeAiInferenceScope()) return false;

        String subType = tokens.getSubscriptionType();
        return "enterprise".equals(subType) || "team".equals(subType);
    }

    // ─── Initialization ────────────────────────────────────────

    /**
     * Initialize the loading promise for policy limits.
     * Only creates the promise if the user is eligible for policy limits.
     * Includes a timeout to prevent deadlocks if loadPolicyLimits() is never called.
     * Translated from initializePolicyLimitsLoadingPromise() in index.ts
     */
    public void initializePolicyLimitsLoadingPromise() {
        if (loadingFuture.get() != null) return;

        if (!isPolicyLimitsEligible()) return;

        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!loadingFuture.compareAndSet(null, future)) return;

        // Timeout to prevent deadlocks
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                log.debug("Policy limits: Loading promise timed out, resolving anyway");
                future.complete(null);
            }
        }, LOADING_PROMISE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Wait for the initial policy limits loading to complete.
     * Returns immediately if user is not eligible or loading has already completed.
     * Translated from waitForPolicyLimitsToLoad() in index.ts
     */
    public CompletableFuture<Void> waitForPolicyLimitsToLoad() {
        CompletableFuture<Void> future = loadingFuture.get();
        if (future != null) return future;
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Load policy limits during CLI initialization.
     * Fails open — if fetch fails, continues without restrictions.
     * Also starts background polling to pick up changes mid-session.
     * Translated from loadPolicyLimits() in index.ts
     */
    public CompletableFuture<Void> loadPolicyLimits() {
        if (isPolicyLimitsEligible() && loadingFuture.get() == null) {
            loadingFuture.compareAndSet(null, new CompletableFuture<>());
        }

        return CompletableFuture.runAsync(() -> {
            try {
                fetchAndLoadPolicyLimits();
                if (isPolicyLimitsEligible()) {
                    startBackgroundPolling();
                }
            } finally {
                CompletableFuture<Void> future = loadingFuture.get();
                if (future != null && !future.isDone()) {
                    future.complete(null);
                }
            }
        });
    }

    /**
     * Refresh policy limits asynchronously (for auth state changes).
     * Used when login occurs.
     * Translated from refreshPolicyLimits() in index.ts
     */
    public CompletableFuture<Void> refreshPolicyLimits() {
        return clearPolicyLimitsCache().thenRunAsync(() -> {
            if (!isPolicyLimitsEligible()) return;
            fetchAndLoadPolicyLimits();
            log.debug("Policy limits: Refreshed after auth change");
        });
    }

    /**
     * Clear all policy limits (session, persistent, and stop polling).
     * Translated from clearPolicyLimitsCache() in index.ts
     */
    public CompletableFuture<Void> clearPolicyLimitsCache() {
        stopBackgroundPolling();
        sessionCache = null;
        loadingFuture.set(null);

        return CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(Paths.get(getCachePath()));
            } catch (Exception e) {
                log.debug("Policy limits: Could not delete cache file: {}", e.getMessage());
            }
        });
    }

    // ─── Policy check ─────────────────────────────────────────

    /**
     * Check if a specific policy is allowed.
     * Returns true if the policy is unknown, unavailable, or explicitly allowed (fail open).
     * Exception: policies in ESSENTIAL_TRAFFIC_DENY_ON_MISS fail closed when
     * essential-traffic-only mode is active and the cache is unavailable.
     * Translated from isPolicyAllowed() in index.ts
     */
    public boolean isPolicyAllowed(String policy) {
        Map<String, PolicyRestriction> restrictions = getRestrictionsFromCache();
        if (restrictions == null) {
            if (isEssentialTrafficOnly() && ESSENTIAL_TRAFFIC_DENY_ON_MISS.contains(policy)) {
                return false;
            }
            return true; // fail open
        }
        PolicyRestriction restriction = restrictions.get(policy);
        if (restriction == null) {
            return true; // unknown policy = allowed
        }
        return restriction.isAllowed();
    }

    // ─── Background polling ────────────────────────────────────

    /**
     * Start background polling for policy limits.
     * Translated from startBackgroundPolling() in index.ts
     */
    public synchronized void startBackgroundPolling() {
        if (pollingFuture != null && !pollingFuture.isDone()) return;
        if (!isPolicyLimitsEligible()) return;

        pollingFuture = scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    pollPolicyLimits();
                } catch (Exception e) {
                    log.debug("Policy limits: Background poll error: {}", e.getMessage());
                }
            },
            POLLING_INTERVAL_MS,
            POLLING_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stop background polling for policy limits.
     * Translated from stopBackgroundPolling() in index.ts
     */
    public synchronized void stopBackgroundPolling() {
        if (pollingFuture != null) {
            pollingFuture.cancel(false);
            pollingFuture = null;
        }
    }

    /**
     * Reset policy limits state for testing purposes.
     * Translated from _resetPolicyLimitsForTesting() in index.ts
     */
    public void resetForTesting() {
        stopBackgroundPolling();
        sessionCache = null;
        loadingFuture.set(null);
    }

    // ─── Internal helpers ─────────────────────────────────────

    private void fetchAndLoadPolicyLimits() {
        if (!isPolicyLimitsEligible()) return;

        Map<String, PolicyRestriction> cachedRestrictions = loadCachedRestrictions();
        String cachedChecksum = cachedRestrictions != null ? computeChecksum(cachedRestrictions) : null;

        PolicyLimitsFetchResult result = fetchWithRetry(cachedChecksum);

        if (!result.isSuccess()) {
            if (cachedRestrictions != null) {
                log.debug("Policy limits: Using stale cache after fetch failure");
                sessionCache = cachedRestrictions;
            }
            return;
        }

        // 304 Not Modified
        if (result.getRestrictions() == null && cachedRestrictions != null) {
            log.debug("Policy limits: Cache still valid (304 Not Modified)");
            sessionCache = cachedRestrictions;
            return;
        }

        Map<String, PolicyRestriction> newRestrictions =
            result.getRestrictions() != null ? result.getRestrictions() : Collections.emptyMap();

        if (!newRestrictions.isEmpty()) {
            sessionCache = newRestrictions;
            saveCachedRestrictions(newRestrictions);
            log.debug("Policy limits: Applied new restrictions successfully");
        } else {
            // Empty restrictions (404 response) - delete cached file if it exists
            sessionCache = newRestrictions;
            try {
                Files.deleteIfExists(Paths.get(getCachePath()));
                log.debug("Policy limits: Deleted cached file (404 response)");
            } catch (Exception e) {
                log.debug("Policy limits: Failed to delete cached file: {}", e.getMessage());
            }
        }
    }

    private void pollPolicyLimits() {
        if (!isPolicyLimitsEligible()) return;

        String previousJson = sessionCache != null ? toJson(sessionCache) : null;
        fetchAndLoadPolicyLimits();
        String newJson = sessionCache != null ? toJson(sessionCache) : null;

        if (!Objects.equals(newJson, previousJson)) {
            log.debug("Policy limits: Changed during background poll");
        }
    }

    private PolicyLimitsFetchResult fetchWithRetry(String cachedChecksum) {
        PolicyLimitsFetchResult lastResult = null;
        for (int attempt = 1; attempt <= DEFAULT_MAX_RETRIES + 1; attempt++) {
            lastResult = fetchPolicyLimits(cachedChecksum);
            if (lastResult.isSuccess()) return lastResult;
            if (lastResult.isSkipRetry()) return lastResult;
            if (attempt > DEFAULT_MAX_RETRIES) return lastResult;

            long delayMs = getRetryDelayMs(attempt);
            log.debug("Policy limits: Retry {}/{} after {}ms", attempt, DEFAULT_MAX_RETRIES, delayMs);
            sleep(delayMs);
        }
        return lastResult;
    }

    private PolicyLimitsFetchResult fetchPolicyLimits(String cachedChecksum) {
        try {
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(getPolicyLimitsEndpoint()))
                .timeout(Duration.ofMillis(FETCH_TIMEOUT_MS))
                .header("User-Agent", "ClaudeCode-Java")
                .GET();

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("x-api-key", apiKey);
            } else if (tokens != null && tokens.getAccessToken() != null) {
                builder.header("Authorization", "Bearer " + tokens.getAccessToken());
                builder.header("anthropic-beta", "oauth-2025-04-20");
            } else {
                return PolicyLimitsFetchResult.failure("Authentication required for policy limits", true);
            }

            if (cachedChecksum != null) {
                builder.header("If-None-Match", "\"" + cachedChecksum + "\"");
            }

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(FETCH_TIMEOUT_MS))
                .build();

            HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 304 -> {
                    log.debug("Policy limits: Using cached restrictions (304)");
                    yield PolicyLimitsFetchResult.notModified(cachedChecksum);
                }
                case 404 -> {
                    log.debug("Policy limits: No restrictions found (404)");
                    yield PolicyLimitsFetchResult.success(Collections.emptyMap(), null);
                }
                case 200 -> {
                    PolicyLimitsResponse parsed = objectMapper.readValue(
                        response.body(), PolicyLimitsResponse.class);
                    log.debug("Policy limits: Fetched successfully");
                    yield PolicyLimitsFetchResult.success(parsed.getRestrictions(), null);
                }
                case 401, 403 -> PolicyLimitsFetchResult.failure(
                    "Not authorized for policy limits", true);
                default -> PolicyLimitsFetchResult.failure(
                    "HTTP " + response.statusCode(), false);
            };

        } catch (java.net.http.HttpTimeoutException e) {
            return PolicyLimitsFetchResult.failure("Policy limits request timeout", false);
        } catch (java.io.IOException e) {
            return PolicyLimitsFetchResult.failure("Cannot connect to server", false);
        } catch (Exception e) {
            return PolicyLimitsFetchResult.failure(e.getMessage(), false);
        }
    }

    private Map<String, PolicyRestriction> getRestrictionsFromCache() {
        if (!isPolicyLimitsEligible()) return null;
        if (sessionCache != null) return sessionCache;

        Map<String, PolicyRestriction> cached = loadCachedRestrictions();
        if (cached != null) {
            sessionCache = cached;
            return cached;
        }
        return null;
    }

    private Map<String, PolicyRestriction> loadCachedRestrictions() {
        try {
            String content = Files.readString(Paths.get(getCachePath()));
            PolicyLimitsResponse parsed = objectMapper.readValue(content, PolicyLimitsResponse.class);
            return parsed.getRestrictions();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveCachedRestrictions(Map<String, PolicyRestriction> restrictions) {
        try {
            String path = getCachePath();
            PolicyLimitsResponse data = new PolicyLimitsResponse(restrictions);
            Files.writeString(Paths.get(path), objectMapper.writeValueAsString(data));
            log.debug("Policy limits: Saved to {}", path);
        } catch (Exception e) {
            log.debug("Policy limits: Failed to save: {}", e.getMessage());
        }
    }

    /**
     * Recursively sort object keys and compute a sha256 checksum for HTTP caching.
     * Translated from computeChecksum() in index.ts
     */
    private String computeChecksum(Map<String, PolicyRestriction> restrictions) {
        try {
            // Sort keys for consistent hashing
            Map<String, PolicyRestriction> sorted = new TreeMap<>(restrictions);
            String normalized = objectMapper.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) hex.append(String.format("%02x", b));
            return "sha256:" + hex;
        } catch (Exception e) {
            return "";
        }
    }

    private String getCachePath() {
        String configHome = System.getenv("CLAUDE_CONFIG_HOME");
        if (configHome == null) configHome = System.getProperty("user.home") + "/.claude";
        return configHome + "/" + CACHE_FILENAME;
    }

    private String getPolicyLimitsEndpoint() {
        String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
        if (baseUrl == null) baseUrl = "https://api.anthropic.com";
        return baseUrl + "/api/claude_code/policy_limits";
    }

    private boolean isEssentialTrafficOnly() {
        // In full implementation, checks privacy level settings
        return "true".equalsIgnoreCase(System.getenv("CLAUDE_ESSENTIAL_TRAFFIC_ONLY"));
    }

    private long getRetryDelayMs(int attempt) {
        return (long) Math.min(1000 * Math.pow(2, attempt - 1), 30_000);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }

    /** Alias for initializePolicyLimitsLoadingPromise. */
    public void initializePolicyLimits() {
        initializePolicyLimitsLoadingPromise();
    }
}
