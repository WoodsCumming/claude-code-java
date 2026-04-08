package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;

/**
 * Bridge messaging service.
 * Translated from src/bridge/bridgeMessaging.ts
 *
 * Shared transport-layer helpers for bridge message handling. Extracted so
 * both env-based and env-less bridge cores can use the same ingress parsing,
 * control-request handling, and echo-dedup machinery.
 *
 * Everything here is pure — no closure over bridge-specific state. All
 * collaborators are passed as parameters.
 */
@Slf4j
@Service
public class BridgeMessagingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeMessagingService.class);


    private final ObjectMapper objectMapper;

    private static final String OUTBOUND_ONLY_ERROR =
        "This session is outbound-only. Enable Remote Control locally to allow inbound control.";

    @Autowired
    public BridgeMessagingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ─── Type predicates ──────────────────────────────────────────────────────

    /**
     * True if the map has a non-null string "type" field — i.e. looks like an SDK message.
     * Translated from isSDKMessage() in bridgeMessaging.ts
     */
    public boolean isSDKMessage(Map<String, Object> value) {
        return value != null && value.get("type") instanceof String;
    }

    /**
     * True for control_response messages from the server.
     * Translated from isSDKControlResponse() in bridgeMessaging.ts
     */
    public boolean isSDKControlResponse(Map<String, Object> value) {
        return value != null
                && "control_response".equals(value.get("type"))
                && value.containsKey("response");
    }

    /**
     * True for control_request messages from the server.
     * Translated from isSDKControlRequest() in bridgeMessaging.ts
     */
    public boolean isSDKControlRequest(Map<String, Object> value) {
        return value != null
                && "control_request".equals(value.get("type"))
                && value.containsKey("request_id")
                && value.containsKey("request");
    }

    // ─── Ingress routing ──────────────────────────────────────────────────────

    /**
     * Parse an ingress WebSocket message and route it to the appropriate handler.
     * Ignores messages whose UUID is in recentPostedUUIDs (echoes) or
     * recentInboundUUIDs (re-deliveries already forwarded).
     *
     * Translated from handleIngressMessage() in bridgeMessaging.ts
     */
    @SuppressWarnings("unchecked")
    public void handleIngressMessage(
            String data,
            BoundedUUIDSet recentPostedUUIDs,
            BoundedUUIDSet recentInboundUUIDs,
            Consumer<Map<String, Object>> onInboundMessage,
            Consumer<Map<String, Object>> onPermissionResponse,
            Consumer<Map<String, Object>> onControlRequest) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
            if (parsed == null) return;

            // control_response is not an SDKMessage — check before the type guard
            if (isSDKControlResponse(parsed)) {
                log.debug("[bridge:repl] Ingress message type=control_response");
                if (onPermissionResponse != null) onPermissionResponse.accept(parsed);
                return;
            }

            // control_request from the server (initialize, set_model, can_use_tool, etc.)
            if (isSDKControlRequest(parsed)) {
                Map<String, Object> request = (Map<String, Object>) parsed.get("request");
                String subtype = request != null ? (String) request.get("subtype") : null;
                log.debug("[bridge:repl] Inbound control_request subtype={}", subtype);
                if (onControlRequest != null) onControlRequest.accept(parsed);
                return;
            }

            if (!isSDKMessage(parsed)) return;

            // Check for UUID to detect echoes of our own messages
            Object uuidObj = parsed.get("uuid");
            String uuid = uuidObj instanceof String ? (String) uuidObj : null;

            if (uuid != null && recentPostedUUIDs.has(uuid)) {
                log.debug("[bridge:repl] Ignoring echo: type={} uuid={}", parsed.get("type"), uuid);
                return;
            }

            // Defensive dedup: drop inbound prompts we've already forwarded
            if (uuid != null && recentInboundUUIDs.has(uuid)) {
                log.debug("[bridge:repl] Ignoring re-delivered inbound: type={} uuid={}", parsed.get("type"), uuid);
                return;
            }

            log.debug("[bridge:repl] Ingress message type={}{}", parsed.get("type"),
                    uuid != null ? " uuid=" + uuid : "");

            if ("user".equals(parsed.get("type"))) {
                if (uuid != null) recentInboundUUIDs.add(uuid);
                if (onInboundMessage != null) onInboundMessage.accept(parsed);
            } else {
                log.debug("[bridge:repl] Ignoring non-user inbound message: type={}", parsed.get("type"));
            }
        } catch (Exception err) {
            log.debug("[bridge:repl] Failed to parse ingress message: {}", err.getMessage());
        }
    }

    // ─── Server-initiated control request handling ────────────────────────────

    /**
     * Handlers wired into handleServerControlRequest.
     * Translated from ServerControlRequestHandlers in bridgeMessaging.ts
     */
    public static class ServerControlRequestHandlers {
        /** Interface for sending a response back over the transport. */
        public interface Transport {
            void write(Map<String, Object> event);
        }

        public Transport transport;
        public String sessionId;
        /**
         * When true, all mutable requests reply with an error instead of false-success.
         * initialize still replies success — the server kills the connection otherwise.
         */
        public boolean outboundOnly;
        public Runnable onInterrupt;
        public Consumer<String> onSetModel;
        public Consumer<Integer> onSetMaxThinkingTokens;
        /** Returns ok=true on success, or ok=false with an error message. */
        public java.util.function.Function<String, PermissionModeVerdict> onSetPermissionMode;

        public record PermissionModeVerdict(boolean ok, String error) {}
    }

    /**
     * Respond to inbound control_request messages from the server.
     * The server sends these for session lifecycle events (initialize, set_model)
     * and turn-level coordination (interrupt, set_max_thinking_tokens).
     * If we don't respond, the server hangs and kills the WS after ~10-14s.
     *
     * Translated from handleServerControlRequest() in bridgeMessaging.ts
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void handleServerControlRequest(
            Map<String, Object> request,
            ServerControlRequestHandlers handlers) {

        if (handlers.transport == null) {
            log.debug("[bridge:repl] Cannot respond to control_request: transport not configured");
            return;
        }

        String requestId = (String) request.get("request_id");
        Map<String, Object> requestPayload = (Map<String, Object>) request.get("request");
        String subtype = requestPayload != null ? (String) requestPayload.get("subtype") : null;

        Map<String, Object> responsePayload;

        // Outbound-only: reply error for mutable requests
        if (handlers.outboundOnly && !"initialize".equals(subtype)) {
            responsePayload = new HashMap<>();
            responsePayload.put("subtype", "error");
            responsePayload.put("request_id", requestId);
            responsePayload.put("error", OUTBOUND_ONLY_ERROR);

            Map<String, Object> event = new HashMap<>();
            event.put("type", "control_response");
            event.put("response", responsePayload);
            event.put("session_id", handlers.sessionId);
            handlers.transport.write(event);
            log.debug("[bridge:repl] Rejected {} (outbound-only) request_id={}", subtype, requestId);
            return;
        }

        switch (subtype != null ? subtype : "") {
            case "initialize" -> {
                // Respond with minimal capabilities
                Map<String, Object> initResponse = new LinkedHashMap<>();
                initResponse.put("commands", Collections.emptyList());
                initResponse.put("output_style", "normal");
                initResponse.put("available_output_styles", List.of("normal"));
                initResponse.put("models", Collections.emptyList());
                initResponse.put("account", Collections.emptyMap());
                initResponse.put("pid", ProcessHandle.current().pid());

                responsePayload = new LinkedHashMap<>();
                responsePayload.put("subtype", "success");
                responsePayload.put("request_id", requestId);
                responsePayload.put("response", initResponse);
            }
            case "set_model" -> {
                if (handlers.onSetModel != null) {
                    handlers.onSetModel.accept((String) requestPayload.get("model"));
                }
                responsePayload = Map.of("subtype", "success", "request_id", requestId);
            }
            case "set_max_thinking_tokens" -> {
                if (handlers.onSetMaxThinkingTokens != null) {
                    Object maxTokens = requestPayload.get("max_thinking_tokens");
                    handlers.onSetMaxThinkingTokens.accept(
                            maxTokens instanceof Number n ? n.intValue() : null);
                }
                responsePayload = Map.of("subtype", "success", "request_id", requestId);
            }
            case "set_permission_mode" -> {
                String mode = (String) requestPayload.get("mode");
                ServerControlRequestHandlers.PermissionModeVerdict verdict;
                if (handlers.onSetPermissionMode != null) {
                    verdict = handlers.onSetPermissionMode.apply(mode);
                } else {
                    verdict = new ServerControlRequestHandlers.PermissionModeVerdict(false,
                            "set_permission_mode is not supported in this context (onSetPermissionMode callback not registered)");
                }
                if (verdict.ok()) {
                    responsePayload = Map.of("subtype", "success", "request_id", requestId);
                } else {
                    responsePayload = Map.of("subtype", "error", "request_id", requestId,
                            "error", verdict.error());
                }
            }
            case "interrupt" -> {
                if (handlers.onInterrupt != null) handlers.onInterrupt.run();
                responsePayload = Map.of("subtype", "success", "request_id", requestId);
            }
            default -> {
                // Unknown subtype — respond with error so the server doesn't hang
                responsePayload = Map.of(
                        "subtype", "error",
                        "request_id", requestId,
                        "error", "REPL bridge does not handle control_request subtype: " + subtype);
            }
        }

        Map<String, Object> event = new HashMap<>(Map.of(
                "type", "control_response",
                "response", responsePayload,
                "session_id", handlers.sessionId));
        handlers.transport.write(event);
        log.debug("[bridge:repl] Sent control_response for {} request_id={} result={}",
                subtype, requestId, responsePayload.get("subtype"));
    }

    // ─── Result message ───────────────────────────────────────────────────────

    /**
     * Build a minimal result/success message for session archival on teardown.
     * The server needs this event before a WS close to trigger archival.
     *
     * Translated from makeResultMessage() in bridgeMessaging.ts
     */
    public Map<String, Object> makeResultMessage(String sessionId) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
        usage.put("cache_creation_input_tokens", 0);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "result");
        msg.put("subtype", "success");
        msg.put("duration_ms", 0);
        msg.put("duration_api_ms", 0);
        msg.put("is_error", false);
        msg.put("num_turns", 0);
        msg.put("result", "");
        msg.put("stop_reason", null);
        msg.put("total_cost_usd", 0.0);
        msg.put("usage", usage);
        msg.put("modelUsage", Collections.emptyMap());
        msg.put("permission_denials", Collections.emptyList());
        msg.put("session_id", sessionId);
        msg.put("uuid", UUID.randomUUID().toString());
        return msg;
    }

    // ─── Parse / serialize helpers ────────────────────────────────────────────

    /**
     * Parse a WebSocket message from JSON string into a Map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseMessage(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse bridge message: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Serialize a message object to a JSON string for sending over bridge.
     */
    public String serializeMessage(Object message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Failed to serialize bridge message: {}", e.getMessage());
            return null;
        }
    }

    // ─── BoundedUUIDSet (echo-dedup ring buffer) ──────────────────────────────

    /**
     * FIFO-bounded set backed by a circular buffer. Evicts the oldest entry when
     * capacity is reached, keeping memory usage constant at O(capacity).
     *
     * Translated from BoundedUUIDSet in bridgeMessaging.ts
     */
    public static class BoundedUUIDSet {
        private final int capacity;
        private final String[] ring;
        private final Set<String> set = new HashSet<>();
        private int writeIdx = 0;

        public BoundedUUIDSet(int capacity) {
            this.capacity = capacity;
            this.ring = new String[capacity];
        }

        public void add(String uuid) {
            if (set.contains(uuid)) return;
            // Evict the entry at the current write position (if occupied)
            String evicted = ring[writeIdx];
            if (evicted != null) set.remove(evicted);
            ring[writeIdx] = uuid;
            set.add(uuid);
            writeIdx = (writeIdx + 1) % capacity;
        }

        public boolean has(String uuid) {
            return set.contains(uuid);
        }

        public void clear() {
            set.clear();
            Arrays.fill(ring, null);
            writeIdx = 0;
        }
    }
}
