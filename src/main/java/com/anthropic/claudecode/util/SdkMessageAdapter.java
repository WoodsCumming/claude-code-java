package com.anthropic.claudecode.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Converts SDK messages from CCR to REPL message types.
 * Translated from src/remote/sdkMessageAdapter.ts
 *
 * The CCR backend sends SDK-format messages via WebSocket. The REPL expects
 * internal Message types for rendering. This adapter bridges the two.
 */
@Slf4j
@UtilityClass
public class SdkMessageAdapter {



    // =========================================================================
    // Result type
    // =========================================================================

    /**
     * Result of converting an SDKMessage.
     * Translated from the ConvertedMessage union type in sdkMessageAdapter.ts
     */
    public sealed interface ConvertedMessage
            permits ConvertedMessage.MessageResult,
                    ConvertedMessage.StreamEventResult,
                    ConvertedMessage.Ignored {

        record MessageResult(Map<String, Object> message) implements ConvertedMessage {}
        record StreamEventResult(Map<String, Object> event) implements ConvertedMessage {}
        record Ignored() implements ConvertedMessage {}
    }

    /**
     * Options for convertSDKMessage().
     * Translated from ConvertOptions in sdkMessageAdapter.ts
     */
    public record ConvertOptions(
            /** Convert user messages containing tool_result blocks into UserMessages. */
            boolean convertToolResults,
            /** Convert user text messages into UserMessages for display. */
            boolean convertUserTextMessages
    ) {
        public static final ConvertOptions DEFAULT = new ConvertOptions(false, false);
    }

    // =========================================================================
    // Primary conversion entry point
    // =========================================================================

    /**
     * Convert an SDKMessage to REPL message format.
     * Translated from convertSDKMessage() in sdkMessageAdapter.ts
     */
    @SuppressWarnings("unchecked")
    public ConvertedMessage convertSDKMessage(Map<String, Object> msg, ConvertOptions opts) {
        if (msg == null) return new ConvertedMessage.Ignored();
        ConvertOptions options = opts != null ? opts : ConvertOptions.DEFAULT;

        String type = (String) msg.get("type");
        return switch (type != null ? type : "") {
            case "assistant" -> new ConvertedMessage.MessageResult(convertAssistantMessage(msg));

            case "user" -> {
                Object content = getNestedContent(msg);
                boolean isToolResult = isToolResultContent(content);

                if (options.convertToolResults() && isToolResult) {
                    yield new ConvertedMessage.MessageResult(createUserMessage(msg));
                }
                if (options.convertUserTextMessages() && !isToolResult) {
                    if (content instanceof String || content instanceof List) {
                        yield new ConvertedMessage.MessageResult(createUserMessage(msg));
                    }
                }
                yield new ConvertedMessage.Ignored();
            }

            case "stream_event" -> new ConvertedMessage.StreamEventResult(convertStreamEvent(msg));

            case "result" -> {
                String subtype = (String) msg.get("subtype");
                if (!"success".equals(subtype)) {
                    yield new ConvertedMessage.MessageResult(convertResultMessage(msg));
                }
                yield new ConvertedMessage.Ignored();
            }

            case "system" -> {
                String subtype = (String) msg.get("subtype");
                if ("init".equals(subtype)) {
                    yield new ConvertedMessage.MessageResult(convertInitMessage(msg));
                }
                if ("status".equals(subtype)) {
                    Map<String, Object> statusMsg = convertStatusMessage(msg);
                    yield statusMsg != null
                            ? new ConvertedMessage.MessageResult(statusMsg)
                            : new ConvertedMessage.Ignored();
                }
                if ("compact_boundary".equals(subtype)) {
                    yield new ConvertedMessage.MessageResult(convertCompactBoundaryMessage(msg));
                }
                // hook_response and other subtypes
                log.debug("[sdkMessageAdapter] Ignoring system message subtype: {}", subtype);
                yield new ConvertedMessage.Ignored();
            }

            case "tool_progress" ->
                    new ConvertedMessage.MessageResult(convertToolProgressMessage(msg));

            case "auth_status" -> {
                log.debug("[sdkMessageAdapter] Ignoring auth_status message");
                yield new ConvertedMessage.Ignored();
            }

            case "tool_use_summary" -> {
                log.debug("[sdkMessageAdapter] Ignoring tool_use_summary message");
                yield new ConvertedMessage.Ignored();
            }

            case "rate_limit_event" -> {
                log.debug("[sdkMessageAdapter] Ignoring rate_limit_event message");
                yield new ConvertedMessage.Ignored();
            }

            default -> {
                log.debug("[sdkMessageAdapter] Unknown message type: {}", type);
                yield new ConvertedMessage.Ignored();
            }
        };
    }

    // =========================================================================
    // Typed conversion helpers
    // =========================================================================

    /**
     * Convert an SDKAssistantMessage to an AssistantMessage.
     * Translated from convertAssistantMessage() in sdkMessageAdapter.ts
     */
    private Map<String, Object> convertAssistantMessage(Map<String, Object> msg) {
        return Map.of(
                "type", "assistant",
                "message", msg.getOrDefault("message", Map.of()),
                "uuid", msg.getOrDefault("uuid", ""),
                "timestamp", Instant.now().toString(),
                "error", msg.getOrDefault("error", "")
        );
    }

