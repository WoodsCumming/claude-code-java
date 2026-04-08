package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * GrowthBook feature flag service.
 * Translated from src/services/analytics/growthbook.ts
 *
 * Manages feature flags and A/B testing via GrowthBook remote evaluation.
 * Feature values are fetched from the GrowthBook API and cached in memory
 * (and optionally on disk via GlobalConfigService).  Callers that can tolerate
 * a potentially-stale value should use {@link #getFeatureValue} directly;
 * callers that need fresh values must first {@code await} {@link #initialize}.
 */
@Slf4j
@Service
public class GrowthBookService implements BridgeTrustedDeviceService.GrowthBookService {



    // -------------------------------------------------------------------------
    // In-memory state — mirrors the module-scope variables in growthbook.ts
    // -------------------------------------------------------------------------

    /** Pre-evaluated feature values from the last successful remote-eval payload. */
    private final Map<String, Object> remoteEvalFeatureValues = new ConcurrentHashMap<>();

    /** Experiment data stored per feature key for exposure logging. */
    private final Map<String, StoredExperimentData> experimentDataByFeature = new ConcurrentHashMap<>();

    /** Features accessed before init that need exposure logging when init completes. */
    private final Set<String> pendingExposures = ConcurrentHashMap.newKeySet();

    /** Features whose experiment exposure has already been logged this session (dedup). */
    private final Set<String> loggedExposures = ConcurrentHashMap.newKeySet();

    /** Listeners fired whenever the feature-value map is refreshed. */
    private final List<Runnable> refreshListeners = new CopyOnWriteArrayList<>();

    /** Whether the client was created with auth headers available. */
    private final AtomicBoolean clientCreatedWithAuth = new AtomicBoolean(false);

    /** Config overrides set at runtime (ant-only). */
    private volatile Map<String, Object> configOverrides = null;

    /** Env-var overrides parsed once at startup (ant-only). */
    private volatile Map<String, Object> envOverrides = null;
    private volatile boolean envOverridesParsed = false;

    /** Promise tracking the current (re-)initialization. */
    private volatile CompletableFuture<Void> initFuture = null;

    private final GlobalConfigService globalConfigService;

    @Autowired
    public GrowthBookService(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
    }

    // -------------------------------------------------------------------------
    // Refresh listener API
    // -------------------------------------------------------------------------

    /**
     * Register a callback to fire when GrowthBook feature values refresh.
     * Returns a Runnable that unregisters the listener.
     * Translated from onGrowthBookRefresh() in growthbook.ts
     */
    public Runnable onGrowthBookRefresh(Runnable listener) {
        refreshListeners.add(listener);
        // Catch-up: if features already loaded, fire on next tick
        if (!remoteEvalFeatureValues.isEmpty()) {
            CompletableFuture.runAsync(listener);
        }
        return () -> refreshListeners.remove(listener);
    }

    // -------------------------------------------------------------------------
    // Feature value accessors
    // -------------------------------------------------------------------------

    /**
     * Get a feature value.  Returns the remote-eval value when available,
     * otherwise falls back to the disk cache, config override, env override,
     * and finally {@code defaultValue}.
     * Translated from getFeatureValue_CACHED_MAY_BE_STALE() in growthbook.ts
     */
    @SuppressWarnings("unchecked")
    public <T> T getFeatureValue(String featureKey, T defaultValue) {
        // 1. Env-var override (ant-only, deterministic for eval harnesses)
        Map<String, Object> envOvr = getEnvOverrides();
        if (envOvr != null && envOvr.containsKey(featureKey)) {
            return castOrDefault(envOvr.get(featureKey), defaultValue);
        }

        // 2. Runtime config override (ant-only)
        Map<String, Object> cfgOvr = getConfigOverrides();
        if (cfgOvr != null && cfgOvr.containsKey(featureKey)) {
            return castOrDefault(cfgOvr.get(featureKey), defaultValue);
        }

        // 3. In-memory remote-eval cache
        if (remoteEvalFeatureValues.containsKey(featureKey)) {
            logExposureForFeature(featureKey);
            return castOrDefault(remoteEvalFeatureValues.get(featureKey), defaultValue);
        }

        // 4. Disk cache (populated from previous session)
        Map<String, Object> diskCache = globalConfigService.getCachedGrowthBookFeatures();
        if (diskCache != null && diskCache.containsKey(featureKey)) {
            pendingExposures.add(featureKey);
            return castOrDefault(diskCache.get(featureKey), defaultValue);
        }

        return defaultValue;
    }

    /**
     * Check a boolean feature gate.
     * Translated from checkStatsigFeatureGate_CACHED_MAY_BE_STALE() in growthbook.ts
     */
    public boolean checkFeatureGate(String gateKey) {
        return getFeatureValue(gateKey, false);
    }

    /** Alias: getFeatureValueCachedMayBeStale (used by callers translated from TS). */
    public <T> T getFeatureValueCachedMayBeStale(String featureKey, T defaultValue) {
        return getFeatureValue(featureKey, defaultValue);
    }

    /** Implements BridgeTrustedDeviceService.GrowthBookService interface (boolean overload). */
    @Override
    public boolean getFeatureValueCachedMayBeStale(String featureKey, boolean defaultValue) {
        return Boolean.TRUE.equals(getFeatureValue(featureKey, defaultValue));
    }

    /** Alias: checkFeatureGateCachedMayBeStale. */
    public boolean checkFeatureGateCachedMayBeStale(String gateKey) {
        return checkFeatureGate(gateKey);
    }

    /** Alias: isFeatureEnabled. */
    public boolean isFeatureEnabled(String featureKey) {
        return Boolean.TRUE.equals(getFeatureValue(featureKey, false));
    }

    /** Alias: getFeatureValueCached (same as getFeatureValueCachedMayBeStale). */
    public <T> T getFeatureValueCached(String featureKey, T defaultValue) {
        return getFeatureValue(featureKey, defaultValue);
    }

    /**
     * Returns a snapshot of all known GrowthBook features and their current values.
     * Translated from getAllGrowthBookFeatures() in growthbook.ts
     */
    public Map<String, Object> getAllGrowthBookFeatures() {
        if (!remoteEvalFeatureValues.isEmpty()) {
            return Map.copyOf(remoteEvalFeatureValues);
        }
        Map<String, Object> disk = globalConfigService.getCachedGrowthBookFeatures();
        return disk != null ? Map.copyOf(disk) : Map.of();
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Initialize (or re-initialize) the GrowthBook client.
     * Safe to call multiple times; subsequent calls are no-ops unless
     * explicitly reset via {@link #resetGrowthBook()}.
     * Translated from initializeGrowthBook() / getGrowthBookClient() in growthbook.ts
     *
     * @param userId    stable device/user identifier
     * @param sessionId current session identifier
     */
    public synchronized CompletableFuture<Void> initialize(String userId, String sessionId) {
        if (initFuture != null && !initFuture.isCompletedExceptionally()) {
            return initFuture;
        }

        String clientKey = System.getenv("GROWTHBOOK_CLIENT_KEY");
        if (clientKey == null || clientKey.isBlank()) {
            log.debug("GrowthBook: no client key configured, skipping init");
            initFuture = CompletableFuture.completedFuture(null);
            return initFuture;
        }

        log.debug("GrowthBook: initializing for userId={}", userId);
        initFuture = fetchRemoteEvalPayload(clientKey, userId, sessionId)
            .thenAccept(features -> {
                if (features == null || features.isEmpty()) return;

                remoteEvalFeatureValues.putAll(features);
                // Flush pending exposures now that we have real data
                for (String feature : pendingExposures) {
                    logExposureForFeature(feature);
                }
                pendingExposures.clear();
                // Persist to disk
                globalConfigService.saveCachedGrowthBookFeatures(Map.copyOf(remoteEvalFeatureValues));
                // Notify subscribers
                fireRefreshListeners();
            })
            .exceptionally(e -> {
                log.warn("GrowthBook init failed: {}", e.getMessage());
                return null;
            });

        return initFuture;
    }

    /**
     * Reset all GrowthBook state (called on auth change / logout).
     * Translated from resetGrowthBook() in growthbook.ts
     */
    public synchronized void resetGrowthBook() {
        remoteEvalFeatureValues.clear();
        experimentDataByFeature.clear();
        pendingExposures.clear();
        loggedExposures.clear();
        configOverrides = null;
        clientCreatedWithAuth.set(false);
        initFuture = null;
        log.debug("GrowthBook: state reset");
    }

    /**
     * Trigger re-initialization after an auth change so fresh auth headers are used.
     * Translated from refreshGrowthBookAfterAuthChange() in growthbook.ts
     */
    public CompletableFuture<Void> refreshAfterAuthChange(String userId, String sessionId) {
        resetGrowthBook();
        return initialize(userId, sessionId);
    }

    // -------------------------------------------------------------------------
    // Config override API (ant-only)
    // -------------------------------------------------------------------------

    /**
     * Return runtime config overrides (ant-only).
     * Translated from getGrowthBookConfigOverrides() in growthbook.ts
     */
    /**
     * Get a dynamic configuration object by key, returning defaultValue if not found.
     * Delegates to getFeatureValue() which handles env-var and config overrides.
     */
    public <T> T getDynamicConfig(String configKey, T defaultValue) {
        return getFeatureValue(configKey, defaultValue);
    }

    public Map<String, Object> getGrowthBookConfigOverrides() {
        Map<String, Object> ovr = getConfigOverrides();
        return ovr != null ? Map.copyOf(ovr) : Map.of();
    }

    /**
     * Set or clear a single config override.  Fires refresh listeners.
     * Translated from setGrowthBookConfigOverride() in growthbook.ts
     */
    public void setGrowthBookConfigOverride(String feature, Object value) {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return;
        Map<String, Object> current = configOverrides != null
            ? new HashMap<>(configOverrides) : new HashMap<>();
        if (value == null) {
            current.remove(feature);
        } else {
            current.put(feature, value);
        }
        configOverrides = current.isEmpty() ? null : current;
        fireRefreshListeners();
    }

    /**
     * Clear all runtime config overrides.
     * Translated from clearGrowthBookConfigOverrides() in growthbook.ts
     */
    public void clearGrowthBookConfigOverrides() {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return;
        configOverrides = null;
        fireRefreshListeners();
    }

    /**
     * Return whether {@code feature} has an env-var override.
     * Translated from hasGrowthBookEnvOverride() in growthbook.ts
     */
    public boolean hasGrowthBookEnvOverride(String feature) {
        Map<String, Object> ovr = getEnvOverrides();
        return ovr != null && ovr.containsKey(feature);
    }

    /**
     * Return the hostname of ANTHROPIC_BASE_URL when it differs from api.anthropic.com.
     * Translated from getApiBaseUrlHost() in growthbook.ts
     */
    public Optional<String> getApiBaseUrlHost() {
        String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
        if (baseUrl == null) return Optional.empty();
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            String host = uri.getHost();
            if ("api.anthropic.com".equals(host)) return Optional.empty();
            return Optional.ofNullable(host);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void logExposureForFeature(String feature) {
        if (loggedExposures.contains(feature)) return;
        StoredExperimentData exp = experimentDataByFeature.get(feature);
        if (exp != null) {
            loggedExposures.add(feature);
            log.debug("GrowthBook exposure: feature={}, experimentId={}, variationId={}",
                feature, exp.experimentId(), exp.variationId());
        }
    }

    private void fireRefreshListeners() {
        for (Runnable listener : refreshListeners) {
            CompletableFuture.runAsync(() -> {
                try {
                    listener.run();
                } catch (Exception e) {
                    log.error("GrowthBook refresh listener error: {}", e.getMessage());
                }
            });
        }
    }

    private Map<String, Object> getEnvOverrides() {
        if (!envOverridesParsed) {
            envOverridesParsed = true;
            if ("ant".equals(System.getenv("USER_TYPE"))) {
                String raw = System.getenv("CLAUDE_INTERNAL_FC_OVERRIDES");
                if (raw != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(raw, Map.class);
                        envOverrides = parsed;
                        log.debug("GrowthBook: Using env var overrides for {} features",
                            envOverrides.size());
                    } catch (Exception e) {
                        log.error("GrowthBook: Failed to parse CLAUDE_INTERNAL_FC_OVERRIDES: {}", e.getMessage());
                    }
                }
            }
        }
        return envOverrides;
    }

    private Map<String, Object> getConfigOverrides() {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return null;
        if (configOverrides != null) return configOverrides;
        return globalConfigService.getGrowthBookOverrides();
    }

    /**
     * Fetch the remote-eval payload from the GrowthBook API.
     * Returns null (rather than throwing) on any failure so callers can
     * fall back gracefully to the disk cache.
     */
    private CompletableFuture<Map<String, Object>> fetchRemoteEvalPayload(
            String clientKey, String userId, String sessionId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String baseUrl = "ant".equals(System.getenv("USER_TYPE"))
                    ? Optional.ofNullable(System.getenv("CLAUDE_CODE_GB_BASE_URL"))
                              .orElse("https://api.anthropic.com/")
                    : "https://api.anthropic.com/";

                String url = baseUrl + "api/features/" + clientKey
                    + "?userId=" + userId + "&sessionId=" + sessionId;

                var client = java.net.http.HttpClient.newHttpClient();
                var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

                var response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) return null;

                @SuppressWarnings("unchecked")
                Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.body(), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> features = (Map<String, Object>) body.get("features");
                if (features == null || features.isEmpty()) return null;

                // Extract the resolved "value" (or "defaultValue") per feature
                Map<String, Object> resolved = new HashMap<>();
                for (var entry : features.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> featureDef = (Map<String, Object>) entry.getValue();
                    Object value = featureDef.containsKey("value")
                        ? featureDef.get("value")
                        : featureDef.get("defaultValue");
                    if (value != null) {
                        resolved.put(entry.getKey(), value);
                    }
                }
                return resolved;

            } catch (Exception e) {
                log.debug("GrowthBook remote eval failed: {}", e.getMessage());
                return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T castOrDefault(Object value, T defaultValue) {
        if (value == null) return defaultValue;
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Stored experiment data for later exposure logging.
     * Mirrors StoredExperimentData in growthbook.ts
     */
    public record StoredExperimentData(
        String experimentId,
        int variationId,
        boolean inExperiment,
        String hashAttribute,
        String hashValue
    ) {}

    // -------------------------------------------------------------------------
    // BridgeTrustedDeviceService.GrowthBookService implementation
    // -------------------------------------------------------------------------

    @Override
    public boolean checkGateCachedOrBlocking(String key) {
        return getFeatureValueCachedMayBeStale(key, false);
    }
}
