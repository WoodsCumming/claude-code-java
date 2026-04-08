package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages remote CCR sessions.
 * Translated from src/remote/RemoteSessionManager.ts
 *
 * Coordinates:
 * - WebSocket subscription for receiving messages from CCR
 * - HTTP POST for sending user messages to CCR
 * - Permission request/response flow
 */
@Slf4j
@Service
public class RemoteSessionManagerService {



    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Sealed interface for RemotePermissionResponse.
     * Translated from the union type RemotePermissionResponse in RemoteSessionManager.ts
     */
    public sealed interface RemotePermissionResponse
            permits RemotePermissionResponse.Allow, RemotePermissionResponse.Deny {

        record Allow(Map<String, Object> updatedInput) implements RemotePermissionResponse {
            public String behavior() { return "allow"; }
        }

        record Deny(String message) implements RemotePermissionResponse {
            public String behavior() { return "deny"; }
        }
    }

    /**
     * Configuration for a remote session.
     * Translated from RemoteSessionConfig in RemoteSessionManager.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class RemoteSessionConfig {
        private final String sessionId;
        private final Supplier<String> getAccessToken;
        private final String orgUuid;
        private final boolean hasInitialPrompt;
        /** When true, this client is a pure viewer. Ctrl+C/Escape do NOT send
         *  interrupt to the remote agent. Used by {@code claude assistant}. */
        private final boolean viewerOnly;

