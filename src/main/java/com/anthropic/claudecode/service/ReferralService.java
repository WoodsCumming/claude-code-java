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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Referral service for managing referral programs.
 * Translated from src/services/api/referral.ts
 *
 * Handles referral eligibility checks and reward redemptions.
 * Guest-pass feature is available only to Max-tier claudeai subscribers.
 */
@Slf4j
@Service
public class ReferralService {



    private static final long CACHE_EXPIRATION_MS = 24L * 60 * 60 * 1_000;
    private static final String DEFAULT_CAMPAIGN = "claude_code_guest_pass";

    // Currency symbol map — mirrors CURRENCY_SYMBOLS in referral.ts
    private static final Map<String, String> CURRENCY_SYMBOLS = Map.of(
            "USD", "$",
            "EUR", "€",
            "GBP", "£",
            "BRL", "R$",
            "CAD", "CA$",
            "AUD", "A$",
            "NZD", "NZ$",
            "SGD", "S$"
    );

    // -------------------------------------------------------------------------
    // DTOs — mirror types from services/oauth/types.ts
    // -------------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReferrerRewardInfo {
        @JsonProperty("amount_minor_units")
        private int amountMinorUnits;
        private String currency;

        public int getAmountMinorUnits() { return amountMinorUnits; }
        public void setAmountMinorUnits(int v) { amountMinorUnits = v; }
        public String getCurrency() { return currency; }
        public void setCurrency(String v) { currency = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReferralEligibilityResponse {
        private boolean eligible;
        private String campaign;
        @JsonProperty("referral_code")
        private String referralCode;
        @JsonProperty("remaining_passes")
        private Integer remainingPasses;
        @JsonProperty("referrer_reward")
        private ReferrerRewardInfo referrerReward;

        public boolean isEligible() { return eligible; }
        public void setEligible(boolean v) { eligible = v; }
        public String getCampaign() { return campaign; }
        public void setCampaign(String v) { campaign = v; }
        public String getReferralCode() { return referralCode; }
        public void setReferralCode(String v) { referralCode = v; }
        public Integer getRemainingPasses() { return remainingPasses; }
        public void setRemainingPasses(Integer v) { remainingPasses = v; }
        public ReferrerRewardInfo getReferrerReward() { return referrerReward; }
        public void setReferrerReward(ReferrerRewardInfo v) { referrerReward = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReferralRedemptionsResponse {
        private int total;
        private int pending;
        private int completed;

        public int getTotal() { return total; }
        public void setTotal(int v) { total = v; }
        public int getPending() { return pending; }
        public void setPending(int v) { pending = v; }
        public int getCompleted() { return completed; }
        public void setCompleted(int v) { completed = v; }
    }

    /** Per-org eligibility cache entry (mirrors passesEligibilityCache shape). */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PassesEligibilityCacheEntry {
        private boolean eligible;
        private String campaign;
        private String referralCode;
        private Integer remainingPasses;
        private ReferrerRewardInfo referrerReward;
        private long timestamp;

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
    }

    /** Result of checkCachedPassesEligibility() */
    public record CachedEligibilityResult(boolean eligible, boolean needsRefresh, boolean hasCache) {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Map<String, PassesEligibilityCacheEntry> passesEligibilityCache
            = new ConcurrentHashMap<>();

    /** Tracks in-flight fetch to prevent duplicate API calls — mirrors fetchInProgress. */
    private final AtomicReference<CompletableFuture<ReferralEligibilityResponse>>
            fetchInProgress = new AtomicReference<>(null);

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ReferralService(OAuthService oauthService, ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch referral eligibility from the API.
     * Translated from fetchReferralEligibility() in referral.ts
     */
    public CompletableFuture<ReferralEligibilityResponse> fetchReferralEligibility(
            String campaign) {
        String effectiveCampaign = campaign != null ? campaign : DEFAULT_CAMPAIGN;
        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedRequest prep = prepareApiRequest();
                String url = getBaseApiUrl()
                        + "/api/oauth/organizations/" + prep.orgUUID()
                        + "/referral/eligibility";

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url + "?campaign=" + effectiveCampaign))
                        .header("Authorization", "Bearer " + prep.accessToken())
                        .header("anthropic-beta", "oauth-2023-11-01")
                        .header("x-organization-uuid", prep.orgUUID())
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), ReferralEligibilityResponse.class);

            } catch (Exception e) {
                log.error("fetchReferralEligibility failed: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Fetch referral redemptions.
     * Translated from fetchReferralRedemptions() in referral.ts
     */
    public CompletableFuture<ReferralRedemptionsResponse> fetchReferralRedemptions(
            String campaign) {
        String effectiveCampaign = campaign != null ? campaign : DEFAULT_CAMPAIGN;
        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedRequest prep = prepareApiRequest();
                String url = getBaseApiUrl()
                        + "/api/oauth/organizations/" + prep.orgUUID()
                        + "/referral/redemptions?campaign=" + effectiveCampaign;

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + prep.accessToken())
                        .header("anthropic-beta", "oauth-2023-11-01")
                        .header("x-organization-uuid", prep.orgUUID())
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                return objectMapper.readValue(response.body(), ReferralRedemptionsResponse.class);

            } catch (Exception e) {
                log.error("fetchReferralRedemptions failed: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Check cached passes eligibility from config.
     * Returns current cached state and whether a refresh is needed.
     * Translated from checkCachedPassesEligibility() in referral.ts
     */
    public CachedEligibilityResult checkCachedPassesEligibility() {
        if (!shouldCheckForPasses()) {
            return new CachedEligibilityResult(false, false, false);
        }

        String orgId = getOrgId();
        if (orgId == null) {
            return new CachedEligibilityResult(false, false, false);
        }

        PassesEligibilityCacheEntry entry = passesEligibilityCache.get(orgId);
        if (entry == null) {
            return new CachedEligibilityResult(false, true, false);
        }

        long now = System.currentTimeMillis();
        boolean needsRefresh = now - entry.getTimestamp() > CACHE_EXPIRATION_MS;
        return new CachedEligibilityResult(entry.isEligible(), needsRefresh, true);
    }

    /**
     * Format credit amount for display using currency symbols.
     * Translated from formatCreditAmount() in referral.ts
     */
    public String formatCreditAmount(ReferrerRewardInfo reward) {
        String symbol = CURRENCY_SYMBOLS.getOrDefault(reward.getCurrency(),
                reward.getCurrency() + " ");
        double amount = reward.getAmountMinorUnits() / 100.0;
        String formatted = (amount % 1 == 0)
                ? String.valueOf((int) amount)
                : String.format("%.2f", amount);
        return symbol + formatted;
    }

    /**
     * Get cached referrer reward info from eligibility cache.
     * Translated from getCachedReferrerReward() in referral.ts
     */
    public ReferrerRewardInfo getCachedReferrerReward() {
        String orgId = getOrgId();
        if (orgId == null) return null;
        PassesEligibilityCacheEntry entry = passesEligibilityCache.get(orgId);
        return entry != null ? entry.getReferrerReward() : null;
    }

    /**
     * Get cached remaining passes count.
     * Translated from getCachedRemainingPasses() in referral.ts
     */
    public Integer getCachedRemainingPasses() {
        String orgId = getOrgId();
        if (orgId == null) return null;
        PassesEligibilityCacheEntry entry = passesEligibilityCache.get(orgId);
        return entry != null ? entry.getRemainingPasses() : null;
    }

    /**
     * Fetch passes eligibility and store in cache.
     * Returns the fetched response or null on error.
     * Translated from fetchAndStorePassesEligibility() in referral.ts
     */
    public CompletableFuture<ReferralEligibilityResponse> fetchAndStorePassesEligibility() {
        // Return existing promise if fetch is already in progress
        CompletableFuture<ReferralEligibilityResponse> existing = fetchInProgress.get();
        if (existing != null) {
            log.debug("Passes: Reusing in-flight eligibility fetch");
            return existing;
        }

        String orgId = getOrgId();
        if (orgId == null) return CompletableFuture.completedFuture(null);

        CompletableFuture<ReferralEligibilityResponse> future =
                fetchReferralEligibility(DEFAULT_CAMPAIGN)
                .thenApply(response -> {
                    PassesEligibilityCacheEntry entry = new PassesEligibilityCacheEntry(
                            response.isEligible(),
                            response.getCampaign(),
                            response.getReferralCode(),
                            response.getRemainingPasses(),
                            response.getReferrerReward(),
                            System.currentTimeMillis());
                    passesEligibilityCache.put(orgId, entry);
                    log.debug("Passes eligibility cached for org {}: {}",
                            orgId, response.isEligible());
                    return response;
                })
                .exceptionally(e -> {
                    log.debug("Failed to fetch and cache passes eligibility");
                    log.error("Error: {}", e.getMessage(), e);
                    return null;
                })
                .whenComplete((r, e) -> fetchInProgress.set(null));

        // Only store if CAS succeeds (avoid double-fetch race)
        if (!fetchInProgress.compareAndSet(null, future)) {
            return fetchInProgress.get();
        }
        return future;
    }

    /**
     * Get cached passes eligibility data or fetch if needed.
     * Never blocks on network — returns cached data immediately and fetches
     * in the background if needed.
     * Translated from getCachedOrFetchPassesEligibility() in referral.ts
     */
    public CompletableFuture<ReferralEligibilityResponse> getCachedOrFetchPassesEligibility() {
        if (!shouldCheckForPasses()) return CompletableFuture.completedFuture(null);

        String orgId = getOrgId();
        if (orgId == null) return CompletableFuture.completedFuture(null);

        PassesEligibilityCacheEntry entry = passesEligibilityCache.get(orgId);
        long now = System.currentTimeMillis();

        if (entry == null) {
            log.debug("Passes: No cache, fetching eligibility in background (command unavailable this session)");
            fetchAndStorePassesEligibility(); // fire-and-forget
            return CompletableFuture.completedFuture(null);
        }

        if (now - entry.getTimestamp() > CACHE_EXPIRATION_MS) {
            log.debug("Passes: Cache stale, returning cached data and refreshing in background");
            fetchAndStorePassesEligibility(); // background refresh
            return CompletableFuture.completedFuture(toEligibilityResponse(entry));
        }

        log.debug("Passes: Using fresh cached eligibility data");
        return CompletableFuture.completedFuture(toEligibilityResponse(entry));
    }

    /**
     * Prefetch passes eligibility on startup.
     * Translated from prefetchPassesEligibility() in referral.ts
     */
    public void prefetchPassesEligibility() {
        if (isEssentialTrafficOnly()) return;
        getCachedOrFetchPassesEligibility(); // fire-and-forget
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean shouldCheckForPasses() {
        String orgId = getOrgId();
        if (orgId == null) return false;
        // Mirror: isClaudeAISubscriber() && getSubscriptionType() === 'max'
        try {
            String subType = oauthService.getSubscriptionType();
            return oauthService.isClaudeAISubscriber() && "max".equals(subType);
        } catch (Exception e) {
            return false;
        }
    }

    private ReferralEligibilityResponse toEligibilityResponse(PassesEligibilityCacheEntry entry) {
        ReferralEligibilityResponse r = new ReferralEligibilityResponse();
        r.setEligible(entry.isEligible());
        r.setCampaign(entry.getCampaign());
        r.setReferralCode(entry.getReferralCode());
        r.setRemainingPasses(entry.getRemainingPasses());
        r.setReferrerReward(entry.getReferrerReward());
        return r;
    }

    private record PreparedRequest(String accessToken, String orgUUID) {}

    private PreparedRequest prepareApiRequest() {
        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        if (tokens == null || tokens.getAccessToken() == null) {
            throw new IllegalStateException("No OAuth tokens available");
        }
        String orgUUID = oauthService.getOrganizationUUID().join();
        if (orgUUID == null) {
            throw new IllegalStateException("No organization UUID available");
        }
        return new PreparedRequest(tokens.getAccessToken(), orgUUID);
    }

    private String getOrgId() {
        try {
            return oauthService.getOrganizationUUID().join();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isEssentialTrafficOnly() {
        String val = System.getenv("CLAUDE_ESSENTIAL_TRAFFIC_ONLY");
        return "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    private String getBaseApiUrl() {
        String url = System.getenv("ANTHROPIC_BASE_API_URL");
        return url != null ? url : "https://api.anthropic.com";
    }
}
