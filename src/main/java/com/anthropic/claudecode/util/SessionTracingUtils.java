package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session tracing utilities for Claude Code using OpenTelemetry.
 *
 * Merged from:
 *   - src/utils/telemetry/betaSessionTracing.ts  (beta/detailed tracing)
 *   - src/utils/telemetry/sessionTracing.ts       (core span lifecycle)
 *
 * Provides a high-level API for creating and managing spans to trace Claude
 * Code workflows. Each user interaction creates a root interaction span which
 * contains operation spans (LLM requests, tool calls, hooks, etc.).
 *
 * Beta tracing (detailed) is enabled when ENABLE_BETA_TRACING_DETAILED=1 and
 * BETA_TRACING_ENDPOINT are both set. For external users it also requires
 * SDK/headless mode or the 'tengu_trace_lantern' GrowthBook gate.
 *
 * Enhanced telemetry is enabled when CLAUDE_CODE_ENABLE_TELEMETRY=1 and the
 * 'enhanced_telemetry_beta' GrowthBook gate is set (or USER_TYPE=ant).
 *
 * Visibility rules:
 * | Content          | External | Ant |
 * |------------------|----------|-----|
 * | System prompts   |    yes   | yes |
 * | Model output     |    yes   | yes |
 * | Thinking output  |    no    | yes |
 * | Tools            |    yes   | yes |
 * | new_context      |    yes   | yes |
 */
@Slf4j
public final class SessionTracingUtils {



    // =========================================================================
    // Constants
    // =========================================================================

    /** Honeycomb limit is 64 KB; stay slightly under. */
    private static final int MAX_CONTENT_SIZE = 60 * 1024;

    /** Span TTL: orphaned spans are evicted after 30 minutes. */
    private static final long SPAN_TTL_MS = 30 * 60 * 1000L;

    /** OTel tracer name used by Claude Code. */
    public static final String TRACER_NAME = "com.anthropic.claude_code.tracing";
    public static final String TRACER_VERSION = "1.0.0";

    // =========================================================================
    // Beta tracing session state (betaSessionTracing.ts)
    // =========================================================================

    /**
     * Track hashes we've already logged this session (system prompts, tools, etc.).
     * System prompts and tool schemas are large and rarely change within a session;
     * full content is sent only once per unique hash.
     */
    private static final Set<String> seenHashes =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Track the last reported message hash per querySource (agent) for incremental
     * context diffs. Each agent has an independent conversation context.
     */
    private static final Map<String, String> lastReportedMessageHash =
            new ConcurrentHashMap<>();

    // Regex to detect content wrapped in <system-reminder> tags.
    private static final Pattern SYSTEM_REMINDER_REGEX =
            Pattern.compile("^<system-reminder>\\n?([\\s\\S]*?)\\n?</system-reminder>$");

    // =========================================================================
    // Span type constants  (sessionTracing.ts → SpanType)
    // =========================================================================

    public static final String SPAN_TYPE_INTERACTION      = "interaction";
    public static final String SPAN_TYPE_LLM_REQUEST      = "llm_request";
    public static final String SPAN_TYPE_TOOL             = "tool";
    public static final String SPAN_TYPE_BLOCKED_ON_USER  = "tool.blocked_on_user";
    public static final String SPAN_TYPE_TOOL_EXECUTION   = "tool.execution";
    public static final String SPAN_TYPE_HOOK             = "hook";

    // =========================================================================
    // Data holders
    // =========================================================================

    /**
     * Carries the information stored per active span.
     * Mirrors the SpanContext interface in sessionTracing.ts.
     */
    public static class SpanContext {
        public final String spanId;
        public final String spanType;
        public final long startTimeMs;
        public final Map<String, Object> attributes;
        /** Optional Perfetto span ID for parallel Perfetto tracing. */
        public String perfettoSpanId;
        public boolean ended = false;

        public SpanContext(String spanId, String spanType,
                           Map<String, Object> attributes) {
            this.spanId       = spanId;
            this.spanType     = spanType;
            this.startTimeMs  = System.currentTimeMillis();
            this.attributes   = new LinkedHashMap<>(attributes != null ? attributes : Map.of());
        }
    }

