package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted device token source for bridge (remote-control) sessions.
 *
 * <p>Bridge sessions have SecurityTier=ELEVATED on the server (CCR v2).
 * The server gates ConnectBridgeWorker on its own flag
 * ({@code sessions_elevated_auth_enforcement} in Anthropic Main); this CLI-side
 * flag controls whether the CLI sends {@code X-Trusted-Device-Token} at all.
 * Two flags so rollout can be staged: flip CLI-side first (headers start flowing,
 * server still no-ops), then flip server-side.</p>
 *
 * <p>Enrollment (POST /auth/trusted_devices) is gated server-side by
 * {@code account_session.created_at < 10min}, so it must happen during /login.
 * Token is persistent (90d rolling expiry) and stored in secure storage.</p>
 *
 * Translated from src/bridge/trustedDevice.ts
 */
@Slf4j
@Service
public class BridgeTrustedDeviceService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeTrustedDeviceService.class);


    private static final String TRUSTED_DEVICE_GATE = "tengu_sessions_elevated_auth_enforcement";

    /**
     * Base API URL for enrollment calls. Mirrors {@code getOauthConfig().BASE_API_URL}.
     */
    @Value("${bridge.base-api-url:https://api.claude.ai}")
    private String baseApiUrl;

    private final WebClient webClient;
    private final SecureStorageService secureStorageService;
    private final GrowthBookService growthBookService;

    // Memoized token — read() spawns an OS subprocess (~40ms).
    // Cache is cleared after enrollment and on logout (clearAuthRelatedCaches).
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>(null);

    private record CachedToken(String value) {}

    public BridgeTrustedDeviceService(
            WebClient.Builder webClientBuilder,
            SecureStorageService secureStorageService,
            GrowthBookService growthBookService) {
        this.webClient = webClientBuilder.build();
        this.secureStorageService = secureStorageService;
        this.growthBookService = growthBookService;
    }

    // =========================================================================
    // Gate check
    // =========================================================================

    /**
     * Whether the trusted-device gate is currently enabled.
     * Translated from {@code isGateEnabled()} in trustedDevice.ts
     */
    private boolean isGateEnabled() {
        return growthBookService.getFeatureValueCachedMayBeStale(TRUSTED_DEVICE_GATE, false);
    }

    // =========================================================================
    // Token read / cache
    // =========================================================================

    /**
     * Read the stored trusted device token (memoized).
     * Env var {@code CLAUDE_TRUSTED_DEVICE_TOKEN} takes precedence for testing/canary.
     * Translated from {@code readStoredToken()} in trustedDevice.ts
     */
    private String readStoredToken() {
        CachedToken cached = cachedToken.get();
        if (cached != null) {
            return cached.value();
        }

        String envToken = System.getenv("CLAUDE_TRUSTED_DEVICE_TOKEN");
        if (envToken != null && !envToken.isEmpty()) {
            cachedToken.set(new CachedToken(envToken));
            return envToken;
        }

        String stored = secureStorageService.readTrustedDeviceToken();
        cachedToken.set(new CachedToken(stored)); // may cache null
        return stored;
    }

    /**
     * Get the trusted device token for the current session.
     * Returns null when the gate is off or no token is stored.
     * Translated from {@code getTrustedDeviceToken()} in trustedDevice.ts
     */
    public String getTrustedDeviceToken() {
        if (!isGateEnabled()) {
            return null;
        }
        return readStoredToken();
    }

    /**
     * Clear the in-memory token cache.
     * Translated from {@code clearTrustedDeviceTokenCache()} in trustedDevice.ts
     */
    public void clearTrustedDeviceTokenCache() {
        cachedToken.set(null);
    }

    // =========================================================================
    // Token clear
    // =========================================================================

    /**
     * Clear the stored trusted device token from secure storage and the memo cache.
     *
     * <p>Called before {@link #enrollTrustedDevice(String)} during /login so a
     * stale token from the previous account isn't sent as
     * {@code X-Trusted-Device-Token} while enrollment is in-flight.</p>
     *
     * Translated from {@code clearTrustedDeviceToken()} in trustedDevice.ts
     */
    public void clearTrustedDeviceToken() {
        if (!isGateEnabled()) {
            return;
        }
        try {
            secureStorageService.clearTrustedDeviceToken();
        } catch (Exception e) {
            // Best-effort — don't block login if storage is inaccessible
            log.debug("[trusted-device] Failed to clear token from storage: {}", e.getMessage());
        }
        cachedToken.set(null);
    }

    // =========================================================================
    // Enrollment
    // =========================================================================

    /**
     * Enrollment response shape from POST /auth/trusted_devices.
     */
    public record EnrollmentResponse(String device_token, String device_id) {}

    /**
     * Enroll this device via POST /auth/trusted_devices and persist the token
     * to secure storage. Best-effort — logs and returns on failure so callers
     * (post-login hooks) don't block the login flow.
     *
     * <p>The server gates enrollment on {@code account_session.created_at < 10min},
     * so this must be called immediately after a fresh /login. Calling it later
     * (e.g. lazy enrollment on /bridge 403) will fail with 403 stale_session.</p>
     *
     * @param accessToken valid OAuth access token from the current login
     * Translated from {@code enrollTrustedDevice()} in trustedDevice.ts
     */
    public CompletableFuture<Void> enrollTrustedDevice(String accessToken) {
        return CompletableFuture.runAsync(() -> {
            try {
                // checkGate awaits any in-flight GrowthBook re-init before reading
                // the gate, so we get the post-refresh value.
                boolean gateEnabled = growthBookService.checkGateCachedOrBlocking(TRUSTED_DEVICE_GATE);
                if (!gateEnabled) {
                    log.debug("[trusted-device] Gate {} is off, skipping enrollment", TRUSTED_DEVICE_GATE);
                    return;
                }

                // If CLAUDE_TRUSTED_DEVICE_TOKEN is set, skip enrollment — the env
                // var takes precedence in readStoredToken() so any enrolled token
                // would be shadowed and never used.
                if (System.getenv("CLAUDE_TRUSTED_DEVICE_TOKEN") != null) {
                    log.debug("[trusted-device] CLAUDE_TRUSTED_DEVICE_TOKEN env var is set, "
                            + "skipping enrollment (env var takes precedence)");
                    return;
                }

                if (accessToken == null || accessToken.isEmpty()) {
                    log.debug("[trusted-device] No OAuth token, skipping enrollment");
                    return;
                }

                if (isEssentialTrafficOnly()) {
                    log.debug("[trusted-device] Essential traffic only, skipping enrollment");
                    return;
                }

                String hostname = getHostname();
                String platform = System.getProperty("os.name", "unknown");
                Map<String, String> body = Map.of(
                        "display_name", "Claude Code on " + hostname + " \u00b7 " + platform
                );

                EnrollmentResponse response = webClient.post()
                        .uri(baseApiUrl + "/api/auth/trusted_devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .bodyValue(body)
                        .retrieve()
                        .onStatus(status -> status.is5xxServerError(),
                                resp -> Mono.error(new RuntimeException("Server error: " + resp.statusCode())))
                        .bodyToMono(EnrollmentResponse.class)
                        .block(java.time.Duration.ofSeconds(10));

                if (response == null || response.device_token() == null || response.device_token().isEmpty()) {
                    log.debug("[trusted-device] Enrollment response missing device_token field");
                    return;
                }

                boolean saved = secureStorageService.storeTrustedDeviceToken(response.device_token());
                if (!saved) {
                    log.debug("[trusted-device] Failed to persist token");
                    return;
                }

                // Clear the cache so the new token is picked up immediately
                cachedToken.set(null);
                log.debug("[trusted-device] Enrolled device_id={}", response.device_id() != null ? response.device_id() : "unknown");

            } catch (Exception err) {
                log.debug("[trusted-device] Enrollment error: {}", err.getMessage());
            }
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isEssentialTrafficOnly() {
        // Mirrors isEssentialTrafficOnly() in privacyLevel.ts
        String val = System.getenv("CLAUDE_CODE_ESSENTIAL_TRAFFIC_ONLY");
        return val != null && (val.equalsIgnoreCase("true") || val.equals("1"));
    }

    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // =========================================================================
    // Stub interfaces — implemented elsewhere in the Java project
    // =========================================================================

    /** Secure storage operations for trusted device tokens. */
    public interface SecureStorageService {
        String readTrustedDeviceToken();
        boolean storeTrustedDeviceToken(String token);
        void clearTrustedDeviceToken();
    }

    /** GrowthBook feature-flag evaluation. */
    public interface GrowthBookService {
        boolean getFeatureValueCachedMayBeStale(String key, boolean defaultValue);
        boolean checkGateCachedOrBlocking(String key);
    }
}
