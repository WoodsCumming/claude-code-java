package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PrivacyUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.Data;

/**
 * Overage credit grant service.
 * Translated from src/services/api/overageCreditGrant.ts
 *
 * Manages overage credit grants for users.
 */
@Slf4j
@Service
public class OverageCreditService {



    private static final long CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    private volatile OverageCreditGrantInfo cachedInfo;
    private volatile long cacheTime;

    @Autowired
    public OverageCreditService(OAuthService oauthService, ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    /**
     * Get cached overage credit grant info.
     * Translated from getCachedOverageCreditGrant() in overageCreditGrant.ts
     */
    public Optional<OverageCreditGrantInfo> getCachedOverageCreditGrant() {
        if (cachedInfo == null) return Optional.empty();
        if ((System.currentTimeMillis() - cacheTime) > CACHE_TTL_MS) return Optional.empty();
        return Optional.of(cachedInfo);
    }

    /**
     * Refresh the overage credit grant cache.
     * Translated from refreshOverageCreditGrantCache() in overageCreditGrant.ts
     */
    public CompletableFuture<Void> refreshOverageCreditGrantCache() {
        if (PrivacyUtils.isEssentialTrafficOnly()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
                if (tokens == null) return;

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/api/oauth/organizations/overage_credit_grant"))
                    .header("Authorization", "Bearer " + tokens.getAccessToken())
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    cachedInfo = objectMapper.readValue(response.body(), OverageCreditGrantInfo.class);
                    cacheTime = System.currentTimeMillis();
                }

            } catch (Exception e) {
                log.debug("Could not refresh overage credit grant: {}", e.getMessage());
            }
        });
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OverageCreditGrantInfo {
        private boolean available;
        private boolean eligible;
        private boolean granted;
        private Long amountMinorUnits;
        private String currency;

        public boolean isAvailable() { return available; }
        public void setAvailable(boolean v) { available = v; }
        public boolean isEligible() { return eligible; }
        public void setEligible(boolean v) { eligible = v; }
        public boolean isGranted() { return granted; }
        public void setGranted(boolean v) { granted = v; }
        public Long getAmountMinorUnits() { return amountMinorUnits; }
        public void setAmountMinorUnits(Long v) { amountMinorUnits = v; }
        public String getCurrency() { return currency; }
        public void setCurrency(String v) { currency = v; }
    }
}
