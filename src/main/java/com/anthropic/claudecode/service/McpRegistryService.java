package com.anthropic.claudecode.service;

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
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP official registry service.
 * Translated from src/services/mcp/officialRegistry.ts
 *
 * Fire-and-forget fetch of the official MCP server registry at startup.
 * Populates an in-memory Set of normalized URLs so that isOfficialMcpUrl()
 * can answer in O(1) without a network call.
 *
 * URL normalization strips the query string and trailing slash to match the
 * normalization done by getLoggingSafeMcpBaseUrl, enabling direct Set.has() lookup.
 *
 * The registry is only fetched when CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC is not set.
 */
@Slf4j
@Service
public class McpRegistryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpRegistryService.class);


    private static final String REGISTRY_URL =
        "https://api.anthropic.com/mcp-registry/v0/servers?version=latest&visibility=commercial";

    /** Timeout mirrors the 5 000 ms timeout in the TypeScript source. */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;

    /**
     * In-memory cache of normalized official MCP URLs.
     * null = not yet fetched (fail-closed: isOfficialMcpUrl returns false).
     * Mirrors the module-level officialUrls variable in officialRegistry.ts
     */
    private volatile Set<String> officialUrls;

    @Autowired
    public McpRegistryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------------
    // API
    // ---------------------------------------------------------------------------

    /**
     * Fire-and-forget fetch of the official MCP registry.
     * Populates officialUrls for subsequent isOfficialMcpUrl() lookups.
     * Translated from prefetchOfficialMcpUrls() in officialRegistry.ts
     *
     * Respects CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC — does nothing when set.
     *
     * @return CompletableFuture (can be ignored by callers)
     */
    public CompletableFuture<Void> prefetchOfficialMcpUrls() {
        if (System.getenv("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC") != null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(FETCH_TIMEOUT)
                    .build();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(REGISTRY_URL))
                    .timeout(FETCH_TIMEOUT)
                    .GET()
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.debug("[McpRegistry] Registry fetch returned HTTP {}", response.statusCode());
                    return;
                }

                Map<String, Object> data = objectMapper.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {});

                Set<String> urls = new HashSet<>();

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> servers =
                    (List<Map<String, Object>>) data.get("servers");

                if (servers != null) {
                    for (Map<String, Object> entry : servers) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> server =
                            (Map<String, Object>) entry.get("server");
                        if (server == null) continue;

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> remotes =
                            (List<Map<String, Object>>) server.get("remotes");
                        if (remotes == null) continue;

                        for (Map<String, Object> remote : remotes) {
                            String normalized = normalizeUrl((String) remote.get("url"));
                            if (normalized != null) {
                                urls.add(normalized);
                            }
                        }
                    }
                }

                officialUrls = urls;
                log.debug("[McpRegistry] Loaded {} official MCP URLs", urls.size());

            } catch (Exception error) {
                log.debug("[McpRegistry] Failed to fetch MCP registry: {}", error.getMessage());
            }
        });
    }

    /**
     * Returns true iff the given normalized URL is in the official MCP registry.
     * Undefined (not-yet-fetched) registry → false (fail-closed).
     * Translated from isOfficialMcpUrl() in officialRegistry.ts
     *
     * @param normalizedUrl URL already normalized via normalizeUrl() or
     *                      getLoggingSafeMcpBaseUrl() — no query string, no trailing slash
     */
    public boolean isOfficialMcpUrl(String normalizedUrl) {
        Set<String> snapshot = officialUrls;
        return snapshot != null && snapshot.contains(normalizedUrl);
    }

    /**
     * Reset the cached registry (for testing).
     * Translated from resetOfficialMcpUrlsForTesting() in officialRegistry.ts
     */
    public void resetOfficialMcpUrlsForTesting() {
        officialUrls = null;
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Normalize a URL by stripping its query string and trailing slash.
     * Returns null if the URL is invalid.
     * Translated from normalizeUrl() in officialRegistry.ts
     */
    public String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = new URI(url);
            // Reconstruct without query and fragment
            String normalized = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                + (uri.getPath() != null ? uri.getPath() : "");
            // Remove trailing slash
            return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        } catch (Exception e) {
            return null;
        }
    }
}
