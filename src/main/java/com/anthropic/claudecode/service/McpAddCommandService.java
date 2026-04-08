package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP {@code add} CLI sub-command service.
 * Translated from src/commands/mcp/addCommand.ts
 *
 * Registers an MCP server in one of three transports:
 * <ul>
 *   <li>{@code stdio} — local executable with optional env vars and args.</li>
 *   <li>{@code sse}   — remote SSE endpoint with optional OAuth / headers.</li>
 *   <li>{@code http}  — remote HTTP endpoint with optional OAuth / headers.</li>
 * </ul>
 *
 * The caller is responsible for pre-validating required parameters; this
 * service focuses on the core add-and-persist logic.
 */
@Slf4j
@Service
public class McpAddCommandService {



    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    /** URL-like patterns that suggest a stdio command was mis-typed as a URL. */
    private static final List<String> URL_PREFIXES = List.of("http://", "https://", "localhost");
    private static final List<String> URL_SUFFIXES = List.of("/sse", "/mcp");

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final McpConfigService mcpConfigService;
    private final McpAuthService mcpAuthService;
    private final AnalyticsService analyticsService;
    private final McpXaaIdpLoginService mcpXaaIdpLoginService;

    @Autowired
    public McpAddCommandService(McpConfigService mcpConfigService,
                                 McpAuthService mcpAuthService,
                                 AnalyticsService analyticsService,
                                 McpXaaIdpLoginService mcpXaaIdpLoginService) {
        this.mcpConfigService = mcpConfigService;
        this.mcpAuthService = mcpAuthService;
        this.analyticsService = analyticsService;
        this.mcpXaaIdpLoginService = mcpXaaIdpLoginService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Execute the {@code mcp add} sub-command.
     * Mirrors the {@code .action()} callback in addCommand.ts.
     *
     * @param request All parameters parsed from the CLI invocation.
     * @return A {@link McpAddResult} describing the outcome.
     */
    public CompletableFuture<McpAddResult> addMcpServer(McpAddRequest request) {
        return CompletableFuture.supplyAsync(() -> {

            // Basic validation
            if (request.name() == null || request.name().isBlank()) {
                return McpAddResult.error(
                    "Error: Server name is required.\n" +
                    "Usage: claude mcp add <name> <command> [args...]"
                );
            }
            if (request.commandOrUrl() == null || request.commandOrUrl().isBlank()) {
                return McpAddResult.error(
                    "Error: Command is required when server name is provided.\n" +
                    "Usage: claude mcp add <name> <command> [args...]"
                );
            }

            try {
                String scope = ensureConfigScope(request.scope());
                String transport = ensureTransport(request.transport());

                // XAA validation
                if (request.xaa() && !mcpXaaIdpLoginService.isXaaEnabled()) {
                    return McpAddResult.error(
                        "Error: --xaa requires CLAUDE_CODE_ENABLE_XAA=1 in your environment"
                    );
                }
                if (request.xaa()) {
                    List<String> missing = new ArrayList<>();
                    if (request.clientId() == null) missing.add("--client-id");
                    if (!request.clientSecret()) missing.add("--client-secret");
                    if (mcpXaaIdpLoginService.getXaaIdpSettings().isEmpty()) {
                        missing.add("'claude mcp xaa setup' (settings.xaaIdp not configured)");
                    }
                    if (!missing.isEmpty()) {
                        return McpAddResult.error("Error: --xaa requires: " + String.join(", ", missing));
                    }
                }

                boolean transportExplicit = request.transport() != null;
                boolean looksLikeUrl = looksLikeUrl(request.commandOrUrl());

                analyticsService.logEvent("tengu_mcp_add", Map.of(
                    "type", transport,
                    "scope", scope,
                    "source", "command",
                    "transport", transport,
                    "transportExplicit", transportExplicit,
                    "looksLikeUrl", looksLikeUrl
                ));

                return switch (transport) {
                    case "sse" -> addSseServer(request, scope);
                    case "http" -> addHttpServer(request, scope);
                    default -> addStdioServer(request, scope, transportExplicit, looksLikeUrl);
                };

            } catch (Exception e) {
                log.error("Failed to add MCP server '{}'", request.name(), e);
                return McpAddResult.error(e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Transport-specific add methods
    // ---------------------------------------------------------------------------

    private McpAddResult addSseServer(McpAddRequest request, String scope) {
        Map<String, String> headers = request.headers() != null
            ? parseHeaders(request.headers()) : null;

        Integer callbackPort = parsePort(request.callbackPort());

        Map<String, Object> oauth = buildOAuth(request.clientId(), callbackPort, request.xaa());

        String clientSecret = null;
        if (request.clientSecret() && request.clientId() != null) {
            clientSecret = mcpAuthService.readClientSecret();
        }

        McpServerConfig serverConfig = McpServerConfig.sse(request.commandOrUrl(), headers, oauth);
        mcpConfigService.addMcpConfig(request.name(), serverConfig, scope);

        if (clientSecret != null) {
            mcpAuthService.saveMcpClientSecret(request.name(), serverConfig, clientSecret);
        }

        String msg = "Added SSE MCP server " + request.name() +
            " with URL: " + request.commandOrUrl() +
            " to " + scope + " config";
        if (headers != null) {
            msg += "\nHeaders: " + headers;
        }
        String filePath = mcpConfigService.describeMcpConfigFilePath(scope);
        return McpAddResult.ok(msg, filePath);
    }

    private McpAddResult addHttpServer(McpAddRequest request, String scope) {
        Map<String, String> headers = request.headers() != null
            ? parseHeaders(request.headers()) : null;

        Integer callbackPort = parsePort(request.callbackPort());

        Map<String, Object> oauth = buildOAuth(request.clientId(), callbackPort, request.xaa());

        String clientSecret = null;
        if (request.clientSecret() && request.clientId() != null) {
            clientSecret = mcpAuthService.readClientSecret();
        }

        McpServerConfig serverConfig = McpServerConfig.http(request.commandOrUrl(), headers, oauth);
        mcpConfigService.addMcpConfig(request.name(), serverConfig, scope);

        if (clientSecret != null) {
            mcpAuthService.saveMcpClientSecret(request.name(), serverConfig, clientSecret);
        }

        String msg = "Added HTTP MCP server " + request.name() +
            " with URL: " + request.commandOrUrl() +
            " to " + scope + " config";
        if (headers != null) {
            msg += "\nHeaders: " + headers;
        }
        String filePath = mcpConfigService.describeMcpConfigFilePath(scope);
        return McpAddResult.ok(msg, filePath);
    }

    private McpAddResult addStdioServer(McpAddRequest request, String scope,
                                         boolean transportExplicit, boolean looksLikeUrl) {
        StringBuilder warnings = new StringBuilder();

        // Warn about OAuth options being ignored for stdio
        if (request.clientId() != null || request.clientSecret()
                || request.callbackPort() != null || request.xaa()) {
            warnings.append("Warning: --client-id, --client-secret, --callback-port, and " +
                "--xaa are only supported for HTTP/SSE transports and will be " +
                "ignored for stdio.\n");
        }

        // Warn if the command looks like a URL but transport was not explicit
        if (!transportExplicit && looksLikeUrl) {
            warnings.append("\nWarning: The command \"").append(request.commandOrUrl())
                .append("\" looks like a URL, but is being interpreted as a stdio server ")
                .append("as --transport was not specified.\n")
                .append("If this is an HTTP server, use: claude mcp add --transport http ")
                .append(request.name()).append(" ").append(request.commandOrUrl()).append("\n")
                .append("If this is an SSE server, use: claude mcp add --transport sse ")
                .append(request.name()).append(" ").append(request.commandOrUrl()).append("\n");
        }

        Map<String, String> env = request.env() != null ? parseEnvVars(request.env()) : null;
        McpServerConfig serverConfig = McpServerConfig.stdio(
            request.commandOrUrl(),
            request.args() != null ? request.args() : List.of(),
            env
        );
        mcpConfigService.addMcpConfig(request.name(), serverConfig, scope);

        String argsStr = request.args() != null ? String.join(" ", request.args()) : "";
        String msg = "Added stdio MCP server " + request.name() +
            " with command: " + request.commandOrUrl() + " " + argsStr +
            " to " + scope + " config";

        String filePath = mcpConfigService.describeMcpConfigFilePath(scope);
        return McpAddResult.ok(
            warnings.length() > 0 ? warnings + msg : msg,
            filePath
        );
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static String ensureConfigScope(String scope) {
        if (scope == null || scope.isBlank()) return "local";
        return switch (scope.toLowerCase()) {
            case "local", "user", "project" -> scope.toLowerCase();
            default -> throw new IllegalArgumentException(
                "Invalid scope '" + scope + "'. Must be local, user, or project."
            );
        };
    }

    private static String ensureTransport(String transport) {
        if (transport == null || transport.isBlank()) return "stdio";
        return switch (transport.toLowerCase()) {
            case "stdio", "sse", "http" -> transport.toLowerCase();
            default -> throw new IllegalArgumentException(
                "Invalid transport '" + transport + "'. Must be stdio, sse, or http."
            );
        };
    }

    private static boolean looksLikeUrl(String command) {
        if (command == null) return false;
        for (String prefix : URL_PREFIXES) {
            if (command.startsWith(prefix)) return true;
        }
        for (String suffix : URL_SUFFIXES) {
            if (command.endsWith(suffix)) return true;
        }
        return false;
    }

    private static Map<String, String> parseHeaders(List<String> rawHeaders) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String header : rawHeaders) {
            int colon = header.indexOf(':');
            if (colon > 0) {
                result.put(header.substring(0, colon).trim(),
                           header.substring(colon + 1).trim());
            }
        }
        return result;
    }

    private static Map<String, String> parseEnvVars(List<String> rawEnv) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String kv : rawEnv) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                result.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return result;
    }

    private static Integer parsePort(String port) {
        if (port == null || port.isBlank()) return null;
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> buildOAuth(String clientId,
                                                   Integer callbackPort,
                                                   boolean xaa) {
        if (clientId == null && callbackPort == null && !xaa) return null;
        Map<String, Object> oauth = new LinkedHashMap<>();
        if (clientId != null) oauth.put("clientId", clientId);
        if (callbackPort != null) oauth.put("callbackPort", callbackPort);
        if (xaa) oauth.put("xaa", true);
        return oauth;
    }

    // ---------------------------------------------------------------------------
    // Supporting types
    // ---------------------------------------------------------------------------

    /**
     * All parameters parsed from a {@code mcp add} CLI invocation.
     *
     * @param name           MCP server name (required).
     * @param commandOrUrl   Command (stdio) or URL (sse/http) (required).
     * @param args           Additional positional args for stdio servers.
     * @param scope          Configuration scope: local | user | project.
     * @param transport      Transport type: stdio | sse | http.
     * @param env            Environment variable overrides ({@code KEY=value}).
     * @param headers        HTTP headers ({@code "Name: value"}).
     * @param clientId       OAuth client ID.
     * @param clientSecret   Whether to prompt for the OAuth client secret.
     * @param callbackPort   Fixed OAuth callback port.
     * @param xaa            Whether to enable XAA (SEP-990).
     */
    public record McpAddRequest(
        String name,
        String commandOrUrl,
        List<String> args,
        String scope,
        String transport,
        List<String> env,
        List<String> headers,
        String clientId,
        boolean clientSecret,
        String callbackPort,
        boolean xaa
    ) {}

    /**
     * Outcome of an {@code mcp add} invocation.
     *
     * @param success      Whether the server was added successfully.
     * @param message      Human-readable message to display / log.
     * @param configFile   Path of the modified config file (on success).
     * @param errorMessage Error detail (on failure).
     */
    public record McpAddResult(
        boolean success,
        String message,
        String configFile,
        String errorMessage
    ) {
        static McpAddResult ok(String message, String configFile) {
            return new McpAddResult(true, message, configFile, null);
        }

        static McpAddResult error(String errorMessage) {
            return new McpAddResult(false, null, null, errorMessage);
        }
    }

    /**
     * Minimal MCP server configuration used when persisting new entries.
     * The full model lives in McpConfigService / McpServerConfig.
     */
    public record McpServerConfig(
        String type,
        String url,
        String command,
        List<String> args,
        Map<String, String> env,
        Map<String, String> headers,
        Map<String, Object> oauth
    ) {
        static McpServerConfig sse(String url, Map<String, String> headers,
                                    Map<String, Object> oauth) {
            return new McpServerConfig("sse", url, null, null, null, headers, oauth);
        }

        static McpServerConfig http(String url, Map<String, String> headers,
                                     Map<String, Object> oauth) {
            return new McpServerConfig("http", url, null, null, null, headers, oauth);
        }

        static McpServerConfig stdio(String command, List<String> args,
                                      Map<String, String> env) {
            return new McpServerConfig("stdio", null, command, args, env, null, null);
        }
    }
}
