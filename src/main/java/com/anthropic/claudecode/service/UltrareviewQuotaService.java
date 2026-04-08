package com.anthropic.claudecode.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.CompletableFuture;

/**
 * Ultrareview quota service.
 * Translated from src/services/api/ultrareviewQuota.ts
 *
 * Peeks the ultrareview quota for display and nudge decisions.
 * Consume happens server-side at session creation.
 * Returns null when not a subscriber or the endpoint errors.
 */
@Slf4j
@Service
public class UltrareviewQuotaService {



    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UltrareviewQuotaResponse {
        @JsonProperty("reviews_used")
        private int reviewsUsed;
        @JsonProperty("reviews_limit")
        private int reviewsLimit;
        @JsonProperty("reviews_remaining")
        private int reviewsRemaining;
        @JsonProperty("is_overage")
        private boolean isOverage;

        public int getReviewsUsed() { return reviewsUsed; }
        public void setReviewsUsed(int v) { reviewsUsed = v; }
        public int getReviewsLimit() { return reviewsLimit; }
        public void setReviewsLimit(int v) { reviewsLimit = v; }
        public int getReviewsRemaining() { return reviewsRemaining; }
        public void setReviewsRemaining(int v) { reviewsRemaining = v; }
        public boolean isIsOverage() { return isOverage; }
        public void setIsOverage(boolean v) { isOverage = v; }
    }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    @Autowired
    public UltrareviewQuotaService(OAuthService oauthService, ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch the ultrareview quota for display and nudge decisions.
     * Returns null when not a ClaudeAI subscriber or the endpoint errors.
     * Translated from fetchUltrareviewQuota() in ultrareviewQuota.ts
     */
    public CompletableFuture<UltrareviewQuotaResponse> fetchUltrareviewQuota() {
        // Only Claude.ai subscribers have ultrareview access
        if (!oauthService.isClaudeAISubscriber()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
                if (tokens == null || tokens.getAccessToken() == null) return null;

                String orgUUID = oauthService.getOrganizationUUID().join();

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(getBaseApiUrl() + "/v1/ultrareview/quota"))
                        .header("Authorization", "Bearer " + tokens.getAccessToken())
                        .header("anthropic-beta", "oauth-2023-11-01")
                        .GET()
                        .timeout(Duration.ofSeconds(5));

                if (orgUUID != null) {
                    builder.header("x-organization-uuid", orgUUID);
                }

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readValue(response.body(), UltrareviewQuotaResponse.class);
                }

                log.debug("fetchUltrareviewQuota: unexpected status {}", response.statusCode());
                return null;

            } catch (Exception e) {
                log.debug("fetchUltrareviewQuota failed: {}", e.getMessage());
                return null;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String getBaseApiUrl() {
        String url = System.getenv("ANTHROPIC_BASE_API_URL");
        return url != null ? url : "https://api.anthropic.com";
    }
}