    /**
     * New-context metadata forwarded from the caller to an LLM request span.
     * Mirrors LLMRequestNewContext in betaSessionTracing.ts.
     */
    public record LLMRequestNewContext(
            /** System prompt (typically only on first request or if changed). */
            String systemPrompt,
            /** Query source identifying the agent/purpose, e.g. 'repl_main_thread'. */
            String querySource,
            /** Tool schemas sent with the request (JSON string). */
            String tools
    ) {
        public LLMRequestNewContext { /* compact constructor — no-op */ }
    }

    /**
     * Metadata attached when an LLM request span is ended.
     * Mirrors the metadata parameter of endLLMRequestSpan() in sessionTracing.ts.
     */
    public record LLMResponseMetadata(
            Integer inputTokens,
            Integer outputTokens,
            Integer cacheReadTokens,
            Integer cacheCreationTokens,
            Boolean success,
            Integer statusCode,
            String error,
            Integer attempt,
            String modelOutput,
            String thinkingOutput,
            Boolean hasToolCall,
            Long ttftMs,
            Long requestSetupMs,
            long[] attemptStartTimes
    ) {}

    /**
     * Result of content truncation.
     * Mirrors the return type of truncateContent() in betaSessionTracing.ts.
     */
    public record TruncationResult(String content, boolean truncated) {}

    /**
     * Separation of regular content from system-reminder content when formatting
     * messages for the new_context span attribute.
     * Mirrors FormattedMessages in betaSessionTracing.ts.
     */
    public record FormattedMessages(List<String> contextParts, List<String> systemReminders) {}

    // =========================================================================
    // Beta-tracing enablement checks  (betaSessionTracing.ts)
    // =========================================================================

    /**
     * Check if beta detailed tracing is enabled.
     * Requires ENABLE_BETA_TRACING_DETAILED=1 and BETA_TRACING_ENDPOINT.
     * For external users, also requires headless/SDK mode OR the
     * 'tengu_trace_lantern' GrowthBook gate.
     *
     * Translated from isBetaTracingEnabled() in betaSessionTracing.ts.
     */
    public static boolean isBetaTracingEnabled() {
        boolean baseEnabled = EnvUtils.isEnvTruthy(System.getenv("ENABLE_BETA_TRACING_DETAILED"))
                && System.getenv("BETA_TRACING_ENDPOINT") != null
                && !System.getenv("BETA_TRACING_ENDPOINT").isBlank();

        if (!baseEnabled) return false;

        // Ant users: always enabled.
        if ("ant".equals(System.getenv("USER_TYPE"))) return true;

        // External: enabled in headless mode OR when org is allowlisted.
        // isNonInteractiveSession is approximated by CLAUDE_CODE_NON_INTERACTIVE env var.
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_NON_INTERACTIVE"))) return true;

        // GrowthBook gate (cached — use system property as test seam).
        return "true".equalsIgnoreCase(System.getProperty("claude.feature.tengu_trace_lantern"));
    }

    /**
     * Check if enhanced telemetry (beta) is enabled.
     * Priority: env var override > ant build > GrowthBook gate.
     *
     * Translated from isEnhancedTelemetryEnabled() in sessionTracing.ts.
     */
    public static boolean isEnhancedTelemetryEnabled() {
        // Ant: always enabled when the ANT_ENHANCED_TELEMETRY_BETA build flag is set
        if ("ant".equals(System.getenv("USER_TYPE"))) return true;

        String env = System.getenv("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA");
        if (env == null) env = System.getenv("ENABLE_ENHANCED_TELEMETRY_BETA");

        if (EnvUtils.isEnvTruthy(env)) return true;
        if (EnvUtils.isEnvDefinedFalsy(env)) return false;

        return "true".equalsIgnoreCase(System.getProperty("claude.feature.enhanced_telemetry_beta"));
    }

    /**
     * Check if any tracing is active (standard enhanced telemetry OR beta).
     * Translated from isAnyTracingEnabled() in sessionTracing.ts.
     */
    public static boolean isAnyTracingEnabled() {
        return isEnhancedTelemetryEnabled() || isBetaTracingEnabled();
    }

