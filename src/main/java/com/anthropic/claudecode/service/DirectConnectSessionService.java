package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Direct connect session service.
 * Translated from src/server/createDirectConnectSession.ts and
 * src/server/directConnectManager.ts
 *
 * Creates and manages WebSocket connections to direct-connect Claude Code server sessions.
 */
@Slf4j
@Service
public class DirectConnectSessionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DirectConnectSessionService.class);


    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Autowired
    public DirectConnectSessionService(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Configuration for a direct-connect session.
     * Translated from DirectConnectConfig in directConnectManager.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class DirectConnectConfig {
        private final String serverUrl;
        private final String sessionId;
        private final String wsUrl;
        private final String authToken; // nullable

        public String getServerUrl() { return serverUrl; }
        public String getSessionId() { return sessionId; }
        public String getWsUrl() { return wsUrl; }
        public String getAuthToken() { return authToken; }
    
    }

    /**
     * Callbacks for a direct-connect session.
     * Translated from DirectConnectCallbacks in directConnectManager.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class DirectConnectCallbacks {
        private final Consumer<Map<String, Object>> onMessage;
        private final BiConsumer<Map<String, Object>, String> onPermissionRequest;
        private Runnable onConnected;
        private Runnable onDisconnected;
        private Consumer<Exception> onError;

        public Consumer<Map<String, Object>> getOnMessage() { return onMessage; }
        public BiConsumer<Map<String, Object>, String> getOnPermissionRequest() { return onPermissionRequest; }
        public Runnable getOnConnected() { return onConnected; }
        public void setOnConnected(Runnable v) { onConnected = v; }
        public Runnable getOnDisconnected() { return onDisconnected; }
        public void setOnDisconnected(Runnable v) { onDisconnected = v; }
        public Consumer<Exception> getOnError() { return onError; }
        public void setOnError(Consumer<Exception> v) { onError = v; }
    }

    /**
     * Exception thrown when a direct connect session creation fails.
     * Translated from DirectConnectError in createDirectConnectSession.ts
     */
    public static class DirectConnectError extends RuntimeException {
        public DirectConnectError(String message) {
            super(message);
        }
    }

    /**
     * Result of {@link #createSession}.
     */
    public record CreateSessionResult(DirectConnectConfig config, String workDir) {}

    // =========================================================================
    // Session creation (createDirectConnectSession.ts)
    // =========================================================================

    /**
     * Create a session on a direct-connect server.
     * Posts to {@code ${serverUrl}/sessions}, validates the response, and returns
     * a DirectConnectConfig ready for use.
     *
     * Translated from createDirectConnectSession() in createDirectConnectSession.ts
     *
     * @throws DirectConnectError on network, HTTP, or response-parsing failures.
     */
    public CompletableFuture<CreateSessionResult> createSession(
            String serverUrl,
            String authToken,
            String cwd,
            boolean dangerouslySkipPermissions) {

        return CompletableFuture.supplyAsync(() -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (authToken != null && !authToken.isBlank()) {
                headers.set("Authorization", "Bearer " + authToken);
            }

            Map<String, Object> body = dangerouslySkipPermissions
                    ? Map.of("cwd", cwd, "dangerously_skip_permissions", true)
                    : Map.of("cwd", cwd);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response;
            try {
                response = restTemplate.postForEntity(serverUrl + "/sessions", request, Map.class);
            } catch (Exception e) {
                throw new DirectConnectError(
                        "Failed to connect to server at " + serverUrl + ": " + e.getMessage());
            }

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DirectConnectError(
                        "Failed to create session: " + response.getStatusCode());
            }

            Map<String, Object> data = response.getBody();
            String sessionId = (String) data.get("session_id");
            String wsUrl = (String) data.get("ws_url");
            String workDir = (String) data.get("work_dir");

            if (sessionId == null || wsUrl == null) {
                throw new DirectConnectError("Invalid session response: missing session_id or ws_url");
            }

            DirectConnectConfig config = new DirectConnectConfig(serverUrl, sessionId, wsUrl, authToken);
            return new CreateSessionResult(config, workDir);
        });
    }

    // =========================================================================
    // Session manager (directConnectManager.ts)
    // =========================================================================

    /**
     * Manages a single direct-connect WebSocket session.
     * Translated from DirectConnectSessionManager in directConnectManager.ts
     */
    public static class DirectConnectSessionManager {

        private final DirectConnectConfig config;
        private final DirectConnectCallbacks callbacks;
        private volatile boolean connected = false;

        public DirectConnectSessionManager(DirectConnectConfig config, DirectConnectCallbacks callbacks) {
            this.config = config;
            this.callbacks = callbacks;
        }

        /**
         * Connect to the session via WebSocket.
         * Translated from connect() in directConnectManager.ts
         */
        public void connect() {
            log.debug("[DirectConnect] Connecting to: {}", config.getWsUrl());
            // In production: create WebSocket, set auth header if authToken present,
            // register open/message/close/error event listeners.
            connected = true;
            if (callbacks.getOnConnected() != null) callbacks.getOnConnected().run();
        }

        /**
         * Send a user message to the session.
         * Translated from sendMessage() in directConnectManager.ts
         * Must match SDKUserMessage format expected by --input-format stream-json.
         */
        public boolean sendMessage(Object content) {
            if (!connected) return false;
            try {
                Map<String, Object> payload = Map.of(
                        "type", "user",
                        "message", Map.of("role", "user", "content", content),
                        "parent_tool_use_id", (Object) null,
                        "session_id", ""
                );
                // In production: ws.send(objectMapper.writeValueAsString(payload))
                log.debug("[DirectConnect] Sending message to {}", config.getSessionId());
                return true;
            } catch (Exception e) {
                log.error("[DirectConnect] Failed to send message: {}", e.getMessage());
                return false;
            }
        }

        /**
         * Respond to a permission request.
         * Translated from respondToPermissionRequest() in directConnectManager.ts
         * Must match SDKControlResponse format expected by StructuredIO.
         */
        public void respondToPermissionRequest(
                String requestId,
                com.anthropic.claudecode.service.RemoteSessionManagerService.RemotePermissionResponse result) {
            if (!connected) return;

            Map<String, Object> responseBody = result instanceof
                    com.anthropic.claudecode.service.RemoteSessionManagerService.RemotePermissionResponse.Allow a
                    ? Map.of("behavior", "allow", "updatedInput", a.updatedInput())
                    : Map.of("behavior", "deny", "message",
                            ((com.anthropic.claudecode.service.RemoteSessionManagerService.RemotePermissionResponse.Deny) result).message());

            Map<String, Object> payload = Map.of(
                    "type", "control_response",
                    "response", Map.of(
                            "subtype", "success",
                            "request_id", requestId,
                            "response", responseBody
                    )
            );
            // In production: ws.send(objectMapper.writeValueAsString(payload))
            log.debug("[DirectConnect] Sending permission response for {}", requestId);
        }

        /**
         * Send an interrupt signal to cancel the current request.
         * Translated from sendInterrupt() in directConnectManager.ts
         */
        public void sendInterrupt() {
            if (!connected) return;
            Map<String, Object> payload = Map.of(
                    "type", "control_request",
                    "request_id", java.util.UUID.randomUUID().toString(),
                    "request", Map.of("subtype", "interrupt")
            );
            // In production: ws.send(objectMapper.writeValueAsString(payload))
            log.debug("[DirectConnect] Sending interrupt");
        }

        /**
         * Disconnect from the session.
         * Translated from disconnect() in directConnectManager.ts
         */
        public void disconnect() {
            if (connected) {
                connected = false;
                log.debug("[DirectConnect] Disconnected from: {}", config.getSessionId());
            }
        }

        /**
         * Check if the WebSocket is open.
         * Translated from isConnected() in directConnectManager.ts
         */
        public boolean isConnected() {
            return connected;
        }

        /**
         * Handle a parsed WebSocket message line.
         * Translated from the ws.addEventListener('message',...) handler in directConnectManager.ts
         */
        @SuppressWarnings("unchecked")
        public void handleParsedMessage(Map<String, Object> parsed) {
            String type = (String) parsed.get("type");

            if ("control_request".equals(type)) {
                Map<String, Object> innerRequest = (Map<String, Object>) parsed.get("request");
                String subtype = innerRequest != null ? (String) innerRequest.get("subtype") : null;
                if ("can_use_tool".equals(subtype)) {
                    String requestId = (String) parsed.get("request_id");
                    callbacks.getOnPermissionRequest().accept(innerRequest, requestId);
                } else {
                    log.debug("[DirectConnect] Unsupported control request subtype: {}", subtype);
                    sendErrorResponse((String) parsed.get("request_id"),
                            "Unsupported control request subtype: " + subtype);
                }
                return;
            }

            // Forward SDK messages; skip keep-alive and server-internal types
            if (!"control_response".equals(type)
                    && !"keep_alive".equals(type)
                    && !"control_cancel_request".equals(type)
                    && !"streamlined_text".equals(type)
                    && !"streamlined_tool_use_summary".equals(type)
                    && !("system".equals(type) && "post_turn_summary".equals(parsed.get("subtype")))) {
                callbacks.getOnMessage().accept(parsed);
            }
        }

        private void sendErrorResponse(String requestId, String error) {
            Map<String, Object> payload = Map.of(
                    "type", "control_response",
                    "response", Map.of("subtype", "error", "request_id", requestId, "error", error)
            );
            // In production: ws.send(objectMapper.writeValueAsString(payload))
            log.debug("[DirectConnect] Sending error response for {}: {}", requestId, error);
        }
    }

    // =========================================================================
    // Factory
    // =========================================================================

    /**
     * Convenience factory: create a configured DirectConnectSessionManager.
     */
    public DirectConnectSessionManager buildSessionManager(
            DirectConnectConfig config,
            DirectConnectCallbacks callbacks) {
        return new DirectConnectSessionManager(config, callbacks);
    }
}