        public String getSessionId() { return sessionId; }
        public Supplier<String> getGetAccessToken() { return getAccessToken; }
        public String getOrgUuid() { return orgUuid; }
        public boolean isHasInitialPrompt() { return hasInitialPrompt; }
        public boolean isViewerOnly() { return viewerOnly; }
    
    }

    /**
     * Callbacks for a remote session.
     * Translated from RemoteSessionCallbacks in RemoteSessionManager.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class RemoteSessionCallbacks {
        /** Called when an SDK message is received from the session. */
        private final Consumer<Map<String, Object>> onMessage;
        /** Called when a permission request is received from CCR. */
        private final BiConsumer<Map<String, Object>, String> onPermissionRequest;
        /** Called when the server cancels a pending permission request. */
        private BiConsumer<String, String> onPermissionCancelled;
        /** Called when connection is established. */
        private Runnable onConnected;
        /** Called when connection is lost and cannot be restored. */
        private Runnable onDisconnected;
        /** Called on transient WS drop while reconnect backoff is in progress. */
        private Runnable onReconnecting;
        /** Called on error. */
        private Consumer<Exception> onError;

        public Consumer<Map<String, Object>> getOnMessage() { return onMessage; }
        public BiConsumer<Map<String, Object>, String> getOnPermissionRequest() { return onPermissionRequest; }
        public BiConsumer<String, String> getOnPermissionCancelled() { return onPermissionCancelled; }
        public void setOnPermissionCancelled(BiConsumer<String, String> v) { onPermissionCancelled = v; }
        public Runnable getOnConnected() { return onConnected; }
        public void setOnConnected(Runnable v) { onConnected = v; }
        public Runnable getOnDisconnected() { return onDisconnected; }
        public void setOnDisconnected(Runnable v) { onDisconnected = v; }
        public Runnable getOnReconnecting() { return onReconnecting; }
        public void setOnReconnecting(Runnable v) { onReconnecting = v; }
        public Consumer<Exception> getOnError() { return onError; }
        public void setOnError(Consumer<Exception> v) { onError = v; }
    }

    // =========================================================================
    // RemoteSessionManager inner class
    // (mirrors the TS class; one instance per active session)
    // =========================================================================

    /**
     * Manages a single remote CCR session.
     * Translated from the RemoteSessionManager class in RemoteSessionManager.ts
     */
    public static class RemoteSessionManager {

        private final RemoteSessionConfig config;
        private final RemoteSessionCallbacks callbacks;
        /** Pending permission requests keyed by request_id. */
        private final Map<String, Map<String, Object>> pendingPermissionRequests =
                new ConcurrentHashMap<>();
        private volatile boolean connected = false;

        public RemoteSessionManager(RemoteSessionConfig config, RemoteSessionCallbacks callbacks) {
            this.config = config;
            this.callbacks = callbacks;
        }

        /**
         * Connect to the remote session via WebSocket.
         * Translated from connect() in RemoteSessionManager.ts
         */
        public void connect() {
            log.debug("[RemoteSessionManager] Connecting to session {}", config.getSessionId());
            // In production: create SessionsWebSocket, register callbacks, call connect().
            connected = true;
            if (callbacks.getOnConnected() != null) {
                callbacks.getOnConnected().run();
            }
        }

        /**
         * Handle messages from WebSocket.
         * Translated from handleMessage() in RemoteSessionManager.ts
         */
        public void handleMessage(Map<String, Object> message) {
            String type = (String) message.get("type");

            if ("control_request".equals(type)) {
                handleControlRequest(message);
                return;
            }

            if ("control_cancel_request".equals(type)) {
                String requestId = (String) message.get("request_id");
                Map<String, Object> pending = pendingPermissionRequests.remove(requestId);
                log.debug("[RemoteSessionManager] Permission request cancelled: {}", requestId);
                if (callbacks.getOnPermissionCancelled() != null) {
                    String toolUseId = pending != null ? (String) pending.get("tool_use_id") : null;
                    callbacks.getOnPermissionCancelled().accept(requestId, toolUseId);
                }
                return;
            }

            if ("control_response".equals(type)) {
                log.debug("[RemoteSessionManager] Received control response");
                return;
            }

            // Forward SDK messages
            if (isSDKMessage(message)) {
                callbacks.getOnMessage().accept(message);
            }
        }

        /**
         * Handle control requests from CCR (e.g., permission requests).
         * Translated from handleControlRequest() in RemoteSessionManager.ts
         */
        @SuppressWarnings("unchecked")
        private void handleControlRequest(Map<String, Object> request) {
            String requestId = (String) request.get("request_id");
            Map<String, Object> inner = (Map<String, Object>) request.get("request");
            String subtype = inner != null ? (String) inner.get("subtype") : null;

            if ("can_use_tool".equals(subtype)) {
                log.debug("[RemoteSessionManager] Permission request for tool: {}",
                        inner.get("tool_name"));
                pendingPermissionRequests.put(requestId, inner);
                callbacks.getOnPermissionRequest().accept(inner, requestId);
            } else {
                log.debug("[RemoteSessionManager] Unsupported control request subtype: {}", subtype);
                // Send error response so the server does not hang
                sendControlErrorResponse(requestId,
                        "Unsupported control request subtype: " + subtype);
            }
        }

        /**
         * Send a user message to the remote session via HTTP POST.
         * Translated from sendMessage() in RemoteSessionManager.ts
         */
        public CompletableFuture<Boolean> sendMessage(Object content, String uuid) {
            log.debug("[RemoteSessionManager] Sending message to session {}", config.getSessionId());
            // In production: POST to teleport API with content + uuid
            return CompletableFuture.completedFuture(true);
        }

        /**
         * Respond to a permission request from CCR.
         * Translated from respondToPermissionRequest() in RemoteSessionManager.ts
         */
        public void respondToPermissionRequest(String requestId, RemotePermissionResponse result) {
            Map<String, Object> pending = pendingPermissionRequests.remove(requestId);
            if (pending == null) {
                log.error("[RemoteSessionManager] No pending permission request with ID: {}",
                        requestId);
                return;
            }

            log.debug("[RemoteSessionManager] Sending permission response: {}",
                    result instanceof RemotePermissionResponse.Allow ? "allow" : "deny");
            // In production: send control_response over WebSocket
        }

        /**
         * Check if connected to the remote session.
         * Translated from isConnected() in RemoteSessionManager.ts
         */
        public boolean isConnected() {
            return connected;
        }

        /**
         * Send an interrupt signal to cancel the current request on the remote session.
         * Translated from cancelSession() in RemoteSessionManager.ts
         */
        public void cancelSession() {
            log.debug("[RemoteSessionManager] Sending interrupt signal");
            // In production: websocket.sendControlRequest({ subtype: 'interrupt' })
        }

        /**
         * Get the session ID.
         */
        public String getSessionId() {
            return config.getSessionId();
        }

        /**
         * Disconnect from the remote session.
         * Translated from disconnect() in RemoteSessionManager.ts
         */
        public void disconnect() {
            log.debug("[RemoteSessionManager] Disconnecting");
            connected = false;
            pendingPermissionRequests.clear();
        }

        /**
         * Force reconnect the WebSocket.
         * Translated from reconnect() in RemoteSessionManager.ts
         */
        public void reconnect() {
            log.debug("[RemoteSessionManager] Reconnecting WebSocket");
            // In production: websocket.reconnect()
        }

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        /**
         * Type guard: true if message is an SDK message (not a control message).
         * Translated from isSDKMessage() in RemoteSessionManager.ts
         */
        private static boolean isSDKMessage(Map<String, Object> message) {
            String type = (String) message.get("type");
            return !"control_request".equals(type)
                    && !"control_response".equals(type)
                    && !"control_cancel_request".equals(type);
        }

        private void sendControlErrorResponse(String requestId, String error) {
            // In production: websocket.sendControlResponse(...)
            log.debug("[RemoteSessionManager] Sending error control response for {}: {}",
                    requestId, error);
        }
    }

    // =========================================================================
    // Factory / service-level helpers
    // =========================================================================

    /**
     * Create a remote session config.
     * Translated from createRemoteSessionConfig() in RemoteSessionManager.ts
     */
    public static RemoteSessionConfig createRemoteSessionConfig(
            String sessionId,
            Supplier<String> getAccessToken,
            String orgUuid,
            boolean hasInitialPrompt,
            boolean viewerOnly) {
        return new RemoteSessionConfig(sessionId, getAccessToken, orgUuid,
                hasInitialPrompt, viewerOnly);
    }
}
