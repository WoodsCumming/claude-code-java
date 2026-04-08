package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.util.ModelUtils;
import com.anthropic.claudecode.util.SlowOperations;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Tool use summary generator service.
 * Translated from src/services/toolUseSummary/toolUseSummaryGenerator.ts
 *
 * Generates human-readable single-line labels for completed tool batches using
 * the small/fast (Haiku) model. Used by the SDK to provide high-level progress
 * updates to clients. Summaries are best-effort — failures are logged but never
 * surfaced to callers.
 */
@Slf4j
@Service
public class ToolUseSummaryService {



    // =========================================================================
    // Constants — translated from toolUseSummaryGenerator.ts
    // =========================================================================

    /**
     * System prompt for the summary model.
     * Translated verbatim from TOOL_USE_SUMMARY_SYSTEM_PROMPT in toolUseSummaryGenerator.ts
     */
    private static final String TOOL_USE_SUMMARY_SYSTEM_PROMPT =
            "Write a short summary label describing what these tool calls accomplished. " +
            "It appears as a single-line row in a mobile app and truncates around 30 characters, " +
            "so think git-commit-subject, not sentence.\n\n" +
            "Keep the verb in past tense and the most distinctive noun. " +
            "Drop articles, connectors, and long location context first.\n\n" +
            "Examples:\n" +
            "- Searched in auth/\n" +
            "- Fixed NPE in UserService\n" +
            "- Created signup endpoint\n" +
            "- Read config.json\n" +
            "- Ran failing tests";

    private static final int MAX_TOOL_INPUT_CHARS = 300;
    private static final int MAX_TOOL_OUTPUT_CHARS = 300;
    private static final int MAX_ASSISTANT_TEXT_CHARS = 200;
    private static final int MAX_SUMMARY_TOKENS = 50;

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final AnthropicClient anthropicClient;

    @Autowired
    public ToolUseSummaryService(AnthropicClient anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generate a human-readable summary of a completed tool batch.
     * Returns an empty Optional if the batch is empty or generation fails.
     *
     * Translated from generateToolUseSummary() in toolUseSummaryGenerator.ts
     *
     * @param tools              list of tools that were executed
     * @param lastAssistantText  optional context from the last assistant text block
     * @param isNonInteractive   whether the session is non-interactive
     * @return CompletableFuture resolving to the summary string, or empty on failure
     */
    public CompletableFuture<Optional<String>> generateToolUseSummary(
            List<ToolInfo> tools,
            String lastAssistantText,
            boolean isNonInteractive) {

        if (tools == null || tools.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String toolSummaries = buildToolSummaries(tools);

                String contextPrefix = (lastAssistantText != null && !lastAssistantText.isBlank())
                        ? "User's intent (from assistant's last message): "
                          + lastAssistantText.substring(0, Math.min(MAX_ASSISTANT_TEXT_CHARS, lastAssistantText.length()))
                          + "\n\n"
                        : "";

                String userPrompt = contextPrefix
                        + "Tools completed:\n\n"
                        + toolSummaries
                        + "\n\nLabel:";

                List<Map<String, Object>> messages = List.of(
                        Map.of("role", "user", "content", userPrompt));

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                        .model(ModelUtils.getSmallFastModel())
                        .maxTokens(MAX_SUMMARY_TOKENS)
                        .system(List.of(Map.of("type", "text", "text", TOOL_USE_SUMMARY_SYSTEM_PROMPT)))
                        .messages(messages)
                        .build();

                AnthropicClient.MessageResponse response = anthropicClient
                        .createMessage(request)
                        .get(30, TimeUnit.SECONDS);

                if (response.getContent() != null) {
                    String text = response.getContent().stream()
                            .filter(b -> "text".equals(b.get("type")))
                            .map(b -> (String) b.get("text"))
                            .filter(t -> t != null && !t.isBlank())
                            .findFirst()
                            .map(String::trim)
                            .orElse(null);

                    if (text != null && !text.isEmpty()) {
                        return Optional.of(text);
                    }
                }

                return Optional.<String>empty();

            } catch (Exception e) {
                // Summaries are non-critical — log but don't propagate.
                log.debug("Tool use summary generation failed: {}", e.getMessage());
                return Optional.<String>empty();
            }
        });
    }

    /**
     * Convenience overload that does not require isNonInteractive.
     */
    public CompletableFuture<Optional<String>> generateToolUseSummary(
            List<ToolInfo> tools,
            String lastAssistantText) {
        return generateToolUseSummary(tools, lastAssistantText, false);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Build a concise representation of each tool's input and output.
     * Translated from the toolSummaries block in toolUseSummaryGenerator.ts
     */
    private String buildToolSummaries(List<ToolInfo> tools) {
        StringBuilder sb = new StringBuilder();
        for (ToolInfo tool : tools) {
            String inputStr = truncateJson(tool.getInput(), MAX_TOOL_INPUT_CHARS);
            String outputStr = truncateJson(tool.getOutput(), MAX_TOOL_OUTPUT_CHARS);
            sb.append("Tool: ").append(tool.getName()).append('\n')
              .append("Input: ").append(inputStr).append('\n')
              .append("Output: ").append(outputStr).append('\n');

            if (tools.indexOf(tool) < tools.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Truncate a JSON-serializable value to at most maxLength characters.
     * Translated from truncateJson() in toolUseSummaryGenerator.ts
     */
    private String truncateJson(Object value, int maxLength) {
        if (value == null) return "null";
        try {
            String str = SlowOperations.jsonStringify(value);
            if (str.length() <= maxLength) return str;
            return str.substring(0, maxLength - 3) + "...";
        } catch (Exception e) {
            return "[unable to serialize]";
        }
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Description of a single tool execution for summary generation.
     * Translated from ToolInfo in toolUseSummaryGenerator.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ToolInfo {
        /** Tool name (e.g. "Bash", "Read", "mcp__slack__postMessage"). */
        private String name;
        /** Tool input map or primitive. */
        private Object input;
        /** Tool output value (any serializable type). */
        private Object output;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public Object getInput() { return input; }
        public void setInput(Object v) { input = v; }
        public Object getOutput() { return output; }
        public void setOutput(Object v) { output = v; }
    }

    /**
     * Parameters for generateToolUseSummary.
     * Translated from GenerateToolUseSummaryParams in toolUseSummaryGenerator.ts
     */
    public record GenerateParams(
            List<ToolInfo> tools,
            boolean isNonInteractiveSession,
            String lastAssistantText) {}
}
