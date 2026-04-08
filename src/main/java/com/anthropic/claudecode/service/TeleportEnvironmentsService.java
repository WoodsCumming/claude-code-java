package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Teleport environments service.
 * Translated from src/utils/teleport/environments.ts
 *
 * Fetches available CCR environments from the Environment API and supports
 * creating a default anthropic_cloud environment for users who have none.
 */
@Slf4j
@Service
public class TeleportEnvironmentsService {



    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OAuthService oauthService;

    @Autowired
    public TeleportEnvironmentsService(OkHttpClient httpClient,
                                        ObjectMapper objectMapper,
                                        OAuthService oauthService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    // =========================================================================
    // Type definitions — mirrors TypeScript types in environments.ts
    // =========================================================================

    public enum EnvironmentKind { anthropic_cloud, byoc, bridge }
    public enum EnvironmentState { active }

    @Data
    @lombok.Builder
    public static class EnvironmentResource {
        /** "anthropic_cloud" | "byoc" | "bridge" */
        private String kind;
        private String environmentId;
        private String name;
        private String createdAt;
        /** "active" */
        private String state;
    
        public String getEnvironmentId() { return environmentId; }
    
        public static EnvironmentResourceBuilder builder() { return new EnvironmentResourceBuilder(); }
        public static class EnvironmentResourceBuilder {
            private String kind;
            private String environmentId;
            private String name;
            private String createdAt;
            private String state;
            public EnvironmentResourceBuilder kind(String v) { this.kind = v; return this; }
            public EnvironmentResourceBuilder environmentId(String v) { this.environmentId = v; return this; }
            public EnvironmentResourceBuilder name(String v) { this.name = v; return this; }
            public EnvironmentResourceBuilder createdAt(String v) { this.createdAt = v; return this; }
            public EnvironmentResourceBuilder state(String v) { this.state = v; return this; }
            public EnvironmentResource build() {
                EnvironmentResource o = new EnvironmentResource();
                o.kind = kind;
                o.environmentId = environmentId;
                o.name = name;
                o.createdAt = createdAt;
                o.state = state;
                return o;
            }
        }
    

        public EnvironmentResource() {}
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EnvironmentListResponse {
        private List<EnvironmentResource> environments;
        private boolean hasMore;
        private String firstId;
        private String lastId;

        public List<EnvironmentResource> getEnvironments() { return environments; }
        public void setEnvironments(List<EnvironmentResource> v) { environments = v; }
        public boolean isHasMore() { return hasMore; }
        public void setHasMore(boolean v) { hasMore = v; }
        public String getFirstId() { return firstId; }
        public void setFirstId(String v) { firstId = v; }
        public String getLastId() { return lastId; }
        public void setLastId(String v) { lastId = v; }
    }

    // =========================================================================
    // API operations
    // =========================================================================

    /**
     * Fetch the list of available environments from the Environment API.
     * Translated from fetchEnvironments() in environments.ts
     */
    public CompletableFuture<List<EnvironmentResource>> fetchEnvironments() {
        return CompletableFuture.supplyAsync(() -> {
            String accessToken = oauthService.getAccessToken();
            if (accessToken == null) {
                throw new IllegalStateException(
                    "Claude Code web sessions require authentication with a Claude.ai account. "
                    + "API key authentication is not sufficient. Please run /login to authenticate.");
            }
            return accessToken;
        }).thenCompose(accessToken ->
            oauthService.getOrganizationUUID().thenCompose(orgUUID -> {
                if (orgUUID == null) {
                    throw new IllegalStateException("Unable to get organization UUID");
                }
                return CompletableFuture.supplyAsync(() -> {
                    String url = oauthService.getBaseApiUrl() + "/v1/environment_providers";
                    Map<String, String> headers = TeleportService.getOAuthHeaders(accessToken);
                    headers.put("x-organization-uuid", orgUUID);

                    Request.Builder rb = new Request.Builder().url(url).get();
                    headers.forEach(rb::addHeader);

                    try (Response resp = httpClient.newCall(rb.build()).execute()) {
                        if (resp.code() != 200 || resp.body() == null) {
                            throw new RuntimeException("Failed to fetch environments: "
                                + resp.code() + " " + resp.message());
                        }
                        Map<?, ?> body = objectMapper.readValue(resp.body().string(), Map.class);
                        List<Map<String, Object>> envs =
                            (List<Map<String, Object>>) body.get("environments");
                        if (envs == null) return List.<EnvironmentResource>of();
                        List<EnvironmentResource> result = new ArrayList<>();
                        for (Map<String, Object> env : envs) {
                            result.add(EnvironmentResource.builder()
                                .kind((String) env.get("kind"))
                                .environmentId((String) env.get("environment_id"))
                                .name((String) env.get("name"))
                                .createdAt((String) env.get("created_at"))
                                .state((String) env.get("state"))
                                .build());
                        }
                        return result;
                    } catch (RuntimeException re) {
                        log.error("[environments] fetchEnvironments error: {}", re.getMessage());
                        throw re;
                    } catch (Exception e) {
                        log.error("[environments] fetchEnvironments error: {}", e.getMessage());
                        throw new RuntimeException("Failed to fetch environments: " + e.getMessage(), e);
                    }
                });
            })
        );
    }

    /**
     * Create a default anthropic_cloud environment.
     * Translated from createDefaultCloudEnvironment() in environments.ts
     */
    public CompletableFuture<EnvironmentResource> createDefaultCloudEnvironment(String name) {
        return CompletableFuture.supplyAsync(() -> {
            String accessToken = oauthService.getAccessToken();
            if (accessToken == null) {
                throw new IllegalStateException("No access token available");
            }
            return accessToken;
        }).thenCompose(accessToken ->
            oauthService.getOrganizationUUID().thenCompose(orgUUID -> {
                if (orgUUID == null) {
                    throw new IllegalStateException("Unable to get organization UUID");
                }
                return CompletableFuture.supplyAsync(() -> {
                    String url = oauthService.getBaseApiUrl() + "/v1/environment_providers/cloud/create";
                    Map<String, String> headers = TeleportService.getOAuthHeaders(accessToken);
                    headers.put("anthropic-beta", TeleportService.CCR_BYOC_BETA);
                    headers.put("x-organization-uuid", orgUUID);

                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("environment_type", "anthropic");
                    config.put("cwd", "/home/user");
                    config.put("init_script", null);
                    config.put("environment", Map.of());
                    config.put("languages", List.of(
                        Map.of("name", "python", "version", "3.11"),
                        Map.of("name", "node", "version", "20")
                    ));
                    config.put("network_config", Map.of(
                        "allowed_hosts", List.of(),
                        "allow_default_hosts", true
                    ));

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("name", name);
                    body.put("kind", "anthropic_cloud");
                    body.put("description", "");
                    body.put("config", config);

                    try {
                        String json = objectMapper.writeValueAsString(body);
                        Request.Builder rb = new Request.Builder()
                            .url(url)
                            .post(RequestBody.create(json, MediaType.parse("application/json")));
                        headers.forEach(rb::addHeader);

                        try (Response resp = httpClient.newCall(rb.build()).execute()) {
                            if (!resp.isSuccessful() || resp.body() == null) {
                                throw new RuntimeException("createDefaultCloudEnvironment failed: " + resp.code());
                            }
                            Map<?, ?> respBody = objectMapper.readValue(resp.body().string(), Map.class);
                            return EnvironmentResource.builder()
                                .kind((String) respBody.get("kind"))
                                .environmentId((String) respBody.get("environment_id"))
                                .name((String) respBody.get("name"))
                                .createdAt((String) respBody.get("created_at"))
                                .state((String) respBody.get("state"))
                                .build();
                        }
                    } catch (RuntimeException re) {
                        throw re;
                    } catch (Exception e) {
                        throw new RuntimeException("createDefaultCloudEnvironment failed", e);
                    }
                });
            })
        );
    }
}