    /**
     * Convert an SDKPartialAssistantMessage (streaming) to a StreamEvent.
     * Translated from convertStreamEvent() in sdkMessageAdapter.ts
     */
    private Map<String, Object> convertStreamEvent(Map<String, Object> msg) {
        return Map.of(
                "type", "stream_event",
                "event", msg.getOrDefault("event", Map.of())
        );
    }

    /**
     * Convert an SDKResultMessage to a SystemMessage.
     * Translated from convertResultMessage() in sdkMessageAdapter.ts
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertResultMessage(Map<String, Object> msg) {
        String subtype = (String) msg.get("subtype");
        boolean isError = !"success".equals(subtype);
        String content;
        if (isError) {
            Object errors = msg.get("errors");
            if (errors instanceof List<?> list && !list.isEmpty()) {
                content = String.join(", ", (List<String>) list);
            } else {
                content = "Unknown error";
            }
        } else {
            content = "Session completed successfully";
        }
        return Map.of(
                "type", "system",
                "subtype", "informational",
                "content", content,
                "level", isError ? "warning" : "info",
                "uuid", msg.getOrDefault("uuid", ""),
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Convert an SDKSystemMessage (init) to a SystemMessage.
     * Translated from convertInitMessage() in sdkMessageAdapter.ts
     */
    private Map<String, Object> convertInitMessage(Map<String, Object> msg) {
        String model = (String) msg.getOrDefault("model", "unknown");
        return Map.of(
                "type", "system",
                "subtype", "informational",
                "content", "Remote session initialized (model: " + model + ")",
                "level", "info",
                "uuid", msg.getOrDefault("uuid", ""),
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Convert an SDKStatusMessage to a SystemMessage (null if no status).
     * Translated from convertStatusMessage() in sdkMessageAdapter.ts
     */
    private Map<String, Object> convertStatusMessage(Map<String, Object> msg) {
        String status = (String) msg.get("status");
        if (status == null || status.isBlank()) return null;

        String content = "compacting".equals(status)
                ? "Compacting conversation\u2026"
                : "Status: " + status;

        return Map.of(
                "type", "system",
                "subtype", "informational",
                "content", content,
                "level", "info",
                "uuid", msg.getOrDefault("uuid", ""),
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Convert an SDKToolProgressMessage to a SystemMessage.
     * Translated from convertToolProgressMessage() in sdkMessageAdapter.ts
     */
    private Map<String, Object> convertToolProgressMessage(Map<String, Object> msg) {
        String toolName = (String) msg.getOrDefault("tool_name", "unknown");
        Object elapsed = msg.getOrDefault("elapsed_time_seconds", 0);
        return Map.of(
                "type", "system",
                "subtype", "informational",
                "content", "Tool " + toolName + " running for " + elapsed + "s\u2026",
                "level", "info",
                "uuid", msg.getOrDefault("uuid", ""),
                "timestamp", Instant.now().toString(),
                "toolUseID", msg.getOrDefault("tool_use_id", "")
        );
    }

    /**
     * Convert an SDKCompactBoundaryMessage to a SystemMessage.
     * Translated from convertCompactBoundaryMessage() in sdkMessageAdapter.ts
     */
    private Map<String, Object> convertCompactBoundaryMessage(Map<String, Object> msg) {
        return Map.of(
                "type", "system",
                "subtype", "compact_boundary",
                "content", "Conversation compacted",
                "level", "info",
                "uuid", msg.getOrDefault("uuid", ""),
                "timestamp", Instant.now().toString(),
                "compactMetadata", msg.getOrDefault("compact_metadata", Map.of())
        );
    }

    // =========================================================================
    // Public utility functions
    // =========================================================================

    /**
     * Check if an SDKMessage indicates the session has ended.
     * Translated from isSessionEndMessage() in sdkMessageAdapter.ts
     */
    public boolean isSessionEndMessage(Map<String, Object> msg) {
        return "result".equals(msg.get("type"));
    }

    /**
     * Check if an SDKResultMessage indicates success.
     * Translated from isSuccessResult() in sdkMessageAdapter.ts
     */
    public boolean isSuccessResult(Map<String, Object> msg) {
        return "success".equals(msg.get("subtype"));
    }

    /**
     * Extract the result text from a successful SDKResultMessage.
     * Translated from getResultText() in sdkMessageAdapter.ts
     */
    public String getResultText(Map<String, Object> msg) {
        if ("success".equals(msg.get("subtype"))) {
            return (String) msg.get("result");
        }
        return null;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Object getNestedContent(Map<String, Object> msg) {
        Object message = msg.get("message");
        if (message instanceof Map<?, ?> m) {
            return ((Map<String, Object>) m).get("content");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean isToolResultContent(Object content) {
        if (!(content instanceof List<?> list)) return false;
        return list.stream().anyMatch(item ->
                item instanceof Map<?, ?> block &&
                        "tool_result".equals(((Map<String, Object>) block).get("type")));
    }

    private Map<String, Object> createUserMessage(Map<String, Object> msg) {
        return Map.of(
                "type", "user",
                "message", msg.getOrDefault("message", Map.of()),
                "uuid", msg.getOrDefault("uuid", ""),
                "timestamp", msg.getOrDefault("timestamp", Instant.now().toString()),
                "toolUseResult", msg.getOrDefault("tool_use_result", Map.of())
        );
    }
}