    // =========================================================================
    // Content truncation  (betaSessionTracing.ts)
    // =========================================================================

    /**
     * Truncate content to fit within the Honeycomb attribute size limit.
     * Translated from truncateContent() in betaSessionTracing.ts.
     */
    public static TruncationResult truncateContent(String content) {
        return truncateContent(content, MAX_CONTENT_SIZE);
    }

    /**
     * Truncate content to the specified maximum byte limit.
     * Translated from truncateContent() in betaSessionTracing.ts.
     */
    public static TruncationResult truncateContent(String content, int maxSize) {
        if (content == null) return new TruncationResult("", false);
        if (content.length() <= maxSize) return new TruncationResult(content, false);
        return new TruncationResult(
                content.substring(0, maxSize) + "\n\n[TRUNCATED - Content exceeds 60KB limit]",
                true);
    }

    // =========================================================================
    // Hash helpers  (betaSessionTracing.ts)
    // =========================================================================

    /**
     * Generate a short hash (first 12 hex chars of SHA-256).
     * Translated from shortHash() in betaSessionTracing.ts.
     */
    public static String shortHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 12);
        } catch (Exception e) {
            return "000000000000";
        }
    }

    /**
     * Generate a hash identifier for a system prompt.
     * Translated from hashSystemPrompt() in betaSessionTracing.ts.
     */
    public static String hashSystemPrompt(String systemPrompt) {
        return "sp_" + shortHash(systemPrompt);
    }

    // =========================================================================
    // System-reminder extraction  (betaSessionTracing.ts)
    // =========================================================================

    /**
     * Check if text is entirely a system reminder.
     * Returns the inner content if it is, null otherwise.
     * Translated from extractSystemReminderContent() in betaSessionTracing.ts.
     */
    public static String extractSystemReminderContent(String text) {
        if (text == null) return null;
        Matcher m = SYSTEM_REMINDER_REGEX.matcher(text.trim());
        if (m.matches()) {
            String inner = m.group(1);
            return (inner != null && !inner.isBlank()) ? inner.trim() : null;
        }
        return null;
    }

    // =========================================================================
    // Beta tracing attribute helpers  (betaSessionTracing.ts)
    // =========================================================================

    /**
     * Add beta attributes to an interaction span (new_context = user prompt).
     * Translated from addBetaInteractionAttributes() in betaSessionTracing.ts.
     */
    public static void addBetaInteractionAttributes(
            Map<String, Object> spanAttributes, String userPrompt) {
        if (!isBetaTracingEnabled() || userPrompt == null) return;

        TruncationResult r = truncateContent("[USER PROMPT]\n" + userPrompt);
        spanAttributes.put("new_context", r.content());
        if (r.truncated()) {
            spanAttributes.put("new_context_truncated", true);
            spanAttributes.put("new_context_original_length", userPrompt.length());
        }
    }

    /**
     * Add beta attributes to an LLM request span.
     * Handles system-prompt logging (once per unique hash) and new_context computation.
     * Translated from addBetaLLMRequestAttributes() in betaSessionTracing.ts.
     *
     * @param spanAttributes mutable attribute map that will be written to the span
     * @param newContext      context metadata from the caller
     * @param messages        full message array for the API call (simplified: list of role+text)
     */
    public static void addBetaLLMRequestAttributes(
            Map<String, Object> spanAttributes,
            LLMRequestNewContext newContext,
            List<Map<String, Object>> messages) {
        if (!isBetaTracingEnabled()) return;

        if (newContext != null && newContext.systemPrompt() != null) {
            String promptHash = hashSystemPrompt(newContext.systemPrompt());
            String preview = newContext.systemPrompt().substring(
                    0, Math.min(500, newContext.systemPrompt().length()));

            spanAttributes.put("system_prompt_hash", promptHash);
            spanAttributes.put("system_prompt_preview", preview);
            spanAttributes.put("system_prompt_length", newContext.systemPrompt().length());

            if (seenHashes.add(promptHash)) {
                TruncationResult r = truncateContent(newContext.systemPrompt());
                log.trace("[BetaTracing] system_prompt hash={} length={}{}",
                        promptHash, newContext.systemPrompt().length(),
                        r.truncated() ? " [TRUNCATED]" : "");
            }
        }

        if (newContext != null && newContext.querySource() != null) {
            spanAttributes.put("query_source", newContext.querySource());
        }

        // Compute incremental new_context from messages
        if (messages != null && !messages.isEmpty()
                && newContext != null && newContext.querySource() != null) {
            String querySource = newContext.querySource();
            String lastHash = lastReportedMessageHash.get(querySource);

            int startIndex = 0;
            if (lastHash != null) {
                for (int i = 0; i < messages.size(); i++) {
                    Object content = messages.get(i).get("content");
                    String msgHash = "msg_" + shortHash(content != null ? content.toString() : "");
                    if (msgHash.equals(lastHash)) {
                        startIndex = i + 1;
                        break;
                    }
                }
            }

            List<Map<String, Object>> newMessages = new ArrayList<>();
            for (int i = startIndex; i < messages.size(); i++) {
                Map<String, Object> msg = messages.get(i);
                if ("user".equals(msg.get("role"))) {
                    newMessages.add(msg);
                }
            }

            if (!newMessages.isEmpty()) {
                FormattedMessages formatted = formatMessagesForContext(newMessages);

                if (!formatted.contextParts().isEmpty()) {
                    String fullContext = String.join("\n\n---\n\n", formatted.contextParts());
                    TruncationResult r = truncateContent(fullContext);
                    spanAttributes.put("new_context", r.content());
                    spanAttributes.put("new_context_message_count", newMessages.size());
                    if (r.truncated()) {
                        spanAttributes.put("new_context_truncated", true);
                        spanAttributes.put("new_context_original_length", fullContext.length());
                    }
                }

                if (!formatted.systemReminders().isEmpty()) {
                    String fullReminders = String.join("\n\n---\n\n", formatted.systemReminders());
                    TruncationResult rr = truncateContent(fullReminders);
                    spanAttributes.put("system_reminders", rr.content());
                    spanAttributes.put("system_reminders_count", formatted.systemReminders().size());
                    if (rr.truncated()) {
                        spanAttributes.put("system_reminders_truncated", true);
                        spanAttributes.put("system_reminders_original_length", fullReminders.length());
                    }
                }

                Map<String, Object> lastMsg = messages.get(messages.size() - 1);
                Object lastContent = lastMsg.get("content");
                String newLastHash = "msg_" + shortHash(lastContent != null ? lastContent.toString() : "");
                lastReportedMessageHash.put(querySource, newLastHash);
            }
        }
    }

    /**
     * Add beta attributes to an LLM response (model_output, thinking_output).
     * Translated from addBetaLLMResponseAttributes() in betaSessionTracing.ts.
     */
    public static void addBetaLLMResponseAttributes(
            Map<String, Object> endAttributes,
            String modelOutput,
            String thinkingOutput) {
        if (!isBetaTracingEnabled()) return;

        if (modelOutput != null) {
            TruncationResult r = truncateContent(modelOutput);
            endAttributes.put("response.model_output", r.content());
            if (r.truncated()) {
                endAttributes.put("response.model_output_truncated", true);
                endAttributes.put("response.model_output_original_length", modelOutput.length());
            }
        }

        // thinking_output is ant-only
        if ("ant".equals(System.getenv("USER_TYPE")) && thinkingOutput != null) {
            TruncationResult r = truncateContent(thinkingOutput);
            endAttributes.put("response.thinking_output", r.content());
            if (r.truncated()) {
                endAttributes.put("response.thinking_output_truncated", true);
                endAttributes.put("response.thinking_output_original_length", thinkingOutput.length());
            }
        }
    }

    /**
     * Add beta attributes to a tool start span (tool_input).
     * Translated from addBetaToolInputAttributes() in betaSessionTracing.ts.
     */
    public static void addBetaToolInputAttributes(
            Map<String, Object> spanAttributes,
            String toolName,
            String toolInput) {
        if (!isBetaTracingEnabled() || toolInput == null) return;

        TruncationResult r = truncateContent("[TOOL INPUT: " + toolName + "]\n" + toolInput);
        spanAttributes.put("tool_input", r.content());
        if (r.truncated()) {
            spanAttributes.put("tool_input_truncated", true);
            spanAttributes.put("tool_input_original_length", toolInput.length());
        }
    }

    /**
     * Add beta attributes to a tool end span (new_context = tool result).
     * Translated from addBetaToolResultAttributes() in betaSessionTracing.ts.
     */
    public static void addBetaToolResultAttributes(
            Map<String, Object> endAttributes,
            String toolName,
            String toolResult) {
        if (!isBetaTracingEnabled() || toolResult == null) return;

        TruncationResult r = truncateContent("[TOOL RESULT: " + toolName + "]\n" + toolResult);
        endAttributes.put("new_context", r.content());
        if (r.truncated()) {
            endAttributes.put("new_context_truncated", true);
            endAttributes.put("new_context_original_length", toolResult.length());
        }
    }

    // =========================================================================
    // Span lifecycle helpers  (sessionTracing.ts)
    // =========================================================================

    /**
     * Build a new interaction span attribute map.
     * Mirrors createSpanAttributes('interaction', ...) in sessionTracing.ts.
     */
    public static Map<String, Object> buildInteractionAttributes(
            String userPrompt, int interactionSequence) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("span.type", SPAN_TYPE_INTERACTION);
        attrs.put("user_prompt_length", userPrompt != null ? userPrompt.length() : 0);
        attrs.put("interaction.sequence", interactionSequence);

        // Log the raw prompt only when explicitly requested (privacy guard).
        if (EnvUtils.isEnvTruthy(System.getenv("OTEL_LOG_USER_PROMPTS"))
                && userPrompt != null) {
            attrs.put("user_prompt", userPrompt);
        } else {
            attrs.put("user_prompt", "<REDACTED>");
        }
        return attrs;
    }

    /**
     * Build an LLM request span attribute map.
     * Mirrors createSpanAttributes('llm_request', ...) in sessionTracing.ts.
     */
    public static Map<String, Object> buildLLMRequestAttributes(
            String model, boolean fastMode, boolean hasParentInteraction) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("span.type", SPAN_TYPE_LLM_REQUEST);
        attrs.put("model", model);
        attrs.put("llm_request.context", hasParentInteraction ? "interaction" : "standalone");
        attrs.put("speed", fastMode ? "fast" : "normal");
        return attrs;
    }

    /**
     * Build a tool span attribute map.
     * Mirrors createSpanAttributes('tool', ...) in sessionTracing.ts.
     */
    public static Map<String, Object> buildToolAttributes(
            String toolName, Map<String, Object> extra) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("span.type", SPAN_TYPE_TOOL);
        attrs.put("tool_name", toolName);
        if (extra != null) attrs.putAll(extra);
        return attrs;
    }

    /**
     * Build a hook span attribute map.
     * Mirrors the hook attributes in startHookSpan() in sessionTracing.ts.
     */
    public static Map<String, Object> buildHookAttributes(
            String hookEvent, String hookName,
            int numHooks, String hookDefinitions) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("span.type", SPAN_TYPE_HOOK);
        attrs.put("hook_event", hookEvent);
        attrs.put("hook_name", hookName);
        attrs.put("num_hooks", numHooks);
        attrs.put("hook_definitions", hookDefinitions != null ? hookDefinitions : "");
        return attrs;
    }

    /**
     * Build the end-attributes map for an LLM response span.
     * Handles optional metadata fields and delegates beta response attributes.
     * Mirrors the endAttributes assembly in endLLMRequestSpan() in sessionTracing.ts.
     */
    public static Map<String, Object> buildLLMResponseEndAttributes(
            long durationMs, LLMResponseMetadata meta) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("duration_ms", durationMs);

        if (meta != null) {
            if (meta.inputTokens()        != null) attrs.put("input_tokens",        meta.inputTokens());
            if (meta.outputTokens()       != null) attrs.put("output_tokens",       meta.outputTokens());
            if (meta.cacheReadTokens()    != null) attrs.put("cache_read_tokens",   meta.cacheReadTokens());
            if (meta.cacheCreationTokens()!= null) attrs.put("cache_creation_tokens", meta.cacheCreationTokens());
            if (meta.success()            != null) attrs.put("success",             meta.success());
            if (meta.statusCode()         != null) attrs.put("status_code",         meta.statusCode());
            if (meta.error()              != null) attrs.put("error",               meta.error());
            if (meta.attempt()            != null) attrs.put("attempt",             meta.attempt());
            if (meta.hasToolCall()        != null) attrs.put("response.has_tool_call", meta.hasToolCall());
            if (meta.ttftMs()             != null) attrs.put("ttft_ms",             meta.ttftMs());

            // Beta-specific response attributes (model_output, thinking_output)
            addBetaLLMResponseAttributes(attrs, meta.modelOutput(), meta.thinkingOutput());
        }

        return attrs;
    }

    // =========================================================================
    // Session state management  (betaSessionTracing.ts)
    // =========================================================================

    /**
     * Clear beta-tracing session state (seenHashes + lastReportedMessageHash).
     * Call this after context compaction — old hashes are irrelevant once
     * messages have been replaced.
     * Translated from clearBetaTracingState() in betaSessionTracing.ts.
     */
    public static void clearBetaTracingState() {
        seenHashes.clear();
        lastReportedMessageHash.clear();
    }

    // =========================================================================
    // Message formatting helpers  (betaSessionTracing.ts)
    // =========================================================================

    /**
     * Format user messages for new_context display, separating system reminders.
     * Each message map must have "role" and "content" keys.
     * Translated from formatMessagesForContext() in betaSessionTracing.ts.
     */
    public static FormattedMessages formatMessagesForContext(
            List<Map<String, Object>> userMessages) {
        List<String> contextParts   = new ArrayList<>();
        List<String> systemReminders = new ArrayList<>();

        for (Map<String, Object> msg : userMessages) {
            Object content = msg.get("content");
            if (content == null) continue;

            if (content instanceof String text) {
                String reminder = extractSystemReminderContent(text);
                if (reminder != null) {
                    systemReminders.add(reminder);
                } else {
                    contextParts.add("[USER]\n" + text);
                }
            } else if (content instanceof List<?> blocks) {
                for (Object block : blocks) {
                    if (!(block instanceof Map<?, ?> blockMap)) continue;
                    String type = String.valueOf(blockMap.get("type"));
                    if ("text".equals(type)) {
                        String text = String.valueOf(blockMap.get("text"));
                        String reminder = extractSystemReminderContent(text);
                        if (reminder != null) {
                            systemReminders.add(reminder);
                        } else {
                            contextParts.add("[USER]\n" + text);
                        }
                    } else if ("tool_result".equals(type)) {
                        Object rc = blockMap.get("content");
                        String resultStr = rc != null ? rc.toString() : "";
                        String reminder = extractSystemReminderContent(resultStr);
                        if (reminder != null) {
                            systemReminders.add(reminder);
                        } else {
                            String toolUseId = String.valueOf(blockMap.get("tool_use_id"));
                            contextParts.add("[TOOL RESULT: " + toolUseId + "]\n" + resultStr);
                        }
                    }
                }
            }
        }

        return new FormattedMessages(contextParts, systemReminders);
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /**
     * Check if tool-content logging is enabled (OTEL_LOG_TOOL_CONTENT=1).
     * Mirrors isToolContentLoggingEnabled() in sessionTracing.ts.
     */
    public static boolean isToolContentLoggingEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("OTEL_LOG_TOOL_CONTENT"));
    }

    /**
     * Apply content truncation to every String value in the attributes map,
     * adding a *_truncated / *_original_length sibling when truncated.
     * Used for addToolContentEvent() in sessionTracing.ts.
     */
    public static Map<String, Object> truncateAttributes(Map<String, Object> attributes) {
        if (attributes == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof String s) {
                TruncationResult r = truncateContent(s);
                result.put(key, r.content());
                if (r.truncated()) {
                    result.put(key + "_truncated", true);
                    result.put(key + "_original_length", s.length());
                }
            } else {
                result.put(key, val);
            }
        }
        return result;
    }

    // =========================================================================
    // Private constructor — utility class
    // =========================================================================
    private SessionTracingUtils() {}
}
