package com.anthropic.claudecode.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Usage and rate-limit service.
 * Translated from src/services/api/usage.ts
 *
 * Fetches usage / utilization data from the Claude.ai OAuth usage endpoint.
 * Only applicable for Claude.ai subscribers with the profile scope.
 */
@Slf4j
@Service
public class UsageService {



    private static final String USAGE_PATH = "/api/oauth/usage";
    private static final int TIMEOUT_MS = 5_000;

    // -----------------------------------------------------------------------
    // Domain model  (translated from TypeScript type exports in usage.ts)
    // -----------------------------------------------------------------------

    /** A single rate-limit window with utilization and reset timestamp. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RateLimit {
        /** Percentage from 0 to 100, or null if unknown. */
        @JsonProperty("utilization")
        private Double utilization;

        /** ISO-8601 timestamp when the limit resets, or null. */
        @JsonProperty("resets_at")
        private String resetsAt;

        public Double getUtilization() { return utilization; }
        public void setUtilization(Double v) { utilization = v; }
        public String getResetsAt() { return resetsAt; }
        public void setResetsAt(String v) { resetsAt = v; }
    }

    /** Extra (overage) usage details for subscribers. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ExtraUsage {
        @JsonProperty("is_enabled")
        private boolean enabled;

        @JsonProperty("monthly_limit")
        private Long monthlyLimit;

        @JsonProperty("used_credits")
        private Long usedCredits;

        @JsonProperty("utilization")
        private Double utilization;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public Long getMonthlyLimit() { return monthlyLimit; }
        public void setMonthlyLimit(Long v) { monthlyLimit = v; }
        public Long getUsedCredits() { return usedCredits; }
        public void setUsedCredits(Long v) { usedCredits = v; }
    }

    /** Full utilization snapshot returned by the API. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Utilization {
        @JsonProperty("five_hour")
        private RateLimit fiveHour;

        @JsonProperty("seven_day")
        private RateLimit sevenDay;

        @JsonProperty("seven_day_oauth_apps")
        private RateLimit sevenDayOauthApps;

        @JsonProperty("seven_day_opus")
        private RateLimit sevenDayOpus;

        @JsonProperty("seven_day_sonnet")
        private RateLimit sevenDaySonnet;

        @JsonProperty("extra_usage")
        private ExtraUsage extraUsage;

        public RateLimit getFiveHour() { return fiveHour; }
        public void setFiveHour(RateLimit v) { fiveHour = v; }
        public RateLimit getSevenDay() { return sevenDay; }
        public void setSevenDay(RateLimit v) { sevenDay = v; }
        public RateLimit getSevenDayOauthApps() { return sevenDayOauthApps; }
        public void setSevenDayOauthApps(RateLimit v) { sevenDayOauthApps = v; }
        public RateLimit getSevenDayOpus() { return sevenDayOpus; }
        public void setSevenDayOpus(RateLimit v) { sevenDayOpus = v; }
        public RateLimit getSevenDaySonnet() { return sevenDaySonnet; }
        public void setSevenDaySonnet(RateLimit v) { sevenDaySonnet = v; }
        public ExtraUsage getExtraUsage() { return extraUsage; }
        public void setExtraUsage(ExtraUsage v) { extraUsage = v; }
    

    }

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OAuthService oauthService;

    @Autowired
    public UsageService(OkHttpClient httpClient,
                        ObjectMapper objectMapper,
                        OAuthService oauthService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    // -----------------------------------------------------------------------
    // fetchUtilization
    // -----------------------------------------------------------------------

    /**
     * Fetch current utilization / rate-limit data from the Claude.ai API.
     * Returns an empty Utilization if the user is not a Claude.ai subscriber
     * or if the OAuth token is expired. Returns null on auth error.
     *
     * Translated from fetchUtilization() in usage.ts
     */
    public CompletableFuture<Utilization> fetchUtilization() {
        // Only applicable for Claude.ai subscribers with profile scope
        if (!oauthService.isClaudeAiSubscriber() || !oauthService.hasProfileScope()) {
            return CompletableFuture.completedFuture(new Utilization());
        }

        // Skip API call if token is expired to avoid 401 errors
        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        if (tokens == null || oauthService.isTokenExpired(tokens)) {
            return CompletableFuture.completedFuture(null);
        }

        String baseApiUrl = oauthService.getBaseApiUrl();
        String url = baseApiUrl + USAGE_PATH;

        return CompletableFuture.supplyAsync(() -> {
            Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokens.getAccessToken())
                .header("Content-Type", "application/json")
                .header("User-Agent", oauthService.getUserAgent())
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.code() == 200) {
                    String body = response.body() != null ? response.body().string() : null;
                    if (body != null && !body.isBlank()) {
                        return objectMapper.readValue(body, Utilization.class);
                    }
                    return new Utilization();
                }

                if (response.code() == 401) {
                    log.debug("[usage] Auth error fetching utilization (401)");
                    return null;
                }

                log.debug("[usage] Unexpected status {} fetching utilization", response.code());
                return new Utilization();

            } catch (IOException e) {
                log.debug("[usage] Error fetching utilization: {}", e.getMessage());
                return new Utilization();
            }
        });
    }
}
