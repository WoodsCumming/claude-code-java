package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Transcript search utilities.
 * Translated from src/utils/transcriptSearch.ts
 *
 * Flattens conversation messages to searchable text, with caching for performance.
 * Text is lowercased at cache time so callers never need to re-lowercase.
 */
public final class TranscriptSearchUtils {

    private static final String SYSTEM_REMINDER_OPEN  = "<system-reminder>";
    private static final String SYSTEM_REMINDER_CLOSE = "</system-reminder>";
    private static final Pattern SYSTEM_REMINDER_PATTERN =
        Pattern.compile("(?s)<system-reminder>.*?</system-reminder>");

    /**
     * Interrupt sentinel messages — rendered as special UI components.
     * Raw text never appears on screen; searching yields phantom matches.
     */
    private static final Set<String> RENDERED_AS_SENTINEL = Set.of(
        "[Request interrupted by user]",
        "[Request interrupted by user for tool use]"
    );

    /** WeakMap equivalent — messages are immutable so a cache hit is always valid. */
    private static final WeakHashMap<Message, String> SEARCH_TEXT_CACHE = new WeakHashMap<>();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Flatten a message to lowercased searchable text.
     * WeakMap-cached — messages are append-only and immutable so a hit is always valid.
     * Returns "" for non-searchable types (grouped_tool_use, system).
     *
     * Translated from renderableSearchText() in transcriptSearch.ts
     */
    public static synchronized String renderableSearchText(Message message) {
        if (message == null) return "";
        String cached = SEARCH_TEXT_CACHE.get(message);
        if (cached != null) return cached;
        String result = computeSearchText(message).toLowerCase(Locale.ROOT);
        SEARCH_TEXT_CACHE.put(message, result);
        return result;
    }

