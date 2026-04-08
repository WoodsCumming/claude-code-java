package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.ContentBlock;

import java.util.List;
import java.util.Map;

/**
 * Token counting and estimation utilities.
 * Translated from src/utils/tokens.ts
 */
public final class TokenUtils {

    // -------------------------------------------------------------------------
    // Usage helpers
    // -------------------------------------------------------------------------

    /**
     * Extract the token usage record from a message if it is a non-synthetic
     * assistant message.
     * Translated from getTokenUsage() in tokens.ts
     */
    public static Message.Usage getTokenUsage(Message message) {
        if (message instanceof Message.AssistantMessage assistantMsg) {
            Message.Usage usage = assistantMsg.getUsage();
            if (usage != null) return usage;
        }
        return null;
    }

    /**
     * Calculate total context-window tokens from an API response's usage data.
     * Includes input_tokens + cache tokens + output_tokens.
     * Translated from getTokenCountFromUsage() in tokens.ts
     */
    public static int getTokenCountFromUsage(Message.Usage usage) {
        if (usage == null) return 0;
        return usage.getInputTokens()
            + usage.getCacheCreationInputTokens()
            + usage.getCacheReadInputTokens()
            + usage.getOutputTokens();
    }

    // -------------------------------------------------------------------------
    // Last-response queries
    // -------------------------------------------------------------------------

