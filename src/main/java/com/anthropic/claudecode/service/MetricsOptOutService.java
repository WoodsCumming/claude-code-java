package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PrivacyUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Metrics opt-out service.
 * Translated from src/services/api/metricsOptOut.ts
 *
 * Manages user metrics/analytics opt-out settings.
 */
@Slf4j
@Service
public class MetricsOptOutService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MetricsOptOutService.class);


    private static final long CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    private volatile Boolean cachedMetricsEnabled;
    private volatile long cacheTime;

    @Autowired
    public MetricsOptOutService(OAuthService oauthService, ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    /**
     * Check if metrics are enabled.
     * Translated from isMetricsEnabled() in metricsOptOut.ts
     */
    public CompletableFuture<Boolean> isMetricsEnabled() {
        if (PrivacyUtils.isEssentialTrafficOnly()) {
            return CompletableFuture.completedFuture(false);
        }

        // Check cache
        if (cachedMetricsEnabled != null
            && (System.currentTimeMillis() - cacheTime) < CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cachedMetricsEnabled);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
                if (tokens == null) return true; // Default to enabled

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/api/metrics/opt-out"))
                    .header("Authorization", "Bearer " + tokens.getAccessToken())
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    java.util.Map<String, Object> data = objectMapper.readValue(response.body(), java.util.Map.class);
                    boolean enabled = Boolean.TRUE.equals(data.get("metrics_logging_enabled"));
                    cachedMetricsEnabled = enabled;
                    cacheTime = System.currentTimeMillis();
                    return enabled;
                }

                return true; // Default to enabled on error

            } catch (Exception e) {
                log.debug("Could not check metrics status: {}", e.getMessage());
                return true;
            }
        });
    }
}
