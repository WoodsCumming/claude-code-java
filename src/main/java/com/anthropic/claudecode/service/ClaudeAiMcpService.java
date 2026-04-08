package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.McpNormalizationUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Claude.ai MCP service.
 * Translated from src/services/mcp/claudeai.ts
 *
 * Fetches MCP server configurations from Claude.ai org configs.
 * These servers are managed by the organization via Claude.ai.
 * Results are memoized for the session lifetime (fetch once per CLI session).
 */
@Slf4j
@Service
public class ClaudeAiMcpService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClaudeAiMcpService.class);


    private static final long FETCH_TIMEOUT_MS = 5_000;
    private static final String MCP_SERVERS_BETA_HEADER = "mcp-servers-2025-12-04";
    private static final String ANTHROPIC_VERSION_HEADER = "2023-06-01";

    /** Memoized result — null means not yet fetched. */
    private final AtomicReference<Map<String, ScopedMcpServerConfig>> cachedConfigs =
            new AtomicReference<>(null);

    private final OAuthService oauthService;
    private final GlobalConfigService globalConfigService;
    private final AnalyticsService analyticsService;
    private final ObjectMapper objectMapper;
    private final McpAuthService mcpAuthService;

    @Autowired
    public ClaudeAiMcpService(OAuthService oauthService,
                               GlobalConfigService globalConfigService,
                               AnalyticsService analyticsService,
                               ObjectMapper objectMapper,
                               McpAuthService mcpAuthService) {
        this.oauthService = oauthService;
        this.globalConfigService = globalConfigService;
        this.analyticsService = analyticsService;
        this.objectMapper = objectMapper;
        this.mcpAuthService = mcpAuthService;
    }

    // ============================================================================
    // Domain types
    // ============================================================================

    /** A single MCP server entry returned by the Claude.ai API. */
    public record ClaudeAiMcpServer(
            String type,
            String id,
            String displayName,
            String url,
            String createdAt) {}

    /** API response envelope. */
    public record ClaudeAiMcpServersResponse(
            List<ClaudeAiMcpServer> data,
            boolean hasMore,
            String nextPage) {}

    /** Scoped MCP server configuration (simplified for claudeai-proxy type). */
    public record ScopedMcpServerConfig(
            String type,
            String url,
            String id,
            String scope) {}

    // ============================================================================
    // Core operations
    // ============================================================================

    /**
     * Fetches MCP server configurations from Claude.ai org configs if eligible.
     * Translated from fetchClaudeAIMcpConfigsIfEligible() in claudeai.ts
     *
     * Eligibility checks (in order):
     * 1. ENABLE_CLAUDEAI_MCP_SERVERS env var not set to falsy value
     * 2. OAuth access token present
     * 3. Token has user:mcp_servers scope
     *
     * Memoized for the session lifetime.
     */
    public CompletableFuture<Map<String, ScopedMcpServerConfig>> fetchClaudeAIMcpConfigsIfEligible() {
        Map<String, ScopedMcpServerConfig> cached = cachedConfigs.get();
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check env var kill-switch
                String envFlag = System.getenv("ENABLE_CLAUDEAI_MCP_SERVERS");
                if (isEnvDefinedFalsy(envFlag)) {
                    log.debug("[claudeai-mcp] Disabled via env var");
                    analyticsService.logEvent("tengu_claudeai_mcp_eligibility",
                            Map.of("state", "disabled_env_var"));
                    return Map.<String, ScopedMcpServerConfig>of();
                }

                OAuthService.OAuthTokens tokens = oauthService.getClaudeAIOAuthTokens();
                if (tokens == null || tokens.getAccessToken() == null) {
                    log.debug("[claudeai-mcp] No access token");
                    analyticsService.logEvent("tengu_claudeai_mcp_eligibility",
                            Map.of("state", "no_oauth_token"));
                    return Map.<String, ScopedMcpServerConfig>of();
                }

                // Check for user:mcp_servers scope directly. In non-interactive mode,
                // isClaudeAISubscriber() returns false when ANTHROPIC_API_KEY is set (even
                // with valid OAuth tokens). Checking the scope directly allows users with
                // both API keys and OAuth tokens to access claude.ai MCPs in print mode.
                List<String> scopes = tokens.getScopes();
                if (scopes == null || !scopes.contains("user:mcp_servers")) {
                    String scopeStr = (scopes != null) ? String.join(",", scopes) : "none";
                    log.debug("[claudeai-mcp] Missing user:mcp_servers scope (scopes={})", scopeStr);
                    analyticsService.logEvent("tengu_claudeai_mcp_eligibility",
                            Map.of("state", "missing_scope"));
                    return Map.<String, ScopedMcpServerConfig>of();
                }

                String baseUrl = oauthService.getBaseApiUrl();
                String url = baseUrl + "/v1/mcp_servers?limit=1000";
                log.debug("[claudeai-mcp] Fetching from {}", url);

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + tokens.getAccessToken())
                        .header("Content-Type", "application/json")
                        .header("anthropic-beta", MCP_SERVERS_BETA_HEADER)
                        .header("anthropic-version", ANTHROPIC_VERSION_HEADER)
                        .GET()
                        .timeout(Duration.ofMillis(FETCH_TIMEOUT_MS))
                        .build();

                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.debug("[claudeai-mcp] Fetch failed with status {}", response.statusCode());
                    return Map.<String, ScopedMcpServerConfig>of();
                }

                Map<String, Object> body = objectMapper.readValue(
                        response.body(), new TypeReference<>() {});
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> servers = (List<Map<String, Object>>) body.get("data");
                if (servers == null) return Map.<String, ScopedMcpServerConfig>of();

                Map<String, ScopedMcpServerConfig> configs = new LinkedHashMap<>();
                // Track used normalized names to detect collisions and assign (2), (3) suffixes.
                // We check the final normalized name (including suffix) to handle edge cases where
                // a suffixed name collides with another server's base name.
                java.util.Set<String> usedNormalizedNames = new java.util.LinkedHashSet<>();

                for (Map<String, Object> server : servers) {
                    String displayName = (String) server.get("display_name");
                    String serverId = (String) server.get("id");
                    String serverUrl = (String) server.get("url");

                    String baseName = "claude.ai " + displayName;

                    // Try without suffix first, then increment until we find an unused name
                    String finalName = baseName;
                    String finalNormalized = McpNormalizationUtils.normalizeNameForMCP(finalName);
                    int count = 1;
                    while (usedNormalizedNames.contains(finalNormalized)) {
                        count++;
                        finalName = baseName + " (" + count + ")";
                        finalNormalized = McpNormalizationUtils.normalizeNameForMCP(finalName);
                    }
                    usedNormalizedNames.add(finalNormalized);

                    configs.put(finalName, new ScopedMcpServerConfig(
                            "claudeai-proxy", serverUrl, serverId, "claudeai"));
                }

                log.debug("[claudeai-mcp] Fetched {} servers", configs.size());
                analyticsService.logEvent("tengu_claudeai_mcp_eligibility",
                        Map.of("state", "eligible"));

                cachedConfigs.set(configs);
                return configs;

            } catch (Exception e) {
                log.debug("[claudeai-mcp] Fetch failed: {}", e.getMessage());
                return Map.<String, ScopedMcpServerConfig>of();
            }
        });
    }

    /**
     * Clears the memoized cache for fetchClaudeAIMcpConfigsIfEligible.
     * Call this after login so the next fetch will use the new auth tokens.
     * Translated from clearClaudeAIMcpConfigsCache() in claudeai.ts
     */
    public void clearClaudeAIMcpConfigsCache() {
        cachedConfigs.set(null);
        mcpAuthService.clearMcpAuthCache();
    }

    /**
     * Record that a claude.ai connector successfully connected. Idempotent.
     *
     * Gates the "N connectors unavailable/need auth" startup notifications: a
     * connector that was working yesterday and is now failed is a state change
     * worth surfacing; an org-configured connector that's been needs-auth since
     * it showed up is one the user has demonstrably ignored.
     * Translated from markClaudeAiMcpConnected() in claudeai.ts
     */
    public void markClaudeAiMcpConnected(String name) {
        globalConfigService.updateConfig(current -> {
            List<String> seen = current.getClaudeAiMcpEverConnected();
            if (seen != null && seen.contains(name)) return current;
            List<String> updated = new java.util.ArrayList<>(seen != null ? seen : List.of());
            updated.add(name);
            return current.withClaudeAiMcpEverConnected(updated);
        });
    }

    /**
     * Returns whether a given claude.ai MCP server has ever successfully connected.
     * Translated from hasClaudeAiMcpEverConnected() in claudeai.ts
     */
    public boolean hasClaudeAiMcpEverConnected(String name) {
        List<String> seen = globalConfigService.getGlobalConfig().getClaudeAiMcpEverConnected();
        return seen != null && seen.contains(name);
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    /** Mirrors isEnvDefinedFalsy() — true for "0", "false", "no", "" when not null. */
    private static boolean isEnvDefinedFalsy(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase();
        return v.isEmpty() || v.equals("0") || v.equals("false") || v.equals("no");
    }
}
