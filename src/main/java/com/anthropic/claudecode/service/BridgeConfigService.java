package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Bridge configuration service.
 *
 * Merges two TypeScript sources:
 * <ul>
 *   <li>{@code src/bridge/bridgeConfig.ts} — auth/URL resolution, ant-only dev overrides</li>
 *   <li>{@code src/bridge/envLessBridgeConfig.ts} — timing / retry config for the env-less (v2) bridge path</li>
 * </ul>
 *
 * Consolidates bridge auth/URL resolution, including ant-only dev overrides
 * (CLAUDE_BRIDGE_OAUTH_TOKEN, CLAUDE_BRIDGE_BASE_URL) that were previously
 * copy-pasted across many files.
 *
 * Two layers: *Override() returns the ant-only env var (or null);
 * the non-Override versions fall through to the real OAuth store/config.
 */
@Slf4j
@Service
public class BridgeConfigService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeConfigService.class);


    /**
     * Production API base URL — injected from application properties so it
     * can be overridden in tests. Mirrors getOauthConfig().BASE_API_URL.
     */
    @Value("${bridge.base-api-url:https://api.claude.ai}")
    private String productionBaseApiUrl;

    /**
     * Ant-only dev override: CLAUDE_BRIDGE_OAUTH_TOKEN, else null.
     * Translated from getBridgeTokenOverride() in bridgeConfig.ts
     */
    public String getBridgeTokenOverride() {
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            String token = System.getenv("CLAUDE_BRIDGE_OAUTH_TOKEN");
            return (token != null && !token.isEmpty()) ? token : null;
        }
        return null;
    }

    /**
     * Ant-only dev override: CLAUDE_BRIDGE_BASE_URL, else null.
     * Translated from getBridgeBaseUrlOverride() in bridgeConfig.ts
     */
    public String getBridgeBaseUrlOverride() {
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            String url = System.getenv("CLAUDE_BRIDGE_BASE_URL");
            return (url != null && !url.isEmpty()) ? url : null;
        }
        return null;
    }

    /**
     * Access token for bridge API calls: dev override first, then the OAuth
     * keychain (CLAUDE_CODE_OAUTH_TOKEN). Null means "not logged in".
     *
     * Translated from getBridgeAccessToken() in bridgeConfig.ts
     */
    public String getBridgeAccessToken() {
        String override = getBridgeTokenOverride();
        if (override != null) return override;
        // Fall through to the real OAuth token stored in the environment
        // (getClaudeAIOAuthTokens()?.accessToken in TypeScript).
        return System.getenv("CLAUDE_CODE_OAUTH_TOKEN");
    }

    /**
     * Base URL for bridge API calls: dev override first, then the production
     * OAuth config. Always returns a non-null URL.
     *
     * Translated from getBridgeBaseUrl() in bridgeConfig.ts
     */
    public String getBridgeBaseUrl() {
        String override = getBridgeBaseUrlOverride();
        if (override != null) return override;
        return productionBaseApiUrl;
    }

    // =========================================================================
    // Env-less (v2) bridge timing config
    // Translated from src/bridge/envLessBridgeConfig.ts
    // =========================================================================

    /**
     * Configuration record for the env-less (v2) bridge path.
     *
     * <p>All timing / retry parameters are tuned so that a bad server-side
     * value causes a safe fallback to {@link #DEFAULT_ENV_LESS_BRIDGE_CONFIG}
     * rather than a partial trust (same defense-in-depth as pollConfig.ts).</p>
     *
     * Translated from {@code EnvLessBridgeConfig} in envLessBridgeConfig.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EnvLessBridgeConfig {
        /** withRetry — init-phase max attempts. */
        private int initRetryMaxAttempts = 3;
        /** withRetry — init-phase base delay in ms. */
        private int initRetryBaseDelayMs = 500;
        /** withRetry — init-phase jitter fraction. */
        private double initRetryJitterFraction = 0.25;
        /** withRetry — init-phase max delay in ms. */
        private int initRetryMaxDelayMs = 4000;
        /** Axios-equivalent HTTP timeout for POST /sessions, POST /bridge, POST /archive. */
        private int httpTimeoutMs = 10_000;
        /** BoundedUUIDSet ring size (echo + re-delivery dedup). */
        private int uuidDedupBufferSize = 2000;
        /** CCRClient worker heartbeat cadence (ms). Server TTL is 60 s — 20 s gives 3× margin. */
        private int heartbeatIntervalMs = 20_000;
        /** ±fraction of interval — per-beat jitter to spread fleet load. */
        private double heartbeatJitterFraction = 0.1;
        /** Fire proactive JWT refresh this long before expires_in (ms). */
        private int tokenRefreshBufferMs = 300_000;
        /** Archive POST timeout in teardown() (ms). Distinct from httpTimeoutMs. */
        private int teardownArchiveTimeoutMs = 1500;
        /** Deadline for onConnect after transport.connect() (ms). */
        private int connectTimeoutMs = 15_000;
        /** Semver floor for the env-less bridge path. */
        private String minVersion = "0.0.0";
        /** When true, nudge users to upgrade their claude.ai app. */
        private boolean shouldShowAppUpgradeMessage = false;

        public int getInitRetryMaxAttempts() { return initRetryMaxAttempts; }
        public void setInitRetryMaxAttempts(int v) { initRetryMaxAttempts = v; }
        public int getInitRetryBaseDelayMs() { return initRetryBaseDelayMs; }
        public void setInitRetryBaseDelayMs(int v) { initRetryBaseDelayMs = v; }
        public double getInitRetryJitterFraction() { return initRetryJitterFraction; }
        public void setInitRetryJitterFraction(double v) { initRetryJitterFraction = v; }
        public int getInitRetryMaxDelayMs() { return initRetryMaxDelayMs; }
        public void setInitRetryMaxDelayMs(int v) { initRetryMaxDelayMs = v; }
        public int getHttpTimeoutMs() { return httpTimeoutMs; }
        public void setHttpTimeoutMs(int v) { httpTimeoutMs = v; }
        public int getUuidDedupBufferSize() { return uuidDedupBufferSize; }
        public void setUuidDedupBufferSize(int v) { uuidDedupBufferSize = v; }
        public int getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
        public void setHeartbeatIntervalMs(int v) { heartbeatIntervalMs = v; }
        public double getHeartbeatJitterFraction() { return heartbeatJitterFraction; }
        public void setHeartbeatJitterFraction(double v) { heartbeatJitterFraction = v; }
        public int getTokenRefreshBufferMs() { return tokenRefreshBufferMs; }
        public void setTokenRefreshBufferMs(int v) { tokenRefreshBufferMs = v; }
        public int getTeardownArchiveTimeoutMs() { return teardownArchiveTimeoutMs; }
        public void setTeardownArchiveTimeoutMs(int v) { teardownArchiveTimeoutMs = v; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int v) { connectTimeoutMs = v; }
        public String getMinVersion() { return minVersion; }
        public void setMinVersion(String v) { minVersion = v; }
        public boolean isShouldShowAppUpgradeMessage() { return shouldShowAppUpgradeMessage; }
        public void setShouldShowAppUpgradeMessage(boolean v) { shouldShowAppUpgradeMessage = v; }
    }

    /**
     * Default timing config. Mirrors {@code DEFAULT_ENV_LESS_BRIDGE_CONFIG}
     * in envLessBridgeConfig.ts.
     */
    public static final EnvLessBridgeConfig DEFAULT_ENV_LESS_BRIDGE_CONFIG =
            new EnvLessBridgeConfig();

    private Supplier<EnvLessBridgeConfig> growthBookConfigSupplier;

    /**
     * Inject a GrowthBook config supplier for testing or live feature-flag
     * delivery. When not set, {@link #getEnvLessBridgeConfig()} returns the
     * default config.
     */
    public void setGrowthBookConfigSupplier(Supplier<EnvLessBridgeConfig> supplier) {
        this.growthBookConfigSupplier = supplier;
    }

    /**
     * Fetch the env-less bridge timing config from GrowthBook. Read once per
     * initEnvLessBridgeCore call — config is fixed for the lifetime of a bridge
     * session.
     *
     * <p>If the GrowthBook value is absent or invalid, falls back to
     * {@link #DEFAULT_ENV_LESS_BRIDGE_CONFIG}.</p>
     *
     * Translated from {@code getEnvLessBridgeConfig()} in envLessBridgeConfig.ts
     */
    public CompletableFuture<EnvLessBridgeConfig> getEnvLessBridgeConfig() {
        return CompletableFuture.supplyAsync(() -> {
            if (growthBookConfigSupplier != null) {
                try {
                    EnvLessBridgeConfig cfg = growthBookConfigSupplier.get();
                    return cfg != null ? cfg : DEFAULT_ENV_LESS_BRIDGE_CONFIG;
                } catch (Exception e) {
                    log.debug("[bridge:config] Failed to read GrowthBook config, using defaults: {}", e.getMessage());
                }
            }
            return DEFAULT_ENV_LESS_BRIDGE_CONFIG;
        });
    }

    /**
     * Returns an error message if the current CLI version is below the minimum
     * required for the env-less (v2) bridge path, or null if the version is fine.
     *
     * Translated from {@code checkEnvLessBridgeMinVersion()} in envLessBridgeConfig.ts
     *
     * @param currentVersion the running CLI version string
     */
    public CompletableFuture<String> checkEnvLessBridgeMinVersion(String currentVersion) {
        return getEnvLessBridgeConfig().thenApply(cfg -> {
            String minVersion = cfg.getMinVersion();
            if (minVersion != null && !minVersion.isEmpty() && !minVersion.equals("0.0.0")
                    && SemverUtils.lt(currentVersion, minVersion)) {
                return "Your version of Claude Code (" + currentVersion + ") is too old for Remote Control.\n"
                        + "Version " + minVersion + " or higher is required. Run `claude update` to update.";
            }
            return null;
        });
    }

    /**
     * Whether to nudge users toward upgrading their claude.ai app when a
     * Remote Control session starts.
     *
     * Translated from {@code shouldShowAppUpgradeMessage()} in envLessBridgeConfig.ts
     *
     * @param envLessBridgeEnabled whether the env-less bridge path is currently active
     */
    public CompletableFuture<Boolean> shouldShowAppUpgradeMessage(boolean envLessBridgeEnabled) {
        if (!envLessBridgeEnabled) return CompletableFuture.completedFuture(false);
        return getEnvLessBridgeConfig().thenApply(EnvLessBridgeConfig::isShouldShowAppUpgradeMessage);
    }

    // =========================================================================
    // SemverUtils stub — real impl lives in util/SemverUtils.java
    // =========================================================================

    /** Version comparison utilities (provided by the existing SemverUtils class). */
    private static class SemverUtils {
        static boolean lt(String a, String b) {
            // Delegate to the project-wide semver utility; inline here avoids a
            // circular dependency at the service layer.
            try {
                return com.anthropic.claudecode.util.SemverUtils.lt(a, b);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
