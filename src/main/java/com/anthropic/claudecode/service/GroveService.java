package com.anthropic.claudecode.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Grove service for privacy settings and notifications.
 * Translated from src/services/api/grove.ts
 *
 * Grove is Anthropic's mechanism for notifying users about Terms of Service
 * and Privacy Policy updates.  All API calls go through the OAuth API.
 */
@Slf4j
@Service
public class GroveService {



    /** Cache duration: 24 hours. */
    private static final long GROVE_CACHE_EXPIRATION_MS = 24L * 60 * 60 * 1_000;

    /**
     * Result type that distinguishes between API failure and success.
     * Mirrors the ApiResult<T> union in grove.ts.
     */
    public sealed interface ApiResult<T> permits ApiResult.Success, ApiResult.Failure {
        record Success<T>(T data) implements ApiResult<T> {}
        record Failure<T>() implements ApiResult<T> {}
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AccountSettings {
        private Boolean groveEnabled;
        private String groveNoticeViewedAt;

        public boolean isGroveEnabled() { return groveEnabled; }
        public void setGroveEnabled(Boolean v) { groveEnabled = v; }
        public String getGroveNoticeViewedAt() { return groveNoticeViewedAt; }
        public void setGroveNoticeViewedAt(String v) { groveNoticeViewedAt = v; }
    
        public Boolean getGroveEnabled() { return groveEnabled; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GroveConfig {
        private boolean groveEnabled;
        private boolean domainExcluded;
        private boolean noticeIsGracePeriod;
        private Integer noticeReminderFrequency;

        public boolean isDomainExcluded() { return domainExcluded; }
        public void setDomainExcluded(boolean v) { domainExcluded = v; }
        public boolean isNoticeIsGracePeriod() { return noticeIsGracePeriod; }
        public void setNoticeIsGracePeriod(boolean v) { noticeIsGracePeriod = v; }
        public Integer getNoticeReminderFrequency() { return noticeReminderFrequency; }
        public void setNoticeReminderFrequency(Integer v) { noticeReminderFrequency = v; }
    
        public boolean isGroveEnabled() { return groveEnabled; }
    }

    // -------------------------------------------------------------------------
    // Memoized caches (session-scoped)
    // -------------------------------------------------------------------------

    private final AtomicReference<ApiResult<AccountSettings>> settingsCache = new AtomicReference<>();
    private final AtomicReference<ApiResult<GroveConfig>> configCache = new AtomicReference<>();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final OAuthService oauthService;
    private final GlobalConfigService globalConfigService;
    private final ObjectMapper objectMapper;

    @Autowired
    public GroveService(OAuthService oauthService,
                        GlobalConfigService globalConfigService,
                        ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.globalConfigService = globalConfigService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Get the current Grove settings for the user account.
     * Memoized for the session; cache is invalidated in updateGroveSettings() and
     * markGroveNoticeViewed().
     * Translated from getGroveSettings() in grove.ts
     */
    public CompletableFuture<ApiResult<AccountSettings>> getGroveSettings() {
        ApiResult<AccountSettings> cached = settingsCache.get();
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = getBearerToken();
                if (token == null) return new ApiResult.Failure<>();

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getBaseApiUrl() + "/api/oauth/account/settings"))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", getUserAgent())
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    AccountSettings settings = objectMapper.readValue(
                        response.body(), AccountSettings.class);
                    ApiResult<AccountSettings> result = new ApiResult.Success<>(settings);
                    settingsCache.set(result);
                    return result;
                }
                return new ApiResult.Failure<>();

            } catch (Exception e) {
                log.error("Failed to fetch Grove settings: {}", e.getMessage());
                // Don't cache failures — transient network issues would lock the user out
                return new ApiResult.Failure<>();
            }
        });
    }

    /**
     * Mark that the Grove notice has been viewed by the user.
     * Translated from markGroveNoticeViewed() in grove.ts
     */
    public CompletableFuture<Void> markGroveNoticeViewed() {
        return CompletableFuture.runAsync(() -> {
            try {
                String token = getBearerToken();
                if (token == null) return;

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getBaseApiUrl() + "/api/oauth/account/grove_notice_viewed"))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", getUserAgent())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .timeout(Duration.ofSeconds(10))
                    .build();

                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                // Invalidate settings cache so re-renders see the updated viewed_at
                settingsCache.set(null);

            } catch (Exception e) {
                log.error("Failed to mark Grove notice viewed: {}", e.getMessage());
            }
        });
    }

    /**
     * Update Grove settings (grove_enabled toggle).
     * Translated from updateGroveSettings() in grove.ts
     */
    public CompletableFuture<Void> updateGroveSettings(boolean groveEnabled) {
        return CompletableFuture.runAsync(() -> {
            try {
                String token = getBearerToken();
                if (token == null) return;

                String body = objectMapper.writeValueAsString(
                    java.util.Map.of("grove_enabled", groveEnabled));

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getBaseApiUrl() + "/api/oauth/account/settings"))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", getUserAgent())
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

                client.send(request, HttpResponse.BodyHandlers.ofString());
                // Invalidate memoized settings so post-toggle reads pick up the new value
                settingsCache.set(null);

            } catch (Exception e) {
                log.error("Failed to update Grove settings: {}", e.getMessage());
            }
        });
    }

    /**
     * Get Grove Statsig configuration from the API.
     * Memoized for the session.
     * Translated from getGroveNoticeConfig() in grove.ts
     */
    public CompletableFuture<ApiResult<GroveConfig>> getGroveNoticeConfig() {
        ApiResult<GroveConfig> cached = configCache.get();
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = getBearerToken();
                if (token == null) return new ApiResult.Failure<>();

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getBaseApiUrl() + "/api/claude_code_grove"))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", getUserAgent())
                    .GET()
                    .timeout(Duration.ofSeconds(3)) // Short timeout — skip Grove dialog if slow
                    .build();

                HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    GroveConfig config = objectMapper.readValue(
                        response.body(), GroveConfig.class);
                    ApiResult<GroveConfig> result = new ApiResult.Success<>(config);
                    configCache.set(result);
                    return result;
                }
                return new ApiResult.Failure<>();

            } catch (Exception e) {
                log.debug("Failed to fetch Grove notice config: {}", e.getMessage());
                return new ApiResult.Failure<>();
            }
        });
    }

    /**
     * Check if user is qualified for Grove (non-blocking, cache-first).
     * Returns false immediately when no fresh cache is available and triggers
     * a background refresh.
     * Translated from isQualifiedForGrove() in grove.ts
     */
    public CompletableFuture<Boolean> isQualifiedForGrove(String accountId) {
        if (accountId == null) {
            return CompletableFuture.completedFuture(false);
        }

        var globalConfig = globalConfigService.getGlobalConfig();
        var cachedEntry = globalConfig.getGroveCacheEntry(accountId);
        long now = System.currentTimeMillis();

        if (cachedEntry == null) {
            log.debug("Grove: No cache, fetching config in background (dialog skipped this session)");
            fetchAndStoreGroveConfig(accountId);
            return CompletableFuture.completedFuture(false);
        }

        if (now - cachedEntry.getTimestamp() > GROVE_CACHE_EXPIRATION_MS) {
            log.debug("Grove: Cache stale, returning cached data and refreshing in background");
            fetchAndStoreGroveConfig(accountId);
            return CompletableFuture.completedFuture(cachedEntry.isGroveEnabled());
        }

        log.debug("Grove: Using fresh cached config");
        return CompletableFuture.completedFuture(cachedEntry.isGroveEnabled());
    }

    /**
     * Determine whether the Grove dialog should be shown.
     * Translated from calculateShouldShowGrove() in grove.ts
     */
    public boolean calculateShouldShowGrove(
            ApiResult<AccountSettings> settingsResult,
            ApiResult<GroveConfig> configResult,
            boolean showIfAlreadyViewed) {

        if (settingsResult instanceof ApiResult.Failure<?>) return false;
        if (configResult instanceof ApiResult.Failure<?>) return false;

        AccountSettings settings = ((ApiResult.Success<AccountSettings>) settingsResult).data();
        GroveConfig config = ((ApiResult.Success<GroveConfig>) configResult).data();

        // User has already made a choice
        if (settings.getGroveEnabled() != null) return false;

        if (showIfAlreadyViewed) return true;
        if (!config.isNoticeIsGracePeriod()) return true;

        Integer reminderFrequency = config.getNoticeReminderFrequency();
        String viewedAt = settings.getGroveNoticeViewedAt();
        if (reminderFrequency != null && viewedAt != null) {
            long daysSinceViewed = Duration.between(
                Instant.parse(viewedAt), Instant.now()).toDays();
            return daysSinceViewed >= reminderFrequency;
        }
        return viewedAt == null;
    }

    /**
     * Check Grove for non-interactive sessions (-p flag).
     * Shows a notice or exits depending on whether the grace period is still active.
     * Translated from checkGroveForNonInteractive() in grove.ts
     */
    public CompletableFuture<Void> checkGroveForNonInteractive() {
        return getGroveSettings()
            .thenCombine(getGroveNoticeConfig(), (settingsResult, configResult) -> {
                boolean shouldShow = calculateShouldShowGrove(settingsResult, configResult, false);
                if (!shouldShow) return null;

                GroveConfig config = configResult instanceof ApiResult.Success<GroveConfig> s
                        ? s.data() : null;

                if (config == null || config.isNoticeIsGracePeriod()) {
                    // Grace period still active — informational message only
                    System.err.println(
                        "\nAn update to our Consumer Terms and Privacy Policy will take effect on " +
                        "October 8, 2025. Run `claude` to review the updated terms.\n");
                    markGroveNoticeViewed();
                } else {
                    // Grace period ended — block execution
                    System.err.println(
                        "\n[ACTION REQUIRED] An update to our Consumer Terms and Privacy Policy has " +
                        "taken effect on October 8, 2025. You must run `claude` to review the updated terms.\n");
                    System.exit(1);
                }
                return null;
            });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void fetchAndStoreGroveConfig(String accountId) {
        getGroveNoticeConfig().thenAccept(result -> {
            if (result instanceof ApiResult.Success<GroveConfig> success) {
                globalConfigService.storeGroveCacheEntry(
                    accountId, success.data().isGroveEnabled(), System.currentTimeMillis());
            }
        });
    }

    private String getBearerToken() {
        try {
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
            return tokens != null ? tokens.getAccessToken() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getBaseApiUrl() {
        String url = System.getenv("ANTHROPIC_BASE_API_URL");
        return url != null ? url : "https://api.anthropic.com";
    }

    private String getUserAgent() {
        return "ClaudeCode-Java/1.0";
    }
}
