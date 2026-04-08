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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bootstrap service for fetching initial configuration from the Anthropic API.
 * Translated from src/services/api/bootstrap.ts
 *
 * Fetches client_data and additional_model_options on startup, persisting them
 * to a disk cache via GlobalConfigService. Only writes when data actually changes.
 */
@Slf4j
@Service
public class BootstrapService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BootstrapService.class);


    private final AuthService authService;
    private final GlobalConfigService globalConfigService;
    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    @Autowired
    public BootstrapService(AuthService authService,
                            GlobalConfigService globalConfigService,
                            OAuthService oauthService,
                            ObjectMapper objectMapper) {
        this.authService = authService;
        this.globalConfigService = globalConfigService;
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    /** Mirrors the Zod-transformed additional_model_options shape in bootstrap.ts */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelOption {
        /** Raw API field: "model" → mapped to "value" */
        private String value;
        /** Raw API field: "name" → mapped to "label" */
        private String label;
        private String description;

        public String getValue() { return value; }
        public void setValue(String v) { value = v; }
        public String getLabel() { return label; }
        public void setLabel(String v) { label = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
    

    }

    /** Wire DTO for the raw API response (before transform). */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class BootstrapApiResponse {
        @JsonProperty("client_data")
        private Map<String, Object> clientData;

        @JsonProperty("additional_model_options")
        private List<RawModelOption> additionalModelOptions;
    
        public List<RawModelOption> getAdditionalModelOptions() { return additionalModelOptions; }
    
        public Map<String, Object> getClientData() { return clientData; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawModelOption {
        private String model;
        private String name;
        private String description;
    
        public String getDescription() { return description; }
    
        public String getModel() { return model; }
    
        public String getName() { return name; }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch bootstrap data from the API and persist to disk cache.
     * Only writes if data actually changed.
     * Translated from fetchBootstrapData() in bootstrap.ts
     */
    public CompletableFuture<Void> fetchBootstrapData() {
        return CompletableFuture.runAsync(() -> {
            try {
                BootstrapApiResponse response = fetchBootstrapAPI();
                if (response == null) return;

                Map<String, Object> clientData = response.getClientData();
                List<ModelOption> additionalModelOptions = transformModelOptions(
                        response.getAdditionalModelOptions());

                // Only persist if data actually changed — avoids config write on every startup
                com.anthropic.claudecode.model.GlobalConfig config = globalConfigService.getGlobalConfig();
                if (Objects.equals(config.getClientDataCache(), clientData)
                        && Objects.equals(config.getAdditionalModelOptionsCache(), additionalModelOptions)) {
                    log.debug("[Bootstrap] Cache unchanged, skipping write");
                    return;
                }

                log.debug("[Bootstrap] Cache updated, persisting to disk");
                globalConfigService.updateGlobalConfig(current -> {
                    current.setClientDataCache(clientData);
                    current.setAdditionalModelOptionsCache(
                        additionalModelOptions != null
                            ? new java.util.ArrayList<>(additionalModelOptions)
                            : null);
                    return current;
                });

            } catch (Exception e) {
                log.error("[Bootstrap] Error: {}", e.getMessage(), e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fetch from the bootstrap endpoint.
     * - Skips if essential-traffic-only mode is active.
     * - Skips if API provider is not first-party.
     * - Prefers OAuth (requires user:profile scope); falls back to API key.
     * Translated from fetchBootstrapAPI() in bootstrap.ts
     */
    private BootstrapApiResponse fetchBootstrapAPI() {
        if (isEssentialTrafficOnly()) {
            log.debug("[Bootstrap] Skipped: Nonessential traffic disabled");
            return null;
        }

        if (!isFirstPartyProvider()) {
            log.debug("[Bootstrap] Skipped: 3P provider");
            return null;
        }

        String apiKey = authService.getApiKey();
        OAuthService.OAuthTokens oauthTokens = safeGetOAuthTokens();
        boolean hasUsableOAuth = oauthTokens != null
                && oauthTokens.getAccessToken() != null
                && hasProfileScope(oauthTokens);

        if (!hasUsableOAuth && apiKey == null) {
            log.debug("[Bootstrap] Skipped: no usable OAuth or API key");
            return null;
        }

        String endpoint = getBaseApiUrl() + "/api/claude_cli/bootstrap";

        try {
            // withOAuth401Retry equivalent: try once; on 401 refresh and retry
            return executeBootstrapRequest(endpoint, apiKey, oauthTokens, hasUsableOAuth);
        } catch (Exception e) {
            log.debug("[Bootstrap] Fetch failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private BootstrapApiResponse executeBootstrapRequest(
            String endpoint,
            String apiKey,
            OAuthService.OAuthTokens tokens,
            boolean hasUsableOAuth) throws Exception {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("User-Agent", getUserAgent())
                .timeout(Duration.ofSeconds(5))
                .GET();

        if (hasUsableOAuth && tokens != null && tokens.getAccessToken() != null) {
            builder.header("Authorization", "Bearer " + tokens.getAccessToken());
            builder.header("anthropic-beta", "oauth-2023-11-01");
        } else if (apiKey != null) {
            builder.header("x-api-key", apiKey);
        } else {
            log.debug("[Bootstrap] No auth available, aborting");
            return null;
        }

        log.debug("[Bootstrap] Fetching");
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            BootstrapApiResponse parsed = objectMapper.readValue(
                    response.body(), BootstrapApiResponse.class);
            log.debug("[Bootstrap] Fetch ok");
            return parsed;
        }

        if (response.statusCode() == 401 && hasUsableOAuth) {
            // Attempt token refresh then retry with API key fallback
            log.debug("[Bootstrap] 401 on OAuth, retrying with API key if available");
            if (apiKey != null) {
                HttpRequest retryRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", getUserAgent())
                        .header("x-api-key", apiKey)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> retryResponse = client.send(retryRequest,
                        HttpResponse.BodyHandlers.ofString());
                if (retryResponse.statusCode() == 200) {
                    return objectMapper.readValue(retryResponse.body(), BootstrapApiResponse.class);
                }
            }
        }

        log.debug("[Bootstrap] Response failed validation: status {}", response.statusCode());
        return null;
    }

    private List<ModelOption> transformModelOptions(List<RawModelOption> raw) {
        if (raw == null) return Collections.emptyList();
        return raw.stream()
                .filter(Objects::nonNull)
                .map(r -> {
                    ModelOption opt = new ModelOption();
                    opt.setValue(r.getModel());
                    opt.setLabel(r.getName());
                    opt.setDescription(r.getDescription());
                    return opt;
                })
                .toList();
    }

    private boolean isEssentialTrafficOnly() {
        String val = System.getenv("CLAUDE_ESSENTIAL_TRAFFIC_ONLY");
        return "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    private boolean isFirstPartyProvider() {
        String provider = System.getenv("CLAUDE_API_PROVIDER");
        return provider == null || "firstParty".equals(provider);
    }

    private OAuthService.OAuthTokens safeGetOAuthTokens() {
        try {
            return oauthService.getCurrentTokens();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasProfileScope(OAuthService.OAuthTokens tokens) {
        if (tokens == null || tokens.getScopes() == null) return false;
        return tokens.getScopes().contains("user:profile");
    }

    private String getBaseApiUrl() {
        String url = System.getenv("ANTHROPIC_BASE_API_URL");
        return url != null ? url : "https://api.anthropic.com";
    }

    private String getUserAgent() {
        return "ClaudeCode-Java/1.0";
    }
}
