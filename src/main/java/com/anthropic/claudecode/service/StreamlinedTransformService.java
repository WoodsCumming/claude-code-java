package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/**
 * Streamlined transform service for distillation-resistant output mode.
 * Translated from src/utils/streamlinedTransform.ts
 *
 * Streamlined mode:
 * - Keeps text messages intact
 * - Summarizes tool calls with cumulative counts (resets when text appears)
 * - Omits thinking content
 * - Strips tool list and model info from init messages
 */
@Slf4j
@Service
public class StreamlinedTransformService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StreamlinedTransformService.class);


    // Tool category sets — matching the TypeScript source constants
    private static final Set<String> SEARCH_TOOLS = Set.of(
            "Grep", "Glob", "WebSearch", "LSP", "ListMcpResources"
    );
    private static final Set<String> READ_TOOLS = Set.of(
            "Read", "ListMcpResources"
    );
    private static final Set<String> WRITE_TOOLS = Set.of(
            "Write", "Edit", "NotebookEdit"
    );
    // Shell tool names + Tmux + TaskStop (matching SHELL_TOOL_NAMES + extras in TS)
    private static final Set<String> COMMAND_TOOLS = Set.of(
            "Bash", "mcp__bash", "computer_use__bash", "Tmux", "TaskStop"
    );

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------

    /**
     * Cumulative tool use counts per category.
     * Translated from ToolCounts in streamlinedTransform.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ToolCounts {
        private int searches;
        private int reads;
        private int writes;
        private int commands;
        private int other;

        public boolean isEmpty() {
            return searches == 0 && reads == 0 && writes == 0 && commands == 0 && other == 0;
        }

        public void reset() {
            searches = 0; reads = 0; writes = 0; commands = 0; other = 0;
        }
    }

    /**
     * Sealed-style output message hierarchy.
     */
    public sealed interface StreamlinedMessage
            permits StreamlinedMessage.TextMessage,
                    StreamlinedMessage.ToolUseSummaryMessage,
                    StreamlinedMessage.ResultMessage {

        record TextMessage(String text, String sessionId, String uuid) implements StreamlinedMessage {}
        record ToolUseSummaryMessage(String toolSummary, String sessionId, String uuid) implements StreamlinedMessage {}
        record ResultMessage(Map<String, Object> payload) implements StreamlinedMessage {}
    }

    // -------------------------------------------------------------------------
    // Tool categorization
    // -------------------------------------------------------------------------

    /**
     * Categorize a tool name into one of the known groups.
     * Translated from categorizeToolName() in streamlinedTransform.ts
     */
    public static String categorizeToolName(String toolName) {
        if (SEARCH_TOOLS.stream().anyMatch(toolName::startsWith)) return "searches";
        if (READ_TOOLS.stream().anyMatch(toolName::startsWith)) return "reads";
        if (WRITE_TOOLS.stream().anyMatch(toolName::startsWith)) return "writes";
        if (COMMAND_TOOLS.stream().anyMatch(toolName::startsWith)) return "commands";
        return "other";
    }

    // -------------------------------------------------------------------------
    // Summary text
    // -------------------------------------------------------------------------

    /**
     * Generate a human-readable summary for cumulative tool counts.
     * Translated from getToolSummaryText() in streamlinedTransform.ts
     */
    public static Optional<String> getToolSummaryText(ToolCounts counts) {
        List<String> parts = new ArrayList<>();
        if (counts.getSearches() > 0) {
            parts.add("searched " + counts.getSearches() + " " +
                    (counts.getSearches() == 1 ? "pattern" : "patterns"));
        }
        if (counts.getReads() > 0) {
            parts.add("read " + counts.getReads() + " " +
                    (counts.getReads() == 1 ? "file" : "files"));
        }
        if (counts.getWrites() > 0) {
            parts.add("wrote " + counts.getWrites() + " " +
                    (counts.getWrites() == 1 ? "file" : "files"));
        }
        if (counts.getCommands() > 0) {
            parts.add("ran " + counts.getCommands() + " " +
                    (counts.getCommands() == 1 ? "command" : "commands"));
        }
        if (counts.getOther() > 0) {
            parts.add(counts.getOther() + " other " +
                    (counts.getOther() == 1 ? "tool" : "tools"));
        }
        if (parts.isEmpty()) return Optional.empty();
        String joined = String.join(", ", parts);
        // Capitalize first letter — matching capitalize() from stringUtils.ts
        return Optional.of(joined.substring(0, 1).toUpperCase() + joined.substring(1));
    }

    // -------------------------------------------------------------------------
    // Stateful transformer factory
    // -------------------------------------------------------------------------

    /**
     * Create a stateful transformer that accumulates tool counts between text messages.
     * Tool counts reset when a message with text content is encountered.
     * Translated from createStreamlinedTransformer() in streamlinedTransform.ts
     *
     * @return A transformer function; returns null to suppress the message.
     */
    public Function<Map<String, Object>, StreamlinedMessage> createStreamlinedTransformer() {
        ToolCounts[] cumulativeCounts = {new ToolCounts()};

        return (message) -> {
            String type = (String) message.get("type");
            if (type == null) return null;

            return switch (type) {
                case "assistant" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msgInner = (Map<String, Object>) message.get("message");
                    Object contentRaw = msgInner != null ? msgInner.get("content") : null;

                    String text = "";
                    if (contentRaw instanceof List<?> contentList) {
                        StringBuilder sb = new StringBuilder();
                        for (Object block : contentList) {
                            if (block instanceof Map<?, ?> blockMap) {
                                if ("text".equals(blockMap.get("type"))) {
                                    Object t = blockMap.get("text");
                                    if (t instanceof String s && !s.isBlank()) {
                                        if (!sb.isEmpty()) sb.append("\n");
                                        sb.append(s);
                                    }
                                } else if ("tool_use".equals(blockMap.get("type"))) {
                                    String toolName = (String) blockMap.get("name");
                                    if (toolName != null) {
                                        String category = categorizeToolName(toolName);
                                        switch (category) {
                                            case "searches" -> cumulativeCounts[0].setSearches(cumulativeCounts[0].getSearches() + 1);
                                            case "reads" -> cumulativeCounts[0].setReads(cumulativeCounts[0].getReads() + 1);
                                            case "writes" -> cumulativeCounts[0].setWrites(cumulativeCounts[0].getWrites() + 1);
                                            case "commands" -> cumulativeCounts[0].setCommands(cumulativeCounts[0].getCommands() + 1);
                                            default -> cumulativeCounts[0].setOther(cumulativeCounts[0].getOther() + 1);
                                        }
                                    }
                                }
                            }
                        }
                        text = sb.toString().trim();
                    }

                    if (!text.isEmpty()) {
                        // Text message: emit text only, reset counts
                        cumulativeCounts[0].reset();
                        yield new StreamlinedMessage.TextMessage(
                                text,
                                (String) message.get("session_id"),
                                (String) message.get("uuid"));
                    }

                    // Tool-only message: emit cumulative tool summary
                    Optional<String> summary = getToolSummaryText(cumulativeCounts[0]);
                    yield summary.map(s -> (StreamlinedMessage) new StreamlinedMessage.ToolUseSummaryMessage(
                            s,
                            (String) message.get("session_id"),
                            (String) message.get("uuid"))).orElse(null);
                }

                case "result" ->
                    // Keep result messages as-is
                    new StreamlinedMessage.ResultMessage(message);

                // All other message types are suppressed
                default -> null;
            };
        };
    }

    // -------------------------------------------------------------------------
    // Utility filter
    // -------------------------------------------------------------------------

    /**
     * Check if a message type should be included in streamlined output.
     * Translated from shouldIncludeInStreamlined() in streamlinedTransform.ts
     */
    public static boolean shouldIncludeInStreamlined(Map<String, Object> message) {
        String type = (String) message.get("type");
        return "assistant".equals(type) || "result".equals(type);
    }
}
