package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Context analysis service.
 *
 * Translated from src/utils/contextAnalysis.ts
 *
 * Analyzes the conversation context to understand token usage by different
 * message types, tool calls, and repeated file reads. Produces a TokenStats
 * snapshot and a flat Statsig metrics map.
 */
@Slf4j
@Service
public class ContextAnalysisService {



    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    /**
     * Full token breakdown for a conversation.
     * Mirrors TokenStats in contextAnalysis.ts
     *
     * @param toolRequests        tokens per tool name for tool_use blocks
     * @param toolResults         tokens per tool name for tool_result blocks
     * @param humanMessages       tokens from user text messages
     * @param assistantMessages   tokens from assistant text messages
     * @param localCommandOutputs tokens from local-command-stdout user messages
     * @param other               tokens from image, thinking, etc.
     * @param attachments         count of each attachment type
     * @param duplicateFileReads  paths read more than once, with duplicate token cost
     * @param total               sum of all tokens
     */
    public record TokenStats(
            Map<String, Integer> toolRequests,
            Map<String, Integer> toolResults,
            int humanMessages,
            int assistantMessages,
            int localCommandOutputs,
            int other,
            Map<String, Integer> attachments,
            Map<String, DuplicateReadInfo> duplicateFileReads,
            int total
    ) {}

    public record DuplicateReadInfo(int count, int tokens) {}

    // ------------------------------------------------------------------
    // analyzeContext
    // ------------------------------------------------------------------

    /**
     * Analyzes token usage across all messages.
     * Mirrors analyzeContext() in contextAnalysis.ts
     */
    public TokenStats analyzeContext(List<Message> messages) {
        Map<String, Integer> toolRequests = new HashMap<>();
        Map<String, Integer> toolResults = new HashMap<>();
        int humanMessages = 0;
        int assistantMessages = 0;
        int localCommandOutputs = 0;
        int other = 0;
        int total = 0;
        Map<String, Integer> attachments = new HashMap<>();

        // Tracks tool_use id → tool name for correlating tool_result blocks
        Map<String, String> toolIdsToNames = new HashMap<>();
        // Tracks Read-tool id → file path for duplicate detection
        Map<String, String> readToolIdToPath = new HashMap<>();
        // file path → (count, totalTokens)
        Map<String, int[]> fileReadStats = new HashMap<>();

        for (Message msg : messages) {
            if (msg instanceof Message.AttachmentMessage att) {
                String type = att.getAttachmentType() != null ? att.getAttachmentType() : "unknown";
                attachments.merge(type, 1, Integer::sum);
                continue;
            }

            boolean isUser = msg instanceof Message.UserMessage;
            List<ContentBlock> blocks = getContentBlocks(msg);

            for (ContentBlock block : blocks) {
                int tokens = roughTokenCount(blockToJson(block));
                total += tokens;

                switch (block.getType()) {
                    case "text" -> {
                        String text = block instanceof ContentBlock.TextBlock tb ? tb.getText() : "";
                        if (isUser && text != null && text.contains("local-command-stdout")) {
                            localCommandOutputs += tokens;
                        } else {
                            if (isUser) humanMessages += tokens;
                            else assistantMessages += tokens;
                        }
                    }
                    case "tool_use" -> {
                        if (block instanceof ContentBlock.ToolUseBlock tu) {
                            String name = tu.getName() != null ? tu.getName() : "unknown";
                            increment(toolRequests, name, tokens);
                            if (tu.getId() != null) toolIdsToNames.put(tu.getId(), name);

                            // Track Read tool file paths for duplicate detection
                            if ("Read".equals(name) && tu.getId() != null && tu.getFilePath() != null) {
                                readToolIdToPath.put(tu.getId(), tu.getFilePath());
                            }
                        }
                    }
                    case "tool_result" -> {
                        if (block instanceof ContentBlock.ToolResultBlock tr && tr.getToolUseId() != null) {
                            String name = toolIdsToNames.getOrDefault(tr.getToolUseId(), "unknown");
                            increment(toolResults, name, tokens);

                            // Track file read token accumulation for duplicate detection
                            if ("Read".equals(name)) {
                                String path = readToolIdToPath.get(tr.getToolUseId());
                                if (path != null) {
                                    int[] stats = fileReadStats.computeIfAbsent(path, k -> new int[]{0, 0});
                                    stats[0]++;        // count
                                    stats[1] += tokens; // totalTokens
                                }
                            }
                        }
                    }
                    default -> other += tokens;
                }
            }
        }

        // Compute duplicate reads (files read more than once)
        Map<String, DuplicateReadInfo> duplicateFileReads = new HashMap<>();
        for (Map.Entry<String, int[]> entry : fileReadStats.entrySet()) {
            int[] s = entry.getValue();
            if (s[0] > 1) {
                int avgTokens = s[1] / s[0];
                int duplicateTokens = avgTokens * (s[0] - 1);
                duplicateFileReads.put(entry.getKey(), new DuplicateReadInfo(s[0], duplicateTokens));
            }
        }

        return new TokenStats(toolRequests, toolResults, humanMessages, assistantMessages,
                localCommandOutputs, other, attachments, duplicateFileReads, total);
    }

    // ------------------------------------------------------------------
    // tokenStatsToStatsigMetrics
    // ------------------------------------------------------------------

