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

/**
 * Overage credit grant service.
 * Translated from src/services/api/overageCreditGrant.ts
 *
 * Fetches and caches overage credit grant eligibility per organisation.
 * The backend resolves tier-specific amounts and role-based claim permission,
 * so this service just reads the response without replicating that logic.
 */
@Slf4j
@Service
public class OverageCreditGrantService {



    private static final long CACHE_TTL_MS = 60 * 60 * 1_000L; // 1 hour

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OverageCreditGrantInfo {
        private boolean available;
        private boolean eligible;
        private boolean granted;
        @JsonProperty("amount_minor_units")
        private Integer amountMinorUnits;
        private String currency;

        public boolean isAvailable() { return available; }
        public void setAvailable(boolean v) { available = v; }
        public boolean isEligible() { return eligible; }
        public void setEligible(boolean v) { eligible = v; }
        public boolean isGranted() { return granted; }
        public void setGranted(boolean v) { granted = v; }
        public Integer getAmountMinorUnits() { return amountMinorUnits; }
        public void setAmountMinorUnits(Integer v) { amountMinorUnits = v; }
        public String getCurrency() { return currency; }
        public void setCurrency(String v) { currency = v; }
    }

    /** Per-org cache entry — mirrors CachedGrantEntry in overageCreditGrant.ts */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CachedGrantEntry {
        private OverageCreditGrantInfo info;
        private long timestamp;

        public OverageCreditGrantInfo getInfo() { return info; }
        public void setInfo(OverageCreditGrantInfo v) { info = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
    
    }

    // -------------------------------------------------------------------------
    // Dependencies & state
    // -------------------------------------------------------------------------

    private final OAuthService oauthService;
    private final GlobalConfigService globalConfigService;
    private final ObjectMapper objectMapper;

    /** In-memory per-org cache (mirrors GlobalConfig.overageCreditGrantCache). */
    private final Map<String, CachedGrantEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public OverageCreditGrantService(OAuthService oauthService,
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
     * Get cached grant info. Returns null if no cache entry or cache is stale.
     * Callers should render nothing (not block) when this returns null —
     * refreshOverageCreditGrantCache() fires lazily to populate it.
     * Translated from getCachedOverageCreditGrant() in overageCreditGrant.ts
     */
    public OverageCreditGrantInfo getCachedOverageCreditGrant() {
        String orgId = getOrgId();
        if (orgId == null) return null;
        CachedGrantEntry entry = cache.get(orgId);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.getTimestamp() > CACHE_TTL_MS) return null;
        return entry.getInfo();
    }

    /**
     * Drop the current org's cached entry so the next read refetches.
     * Leaves other orgs' entries intact.
     * Translated from invalidateOverageCreditGrantCache() in overageCreditGrant.ts
     */
    public void invalidateOverageCreditGrantCache() {
        String orgId = getOrgId();
        if (orgId == null) return;
        cache.remove(orgId);
    }

    /**
     * Fetch and cache grant info.
     * Fire-and-forget; call when an upsell surface is about to render and
     * the cache is empty.
     * Translated from refreshOverageCreditGrantCache() in overageCreditGrant.ts
     */
    public CompletableFuture<Void> refreshOverageCreditGrantCache() {
        if (isEssentialTrafficOnly()) return CompletableFuture.completedFuture(null);
        String orgId = getOrgId();
        if (orgId == null) return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(() -> {
            OverageCreditGrantInfo info = fetchOverageCreditGrant(orgId);
            if (info == null) return;

            // Skip rewriting if data unchanged AND timestamp is still fresh
            CachedGrantEntry prev = cache.get(orgId);
            if (prev != null
                    && dataUnchanged(prev.getInfo(), info)
                    && System.currentTimeMillis() - prev.getTimestamp() <= CACHE_TTL_MS) {
                return;
            }

            OverageCreditGrantInfo toStore = (prev != null && dataUnchanged(prev.getInfo(), info))
                    ? prev.getInfo() : info;
            cache.put(orgId, new CachedGrantEntry(toStore, System.currentTimeMillis()));
        });
    }

    /**
     * Format the grant amount for display.
     * Returns null if amount isn't available or currency is unknown.
     * Translated from formatGrantAmount() in overageCreditGrant.ts
     */
    public String formatGrantAmount(OverageCreditGrantInfo info) {
        if (info.getAmountMinorUnits() == null || info.getCurrency() == null) return null;
        if ("USD".equalsIgnoreCase(info.getCurrency())) {
            double dollars = info.getAmountMinorUnits() / 100.0;
            // Integer dollars → "$N"; fractional → "$N.NN"
            if (dollars == Math.floor(dollars)) {
                return "$" + (int) dollars;
            }
            return String.format("$%.2f", dollars);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private OverageCreditGrantInfo fetchOverageCreditGrant(String orgId) {
        try {
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
            if (tokens == null || tokens.getAccessToken() == null) return null;

            String url = getBaseApiUrl()
                    + "/api/oauth/organizations/" + orgId + "/overage_credit_grant";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + tokens.getAccessToken())
                    .header("anthropic-beta", "oauth-2023-11-01")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), OverageCreditGrantInfo.class);
            }
            return null;

        } catch (Exception e) {
            log.error("fetchOverageCreditGrant failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean dataUnchanged(OverageCreditGrantInfo existing, OverageCreditGrantInfo incoming) {
        if (existing == null) return false;
        return existing.isAvailable() == incoming.isAvailable()
                && existing.isEligible() == incoming.isEligible()
                && existing.isGranted() == incoming.isGranted()
                && java.util.Objects.equals(existing.getAmountMinorUnits(), incoming.getAmountMinorUnits())
                && java.util.Objects.equals(existing.getCurrency(), incoming.getCurrency());
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