    /**
     * Search a list of messages for a query string.
     * Returns the indices of matching messages (0-based).
     */
    public static List<Integer> searchMessages(List<Message> messages, String query) {
        if (messages == null || query == null || query.isBlank()) {
            return List.of();
        }
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (renderableSearchText(messages.get(i)).contains(lowerQuery)) {
                matches.add(i);
            }
        }
        return matches;
    }

    // =========================================================================
    // Search text extraction
    // =========================================================================

    /**
     * Extract searchable text from a tool use input object.
     * Mirrors the duck-type strategy of renderToolUseMessage —
     * indexes known field names only; unknown shapes return "".
     * Under-count is honest; phantom match is a lie.
     *
     * Translated from toolUseSearchText() in transcriptSearch.ts
     */
    public static String toolUseSearchText(Object input) {
        if (!(input instanceof Map)) return "";
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) input;
        List<String> parts = new ArrayList<>();

        // Primary argument fields that renderToolUseMessage shows.
        for (String k : List.of("command", "pattern", "file_path", "path", "prompt",
                                "description", "query", "url", "skill")) {
            Object v = o.get(k);
            if (v instanceof String s) parts.add(s);
        }

        // Array fields — Tmux/TungstenTool args[], SendUserFile files[].
        for (String k : List.of("args", "files")) {
            Object v = o.get(k);
            if (v instanceof List<?> list
                    && list.stream().allMatch(x -> x instanceof String)) {
                @SuppressWarnings("unchecked")
                List<String> strings = (List<String>) list;
                parts.add(String.join(" ", strings));
            }
        }

        return String.join("\n", parts);
    }

    /**
     * Duck-type the tool's native output for searchable text.
     * Known shapes: {stdout, stderr} (Bash), {content} (Grep), {file:{content}} (Read),
     * {filenames} (Grep/Glob), {output} (generic).
     * Falls back to known output-field names only — never blindly walks all fields
     * to avoid indexing metadata that the UI doesn't show.
     *
     * Translated from toolResultSearchText() in transcriptSearch.ts
     */
    @SuppressWarnings("unchecked")
    public static String toolResultSearchText(Object r) {
        if (r == null) return "";
        if (r instanceof String s) return s;
        if (!(r instanceof Map)) return "";

        Map<String, Object> o = (Map<String, Object>) r;

        // Bash/Shell shape: {stdout, stderr}
        if (o.get("stdout") instanceof String stdout) {
            String err = o.get("stderr") instanceof String s ? s : "";
            return stdout + (err.isEmpty() ? "" : "\n" + err);
        }

        // Read shape: {file: {content: string}}
        if (o.get("file") instanceof Map<?, ?> file) {
            Object content = ((Map<String, Object>) file).get("content");
            if (content instanceof String s) return s;
        }

        // Allowlisted scalar fields.
        List<String> parts = new ArrayList<>();
        for (String k : List.of("content", "output", "result", "text", "message")) {
            if (o.get(k) instanceof String s) parts.add(s);
        }

        // Allowlisted array fields.
        for (String k : List.of("filenames", "lines", "results")) {
            Object v = o.get(k);
            if (v instanceof List<?> list
                    && list.stream().allMatch(x -> x instanceof String)) {
                @SuppressWarnings("unchecked")
                List<String> strings = (List<String>) list;
                parts.add(String.join("\n", strings));
            }
        }

        return String.join("\n", parts);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static String computeSearchText(Message message) {
        String raw = switch (message) {
            case Message.UserMessage userMsg -> computeUserText(userMsg);
            case Message.AssistantMessage asstMsg -> computeAssistantText(asstMsg);
            case Message.AttachmentMessage attMsg -> computeAttachmentText(attMsg);
            default -> "";
        };

        // Strip <system-reminder>…</system-reminder> anywhere in the text.
        return SYSTEM_REMINDER_PATTERN.matcher(raw).replaceAll("");
    }

    private static String computeUserText(Message.UserMessage msg) {
        if (msg.getContent() == null) return "";
        List<String> parts = new ArrayList<>();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ContentBlock.TextBlock tb) {
                String t = tb.getText();
                if (t != null && !RENDERED_AS_SENTINEL.contains(t)) parts.add(t);
            } else if (block instanceof ContentBlock.ToolResultBlock tr) {
                // Index the tool's native output, not the model-facing serialization.
                // See comment in transcriptSearch.ts for rationale.
                parts.add(toolResultSearchText(msg.getToolUseResult()));
            }
        }
        return String.join("\n", parts);
    }

    private static String computeAssistantText(Message.AssistantMessage msg) {
        if (msg.getContent() == null) return "";
        List<String> parts = new ArrayList<>();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ContentBlock.TextBlock tb) {
                if (tb.getText() != null) parts.add(tb.getText());
            } else if (block instanceof ContentBlock.ToolUseBlock tu) {
                parts.add(toolUseSearchText(tu.getInput()));
            }
            // Skip thinking blocks — hidden by hidePastThinking.
        }
        return String.join("\n", parts);
    }

    private static String computeAttachmentText(Message.AttachmentMessage msg) {
        if (msg.getAttachment() == null) return "";
        Object attachment = msg.getAttachment();
        if (attachment instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> att = (Map<String, Object>) attachment;
            String type = (String) att.get("type");

            // relevant_memories: full content visible in transcript mode.
            if ("relevant_memories".equals(type)) {
                Object memories = att.get("memories");
                if (memories instanceof List<?> list) {
                    List<String> parts = new ArrayList<>();
                    for (Object m : list) {
                        if (m instanceof Map<?, ?> mem) {
                            Object content = ((Map<?, ?>) mem).get("content");
                            if (content instanceof String s) parts.add(s);
                        }
                    }
                    return String.join("\n", parts);
                }
            }

            // queued_command mid-turn prompts (non-meta, non-task-notification).
            if ("queued_command".equals(type)
                    && !"task-notification".equals(att.get("commandMode"))
                    && !Boolean.TRUE.equals(att.get("isMeta"))) {
                Object prompt = att.get("prompt");
                if (prompt instanceof String s) return s;
                if (prompt instanceof List<?> blocks) {
                    List<String> parts = new ArrayList<>();
                    for (Object b : blocks) {
                        if (b instanceof Map<?, ?> bm
                                && "text".equals(((Map<?, ?>) bm).get("type"))) {
                            Object t = ((Map<?, ?>) bm).get("text");
                            if (t instanceof String s) parts.add(s);
                        }
                    }
                    return String.join("\n", parts);
                }
            }
        }
        return "";
    }

    private TranscriptSearchUtils() {}
}