    /**
     * Converts a TokenStats snapshot to a flat metrics map for Statsig.
     * Mirrors tokenStatsToStatsigMetrics() in contextAnalysis.ts
     */
    public Map<String, Integer> tokenStatsToStatsigMetrics(TokenStats stats) {
        Map<String, Integer> metrics = new LinkedHashMap<>();

        metrics.put("total_tokens", stats.total());
        metrics.put("human_message_tokens", stats.humanMessages());
        metrics.put("assistant_message_tokens", stats.assistantMessages());
        metrics.put("local_command_output_tokens", stats.localCommandOutputs());
        metrics.put("other_tokens", stats.other());

        stats.attachments().forEach((type, count) ->
                metrics.put("attachment_" + type + "_count", count));

        stats.toolRequests().forEach((tool, tokens) ->
                metrics.put("tool_request_" + tool + "_tokens", tokens));

        stats.toolResults().forEach((tool, tokens) ->
                metrics.put("tool_result_" + tool + "_tokens", tokens));

        int duplicateTotal = stats.duplicateFileReads().values().stream()
                .mapToInt(DuplicateReadInfo::tokens).sum();
        metrics.put("duplicate_read_tokens", duplicateTotal);
        metrics.put("duplicate_read_file_count", stats.duplicateFileReads().size());

        if (stats.total() > 0) {
            metrics.put("human_message_percent", pct(stats.humanMessages(), stats.total()));
            metrics.put("assistant_message_percent", pct(stats.assistantMessages(), stats.total()));
            metrics.put("local_command_output_percent", pct(stats.localCommandOutputs(), stats.total()));
            metrics.put("duplicate_read_percent", pct(duplicateTotal, stats.total()));

            int toolReqTotal = stats.toolRequests().values().stream().mapToInt(i -> i).sum();
            int toolResTotal = stats.toolResults().values().stream().mapToInt(i -> i).sum();
            metrics.put("tool_request_percent", pct(toolReqTotal, stats.total()));
            metrics.put("tool_result_percent", pct(toolResTotal, stats.total()));

            stats.toolRequests().forEach((tool, tokens) ->
                    metrics.put("tool_request_" + tool + "_percent", pct(tokens, stats.total())));

            stats.toolResults().forEach((tool, tokens) ->
                    metrics.put("tool_result_" + tool + "_percent", pct(tokens, stats.total())));
        }

        return metrics;
    }

    // ------------------------------------------------------------------
    // Human-readable report
    // ------------------------------------------------------------------

    /**
     * Format a human-readable context report from a list of messages.
     * Translated from formatContextReport() in contextAnalysis.ts
     */
    public String formatContextReport(List<Message> messages) {
        TokenStats stats = analyzeContext(messages);
        StringBuilder sb = new StringBuilder();
        sb.append("Context Window Usage\n");
        sb.append("====================\n");
        sb.append(String.format("Total tokens: ~%,d%n", stats.total()));
        sb.append(String.format("  Human messages:   ~%,d%n", stats.humanMessages()));
        sb.append(String.format("  Assistant messages: ~%,d%n", stats.assistantMessages()));
        sb.append(String.format("  Tool requests:    ~%,d%n",
            stats.toolRequests().values().stream().mapToInt(i -> i).sum()));
        sb.append(String.format("  Tool results:     ~%,d%n",
            stats.toolResults().values().stream().mapToInt(i -> i).sum()));
        if (stats.localCommandOutputs() > 0) {
            sb.append(String.format("  Local commands:   ~%,d%n", stats.localCommandOutputs()));
        }
        if (!stats.duplicateFileReads().isEmpty()) {
            sb.append("\nDuplicate file reads:\n");
            stats.duplicateFileReads().forEach((path, info) ->
                sb.append(String.format("  %s (read %d times, ~%,d extra tokens)%n",
                    path, info.count(), info.tokens())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Rough token count via character/4 heuristic (mirrors roughTokenCountEstimation).
     */
    private static int roughTokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }

    private static void increment(Map<String, Integer> map, String key, int value) {
        map.merge(key, value, Integer::sum);
    }

    private static int pct(int part, int total) {
        if (total == 0) return 0;
        return (int) Math.round((part * 100.0) / total);
    }

    /** Returns the content-block list for a message, empty list if unsupported. */
    private static List<ContentBlock> getContentBlocks(Message msg) {
        if (msg instanceof Message.UserMessage u && u.getContent() != null) return u.getContent();
        if (msg instanceof Message.AssistantMessage a && a.getContent() != null) return a.getContent();
        return List.of();
    }

    /** Produces a JSON-ish string representation of a block for token counting. */
    private static String blockToJson(ContentBlock block) {
        // Lightweight serialization for token counting — exact JSON not required.
        return block.toString();
    }

    /**
     * Get messages after the last compact boundary.
     * Stub implementation - returns all messages.
     */
    public java.util.List<Message> getMessagesAfterCompactBoundary(java.util.List<Message> messages) {
        // Find the last compact boundary and return messages after it
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof Message.SystemMessage sm
                && sm.getSubtypeEnum() == Message.SystemMessageSubtype.COMPACT_BOUNDARY) {
                return messages.subList(i + 1, messages.size());
            }
        }
        return messages;
    }

    /**
     * Analyze context usage for given messages.
     * Stub implementation.
     */
    public java.util.concurrent.CompletableFuture<com.anthropic.claudecode.service.AnalyzeContextService.ContextData> analyzeContextUsage(
            java.util.List<Message> messages,
            String mainLoopModel,
            String customSystemPrompt,
            String appendSystemPrompt,
            java.util.List<Message> apiView) {
        return java.util.concurrent.CompletableFuture.completedFuture(new com.anthropic.claudecode.service.AnalyzeContextService.ContextData());
    }
}
