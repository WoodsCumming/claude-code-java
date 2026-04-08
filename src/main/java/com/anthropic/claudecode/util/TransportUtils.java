package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;

/**
 * Transport utility helpers.
 * Translated from src/cli/transports/transportUtils.ts
 *
 * Provides factory logic for selecting the correct transport implementation
 * based on the session URL and environment variables.
 *
 * Transport selection priority:
 * <ol>
 *   <li>SSE transport (SSE reads + POST writes) when {@code CLAUDE_CODE_USE_CCR_V2} is set</li>
 *   <li>Hybrid transport (WS reads + POST writes) when {@code CLAUDE_CODE_POST_FOR_SESSION_INGRESS_V2} is set</li>
 *   <li>WebSocket transport (WS reads + WS writes) — default</li>
 * </ol>
 */
@Slf4j
public final class TransportUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TransportUtils.class);


    private TransportUtils() {}

    /**
     * Sealed interface representing a resolved transport descriptor.
     * Callers pattern-match on the variant to construct the actual transport.
     * Translated from the return-type logic of getTransportForUrl() in transportUtils.ts
     */
    public sealed interface TransportDescriptor
            permits TransportDescriptor.SseDescriptor,
                    TransportDescriptor.WebSocketDescriptor {

        /** Use SSE for reading and HTTP POST for writing (CCR v2). */
        record SseDescriptor(URL sseUrl, Map<String, String> headers, String sessionId,
                             java.util.function.Supplier<Map<String, String>> refreshHeaders)
                implements TransportDescriptor {}

        /** Use WebSocket for both reading and writing. */
        record WebSocketDescriptor(URL wsUrl, Map<String, String> headers, String sessionId,
                                   java.util.function.Supplier<Map<String, String>> refreshHeaders,
                                   boolean useHybrid)
                implements TransportDescriptor {}
    }

    /**
     * Determine the appropriate transport type for the given session URL.
     *
     * @param url            session URL (ws:, wss:, http:, or https:)
     * @param headers        initial request headers
     * @param sessionId      optional session identifier
     * @param refreshHeaders optional supplier that returns refreshed auth headers
     * @return a {@link TransportDescriptor} whose variant indicates which transport to use
     * @throws IllegalArgumentException if the URL protocol is unsupported
     */
    public static TransportDescriptor getTransportForUrl(
            URL url,
            Map<String, String> headers,
            String sessionId,
            java.util.function.Supplier<Map<String, String>> refreshHeaders) {

        boolean useCcrV2 = isEnvTruthy(System.getenv("CLAUDE_CODE_USE_CCR_V2"));

        if (useCcrV2) {
            // v2: SSE for reads, HTTP POST for writes.
            // Derive the SSE stream URL by appending /worker/events/stream to the session URL.
            try {
                String protocol = url.getProtocol();
                if ("wss".equals(protocol)) {
                    protocol = "https";
                } else if ("ws".equals(protocol)) {
                    protocol = "http";
                }
                String path = url.getPath().replaceAll("/$", "") + "/worker/events/stream";
                int port = url.getPort();
                String portStr = port == -1 ? "" : ":" + port;
                URL sseUrl = new URL(protocol + "://" + url.getHost() + portStr + path);
                return new TransportDescriptor.SseDescriptor(sseUrl, headers, sessionId, refreshHeaders);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Failed to build SSE URL from: " + url, e);
            }
        }

        String protocol = url.getProtocol();
        if ("ws".equals(protocol) || "wss".equals(protocol)) {
            boolean useHybrid = isEnvTruthy(System.getenv("CLAUDE_CODE_POST_FOR_SESSION_INGRESS_V2"));
            return new TransportDescriptor.WebSocketDescriptor(url, headers, sessionId, refreshHeaders, useHybrid);
        }

        throw new IllegalArgumentException("Unsupported protocol: " + protocol);
    }

    // ---------------------------------------------------------------------------
    // Utility helpers
    // ---------------------------------------------------------------------------

    /**
     * Return {@code true} if the given environment variable value is truthy
     * (non-null, non-empty, not {@code "false"}, not {@code "0"}).
     * Translated from isEnvTruthy() in envUtils.ts
     */
    public static boolean isEnvTruthy(String value) {
        if (value == null || value.isBlank()) return false;
        return !"false".equalsIgnoreCase(value) && !"0".equals(value.trim());
    }

    /**
     * Return current session ingress auth headers.
     * Falls back to an empty map when no token is available.
     * Delegates to {@link SessionIngressAuth}.
     */
    public static Map<String, String> getSessionIngressAuthHeaders() {
        Optional<String> token = Optional.ofNullable(SessionIngressAuth.getSessionIngressAuthToken());
        return token.map(t -> Map.of("Authorization", "Bearer " + t))
                    .orElse(Map.of());
    }

    /**
     * Return the {@code User-Agent} string used by Claude Code HTTP requests.
     * Translated from getClaudeCodeUserAgent() in userAgent.ts
     */
    public static String getClaudeCodeUserAgent() {
        String version = Optional.ofNullable(System.getenv("CLAUDE_CODE_VERSION"))
                                 .orElse("unknown");
        return "ClaudeCode/" + version + " Java";
    }
}
