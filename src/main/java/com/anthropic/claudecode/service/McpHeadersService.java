package com.anthropic.claudecode.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * MCP dynamic headers helper service.
 * Translated from src/services/mcp/headersHelper.ts
 *
 * Executes a server-supplied {@code headersHelper} shell command to obtain
 * dynamic request headers for HTTP/SSE/WebSocket MCP servers (e.g. for
 * rotating bearer tokens).  Dynamic headers override static headers when both
 * are present.
 *
 * Security: project/local-scoped servers are checked for workspace trust before
 * the helper is executed (except in non-interactive/CI sessions).
 */
@Slf4j
@Service
public class McpHeadersService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpHeadersService.class);


    /** Timeout for the headersHelper subprocess (mirrors timeout: 10_000 in TS). */
    private static final int HELPER_TIMEOUT_MS = 10_000;

    private final ObjectMapper objectMapper;

    @Autowired
    public McpHeadersService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------------
    // Scope types (mirrors ScopedMcpServerConfig.scope)
    // ---------------------------------------------------------------------------

    public enum McpServerScope {
        GLOBAL, PROJECT, LOCAL, DYNAMIC
    }

    // ---------------------------------------------------------------------------
    // Core API
    // ---------------------------------------------------------------------------

    /**
     * Execute the {@code headersHelper} script and return the resulting headers.
     * Returns {@code null} (not throwing) on failure so that the connection is
     * not blocked.
     * Translated from getMcpHeadersFromHelper() in headersHelper.ts
     *
     * @param serverName    The MCP server name (passed as CLAUDE_CODE_MCP_SERVER_NAME env var)
     * @param serverUrl     The MCP server URL (passed as CLAUDE_CODE_MCP_SERVER_URL env var)
     * @param headersHelper The shell command / script path to execute
     * @param scope         Config scope (PROJECT/LOCAL triggers trust check)
     * @param isNonInteractive Whether running in non-interactive mode (skips trust check)
     * @return CompletableFuture with headers map, or null on failure
     */
    public CompletableFuture<Map<String, String>> getMcpHeadersFromHelper(
            String serverName,
            String serverUrl,
            String headersHelper,
            McpServerScope scope,
            boolean isNonInteractive) {

        if (headersHelper == null || headersHelper.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        // Security check: project/local-scoped servers need workspace trust
        // (skip in non-interactive/CI mode)
        if (!isNonInteractive
                && (scope == McpServerScope.PROJECT || scope == McpServerScope.LOCAL)) {
            boolean hasTrust = checkWorkspaceTrust();
            if (!hasTrust) {
                log.error("[McpHeaders] headersHelper for '{}' invoked before workspace trust is confirmed",
                    serverName);
                return CompletableFuture.completedFuture(null);
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("[McpHeaders] Executing headersHelper for server: {}", serverName);

                ProcessBuilder pb = new ProcessBuilder("sh", "-c", headersHelper);
                pb.environment().put("CLAUDE_CODE_MCP_SERVER_NAME", serverName);
                if (serverUrl != null) {
                    pb.environment().put("CLAUDE_CODE_MCP_SERVER_URL", serverUrl);
                }
                pb.redirectErrorStream(false);

                Process process = pb.start();
                boolean finished = process.waitFor(HELPER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException(
                        "headersHelper for MCP server '" + serverName + "' timed out");
                }

                if (process.exitValue() != 0) {
                    throw new RuntimeException(
                        "headersHelper for MCP server '" + serverName + "' did not return a valid value");
                }

                String stdout = new String(process.getInputStream().readAllBytes()).trim();
                if (stdout.isEmpty()) {
                    throw new RuntimeException(
                        "headersHelper for MCP server '" + serverName + "' did not return a valid value");
                }

                // Parse JSON — must be a plain string→string object
                Map<String, Object> parsed = objectMapper.readValue(stdout,
                    new TypeReference<Map<String, Object>>() {});

                Map<String, String> headers = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                    if (!(entry.getValue() instanceof String)) {
                        throw new RuntimeException(
                            "headersHelper for MCP server '" + serverName +
                            "' returned non-string value for key \"" + entry.getKey() + "\": " +
                            (entry.getValue() == null ? "null" : entry.getValue().getClass().getSimpleName()));
                    }
                    headers.put(entry.getKey(), (String) entry.getValue());
                }

                log.debug("[McpHeaders] Retrieved {} headers from headersHelper for '{}'",
                    headers.size(), serverName);
                return headers;

            } catch (Exception e) {
                log.error("[McpHeaders] Error getting headers from headersHelper for '{}': {}",
                    serverName, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Get the combined headers for an MCP server (static config headers merged
     * with dynamic headersHelper output, with dynamic taking precedence).
     * Translated from getMcpServerHeaders() in headersHelper.ts
     *
     * @param serverName     The MCP server name
     * @param serverUrl      The MCP server URL
     * @param staticHeaders  Headers from the server config (may be null)
     * @param headersHelper  Shell command to produce dynamic headers (may be null)
     * @param scope          Config scope
     * @param isNonInteractive Whether in non-interactive mode
     * @return CompletableFuture with merged headers (never null)
     */
    public CompletableFuture<Map<String, String>> getMcpServerHeaders(
            String serverName,
            String serverUrl,
            Map<String, String> staticHeaders,
            String headersHelper,
            McpServerScope scope,
            boolean isNonInteractive) {

        Map<String, String> base = staticHeaders != null ? new LinkedHashMap<>(staticHeaders) : new LinkedHashMap<>();

        return getMcpHeadersFromHelper(serverName, serverUrl, headersHelper, scope, isNonInteractive)
            .thenApply(dynamic -> {
                Map<String, String> merged = new LinkedHashMap<>(base);
                if (dynamic != null) {
                    merged.putAll(dynamic); // dynamic overrides static
                }
                return merged;
            });
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Check whether workspace trust has been accepted.
     * Full implementation reads the trust dialog state from config.
     * Translated from checkHasTrustDialogAccepted() in config.ts
     */
    private boolean checkWorkspaceTrust() {
        // In a full Spring implementation this delegates to a ConfigService or
        // a persisted setting that tracks whether the user has confirmed trust.
        return false; // conservative default
    }
}