    /**
     * Total context-window token count from the most recent API response.
     * Walks backwards through messages to find the last usage record.
     * Translated from tokenCountFromLastAPIResponse() in tokens.ts
     */
    public static int tokenCountFromLastAPIResponse(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message.Usage usage = getTokenUsage(messages.get(i));
            if (usage != null) return getTokenCountFromUsage(usage);
        }
        return 0;
    }

    /**
     * Output-token count from the most recent API response.
     * Excludes all input context (system prompt, tools, prior messages).
     *
     * WARNING: Do NOT use for threshold comparisons (autocompact, session memory).
     * Use tokenCountWithEstimation() for full context-size measurement.
     * Translated from messageTokenCountFromLastAPIResponse() in tokens.ts
     */
    public static int messageTokenCountFromLastAPIResponse(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message.Usage usage = getTokenUsage(messages.get(i));
            if (usage != null) return usage.getOutputTokens();
        }
        return 0;
    }

    /**
     * Final context-window size from the last API response's usage.
     * Falls back to top-level input_tokens + output_tokens when the
     * {@code iterations} field is absent (no server-side tool loops).
     * Both paths exclude cache tokens to match the server-side formula.
     * Translated from finalContextTokensFromLastResponse() in tokens.ts
     */
    public static int finalContextTokensFromLastResponse(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message.Usage usage = getTokenUsage(messages.get(i));
            if (usage == null) continue;

            // If the usage object exposes an "iterations" list (stainless type extension),
            // use the last entry's input + output (no cache), matching the TS cast path.
            // Note: Message.Usage doesn't have getIterations() yet; skip this path.
            List<?> iterations = null; // usage.getIterations() not available
            if (iterations != null && !iterations.isEmpty()) {
                Object last = iterations.get(iterations.size() - 1);
                if (last instanceof Map<?, ?> lastMap) {
                    Number inputT  = (Number) lastMap.get("input_tokens");
                    Number outputT = (Number) lastMap.get("output_tokens");
                    if (inputT != null && outputT != null) {
                        return inputT.intValue() + outputT.intValue();
                    }
                }
            }

            // No iterations: top-level input + output (no cache)
            return usage.getInputTokens() + usage.getOutputTokens();
        }
        return 0;
    }

    /**
     * Current usage snapshot from the most recent API response.
     * Returns null if no usage record is found.
     * Translated from getCurrentUsage() in tokens.ts
     */
    public static CurrentUsage getCurrentUsage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message.Usage usage = getTokenUsage(messages.get(i));
            if (usage != null) {
                return new CurrentUsage(
                    usage.getInputTokens(),
                    usage.getOutputTokens(),
                    usage.getCacheCreationInputTokens(),
                    usage.getCacheReadInputTokens()
                );
            }
        }
        return null;
    }

    /**
     * Snapshot of the token usage from the most recent API response.
     */
    public record CurrentUsage(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens
    ) {}

    // -------------------------------------------------------------------------
    // Threshold helper
    // -------------------------------------------------------------------------

    /**
     * Check if the most recent assistant message exceeds 200 k context tokens.
     * Translated from doesMostRecentAssistantMessageExceed200k() in tokens.ts
     */
    public static boolean doesMostRecentAssistantMessageExceed200k(List<Message> messages) {
        final int THRESHOLD = 200_000;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof Message.AssistantMessage) {
                Message.Usage usage = getTokenUsage(msg);
                return usage != null && getTokenCountFromUsage(usage) > THRESHOLD;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Content length helper
    // -------------------------------------------------------------------------

    /**
     * Calculate the character content length of an assistant message.
     * Used for spinner token estimation (characters / 4 ≈ tokens).
     *
     * Counts: text, thinking, redacted_thinking data, and tool_use input JSON.
     * Excludes signature_delta (not model output).
     * Translated from getAssistantMessageContentLength() in tokens.ts
     */
    public static int getAssistantMessageContentLength(Message.AssistantMessage message) {
        if (message == null || message.getContent() == null) return 0;

        int length = 0;
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ContentBlock.TextBlock text) {
                length += text.getText() != null ? text.getText().length() : 0;
            } else if (block instanceof ContentBlock.ThinkingBlock thinking) {
                length += thinking.getThinking() != null ? thinking.getThinking().length() : 0;
            } else if (block instanceof ContentBlock.RedactedThinkingBlock redacted) {
                length += redacted.getData() != null ? redacted.getData().length() : 0;
            } else if (block instanceof ContentBlock.ToolUseBlock toolUse) {
                // Approximate JSON serialization length of the input object
                Object input = toolUse.getInput();
                length += input != null ? input.toString().length() : 0;
            }
        }
        return length;
    }

    // -------------------------------------------------------------------------
    // Context size with estimation
    // -------------------------------------------------------------------------

    /**
     * Get the current context-window size in tokens.
     *
     * This is the CANONICAL function for measuring context size when checking
     * thresholds (autocompact, session memory init, etc.). Uses the last API
     * response's token count (input + output + cache) plus rough estimates for
     * any messages added since.
     *
     * Handles parallel tool calls: the TS streaming code splits a single API
     * response into multiple assistant records (all sharing the same message.id),
     * interleaving tool_results between them. We walk back to the FIRST sibling
     * with the same id so all interleaved results are included in the estimate.
     *
     * Translated from tokenCountWithEstimation() in tokens.ts
     */
    public static int tokenCountWithEstimation(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            Message.Usage usage = getTokenUsage(message);
            if (usage == null) continue;

            // Walk back past earlier siblings split from the same API response
            String responseId = getAssistantMessageId(message);
            if (responseId != null) {
                int j = i - 1;
                while (j >= 0) {
                    String priorId = getAssistantMessageId(messages.get(j));
                    if (responseId.equals(priorId)) {
                        i = j; // anchor earlier
                    } else if (priorId != null) {
                        break; // different API response — stop
                    }
                    // null priorId: user/tool_result between splits — keep walking
                    j--;
                }
            }

            return getTokenCountFromUsage(usage)
                + roughTokenCountEstimation(messages.subList(i + 1, messages.size()));
        }
        return roughTokenCountEstimation(messages);
    }

    /**
     * Estimate token count for a list of messages.
     * Alias for tokenCountWithEstimation() — used by AutoCompactService.
     */
    public static int estimateTokenCount(List<Message> messages) {
        return tokenCountWithEstimation(messages);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Get the API response id from an assistant message, or null. */
    private static String getAssistantMessageId(Message message) {
        if (message instanceof Message.AssistantMessage assistantMsg) {
            return assistantMsg.getMessageId();
        }
        return null;
    }

    /**
     * Rough token count estimation for a list of messages.
     * Uses the 4-characters-per-token approximation.
     * Translated from roughTokenCountEstimationForMessages() in tokenEstimation.ts
     */
    public static int roughTokenCountEstimation(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int totalChars = 0;
        for (Message msg : messages) {
            totalChars += estimateMessageChars(msg);
        }
        return totalChars / 4;
    }

    private static int estimateMessageChars(Message msg) {
        if (msg instanceof Message.AssistantMessage assistantMsg) {
            return estimateContentChars(assistantMsg.getContent());
        } else if (msg instanceof Message.UserMessage userMsg) {
            return estimateContentChars(userMsg.getContent());
        }
        return 0;
    }

    private static int estimateContentChars(List<ContentBlock> content) {
        if (content == null) return 0;
        int chars = 0;
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.TextBlock text) {
                chars += text.getText() != null ? text.getText().length() : 0;
            } else {
                chars += 100; // rough estimate for non-text blocks
            }
        }
        return chars;
    }

    private TokenUtils() {}
}
