package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.util.ApiProviderUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Token estimation service.
 * Translated from src/services/tokenEstimation.ts
 *
 * Provides both fast rough estimates and accurate API-based token counts.
 */
@Slf4j
@Service
public class TokenEstimationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TokenEstimationService.class);


    // Minimal values for token counting with thinking enabled.
    // API constraint: max_tokens must be greater than thinking.budget_tokens
    private static final int TOKEN_COUNT_THINKING_BUDGET = 1024;
    private static final int TOKEN_COUNT_MAX_TOKENS = 2048;

    // Default chars-per-token ratio used for rough estimates
    private static final int DEFAULT_BYTES_PER_TOKEN = 4;

    private final AnthropicClient anthropicClient;

    @Autowired
    public TokenEstimationService(AnthropicClient anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    // =========================================================================
    // Rough estimation (no network I/O)
    // =========================================================================

    /**
     * Fast rough token count estimate for a string.
     * Translated from roughTokenCountEstimation() in tokenEstimation.ts
     *
     * @param content       text to estimate
     * @param bytesPerToken chars-per-token ratio (default 4)
     */
    public int roughTokenCountEstimation(String content, int bytesPerToken) {
        if (content == null || content.isEmpty()) return 0;
        return Math.round((float) content.length() / bytesPerToken);
    }

    /** Overload with the default 4 chars-per-token ratio. */
    public int roughTokenCountEstimation(String content) {
        return roughTokenCountEstimation(content, DEFAULT_BYTES_PER_TOKEN);
    }

    /**
     * Returns an estimated bytes-per-token ratio for a given file extension.
     * Dense JSON has many single-character tokens which makes the real ratio
     * closer to 2 rather than the default 4.
     * Translated from bytesPerTokenForFileType() in tokenEstimation.ts
     */
    public int bytesPerTokenForFileType(String fileExtension) {
        if (fileExtension == null) return DEFAULT_BYTES_PER_TOKEN;
        return switch (fileExtension.toLowerCase()) {
            case "json", "jsonl", "jsonc" -> 2;
            default -> DEFAULT_BYTES_PER_TOKEN;
        };
    }

    /**
     * Rough token estimate using a file-type-aware bytes-per-token ratio.
     * Translated from roughTokenCountEstimationForFileType() in tokenEstimation.ts
     */
    public int roughTokenCountEstimationForFileType(String content, String fileExtension) {
        return roughTokenCountEstimation(content, bytesPerTokenForFileType(fileExtension));
    }

    /**
     * Rough token estimate for a list of message maps
     * ({@code type}, {@code message.content}, {@code attachment}).
     * Translated from roughTokenCountEstimationForMessages() in tokenEstimation.ts
     */
    public int roughTokenCountEstimationForMessages(
            List<? extends Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> message : messages) {
            total += roughTokenCountEstimationForMessage(message);
        }
        return total;
    }

    /**
     * Rough token estimate for a single message map.
     * Translated from roughTokenCountEstimationForMessage() in tokenEstimation.ts
     */
    @SuppressWarnings("unchecked")
    public int roughTokenCountEstimationForMessage(Map<String, Object> message) {
        String type = (String) message.get("type");

        if ("assistant".equals(type) || "user".equals(type)) {
            Object msgObj = message.get("message");
            if (msgObj instanceof Map<?, ?> msgMap) {
                Object content = ((Map<String, Object>) msgMap).get("content");
                return roughTokenCountEstimationForContent(content);
            }
        }

        if ("attachment".equals(type)) {
            // Conservative constant; images and PDFs are charged ~2000 tokens each
            return 2000;
        }

        return 0;
    }

    // =========================================================================
    // API-based accurate count
    // =========================================================================

    /**
     * Count tokens for a single content string via the Anthropic API.
     * Translated from countTokensWithAPI() in tokenEstimation.ts
     *
     * Returns {@code null} on API error.
     */
    public CompletableFuture<Integer> countTokensWithAPI(String content) {
        if (content == null || content.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        Map<String, Object> message = Map.of("role", "user", "content", content);
        return countMessagesTokensWithAPI(List.of(message), List.of());
    }

    /**
     * Count tokens for a list of messages + tools via the Anthropic API.
     * Falls back to {@code null} on error.
     * Translated from countMessagesTokensWithAPI() in tokenEstimation.ts
     */
    public CompletableFuture<Integer> countMessagesTokensWithAPI(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String provider = ApiProviderUtils.getAPIProvider().getValue();

                if ("bedrock".equals(provider)) {
                    return countTokensWithBedrock(messages, tools);
                }

                // Build the count-tokens request body
                AnthropicClient.CountTokensRequest request =
                        AnthropicClient.CountTokensRequest.builder()
                                .messages(messages.isEmpty()
                                        ? java.util.List.<Object>of(new java.util.HashMap<String, Object>(Map.of("role", "user", "content", "foo")))
                                        : new java.util.ArrayList<Object>(messages))
                                .tools(tools != null ? new java.util.ArrayList<Object>(tools) : null)
                                .build();

                AnthropicClient.CountTokensResponse response =
                        anthropicClient.countTokens(request).get();

                return response != null ? response.getInputTokens() : null;

            } catch (Exception e) {
                log.error("[TokenEstimation] countTokensWithAPI failed: {}", e.getMessage());
                return null;
            }
        });
    }

    // =========================================================================
    // countTokensViaHaikuFallback
    // (uses a tiny model request to read usage.input_tokens from the response)
    // Translated from countTokensViaHaikuFallback() in tokenEstimation.ts
    // =========================================================================

    /**
     * Estimate token count by firing a minimal 1-token generation against a
     * small/fast model and reading the {@code usage.input_tokens} from the
     * response.  Prefers Haiku; falls back to Sonnet on Vertex (global region)
     * or when messages contain thinking blocks.
     *
     * Returns the sum of input_tokens + cache_creation + cache_read tokens,
     * or {@code null} on error.
     */
    public CompletableFuture<Integer> countTokensViaHaikuFallback(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean containsThinking = hasThinkingBlocks(messages);

                // Provider / region checks (mirrors the TS logic)
                boolean isVertexGlobal = isEnvTruthy("CLAUDE_CODE_USE_VERTEX")
                        && "global".equals(System.getenv("ANTHROPIC_VERTEX_REGION"));
                boolean isBedrockWithThinking =
                        isEnvTruthy("CLAUDE_CODE_USE_BEDROCK") && containsThinking;
                boolean isVertexWithThinking =
                        isEnvTruthy("CLAUDE_CODE_USE_VERTEX") && containsThinking;

                String model = (isVertexGlobal || isBedrockWithThinking || isVertexWithThinking)
                        ? getDefaultSonnetModel()
                        : getSmallFastModel();

                List<Map<String, Object>> stripped = stripToolSearchFields(messages);
                List<Map<String, Object>> toSend = stripped.isEmpty()
                        ? List.of(Map.of("role", "user", "content", "count"))
                        : stripped;

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                        .model(model)
                        .maxTokens(containsThinking ? TOKEN_COUNT_MAX_TOKENS : 1)
                        .messages(toSend)
                        .tools(tools.isEmpty() ? null : tools)
                        .build();

                AnthropicClient.MessageResponse response =
                        anthropicClient.createMessage(request).get();

                if (response == null || response.getUsage() == null) return null;

                AnthropicClient.UsageStats usage = response.getTypedUsage();
                if (usage == null) return null;
                int input = usage.getInputTokens();
                int cacheCreate = usage.getCacheCreationInputTokens() != null
                        ? usage.getCacheCreationInputTokens() : 0;
                int cacheRead = usage.getCacheReadInputTokens() != null
                        ? usage.getCacheReadInputTokens() : 0;

                return input + cacheCreate + cacheRead;

            } catch (Exception e) {
                log.error("[TokenEstimation] countTokensViaHaikuFallback failed: {}",
                        e.getMessage());
                return null;
            }
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Rough token count for content that may be a String, List of blocks, or null.
     * Translated from roughTokenCountEstimationForContent() in tokenEstimation.ts
     */
    @SuppressWarnings("unchecked")
    private int roughTokenCountEstimationForContent(Object content) {
        if (content == null) return 0;
        if (content instanceof String s) return roughTokenCountEstimation(s);
        if (content instanceof List<?> list) {
            int total = 0;
            for (Object block : list) {
                total += roughTokenCountEstimationForBlock(block);
            }
            return total;
        }
        return 0;
    }

    /**
     * Rough token count for a single content block map.
     * Translated from roughTokenCountEstimationForBlock() in tokenEstimation.ts
     */
    @SuppressWarnings("unchecked")
    private int roughTokenCountEstimationForBlock(Object block) {
        if (block instanceof String s) return roughTokenCountEstimation(s);
        if (!(block instanceof Map<?, ?> rawMap)) return 0;

        Map<String, Object> b = (Map<String, Object>) rawMap;
        String type = (String) b.get("type");

        return switch (type != null ? type : "") {
            case "text" -> roughTokenCountEstimation((String) b.get("text"));
            // https://platform.claude.com/docs/en/build-with-claude/vision#calculate-image-costs
            // tokens = (width px * height px) / 750 → cap at same constant as microCompact
            case "image", "document" -> 2000;
            case "tool_result" -> roughTokenCountEstimationForContent(b.get("content"));
            case "tool_use" -> {
                String nameAndInput = b.get("name") + toJsonString(b.getOrDefault("input", Map.of()));
                yield roughTokenCountEstimation(nameAndInput);
            }
            case "thinking" -> roughTokenCountEstimation((String) b.get("thinking"));
            case "redacted_thinking" -> roughTokenCountEstimation((String) b.get("data"));
            // server_tool_use, web_search_tool_result, mcp_tool_use, etc.
            default -> roughTokenCountEstimation(toJsonString(b));
        };
    }

    /**
     * Check whether any assistant message in the list contains thinking blocks.
     * Translated from hasThinkingBlocks() in tokenEstimation.ts
     */
    @SuppressWarnings("unchecked")
    private boolean hasThinkingBlocks(List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            if ("assistant".equals(message.get("role"))) {
                Object content = message.get("content");
                if (content instanceof List<?> blocks) {
                    for (Object block : blocks) {
                        if (block instanceof Map<?, ?> b) {
                            String bt = (String) ((Map<String, Object>) b).get("type");
                            if ("thinking".equals(bt) || "redacted_thinking".equals(bt)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Strip tool-search-specific fields (caller, tool_reference blocks) from messages.
     * Translated from stripToolSearchFieldsFromMessages() in tokenEstimation.ts
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stripToolSearchFields(
            List<Map<String, Object>> messages) {
        return messages.stream().map(message -> {
            Object content = message.get("content");
            if (!(content instanceof List<?>)) return message;

            List<Object> normalised = ((List<Object>) content).stream().map(block -> {
                if (!(block instanceof Map<?, ?> rawBlock)) return block;
                Map<String, Object> b = (Map<String, Object>) rawBlock;
                String type = (String) b.get("type");

                if ("tool_use".equals(type)) {
                    // Strip any extra fields like 'caller'
                    return Map.of(
                            "type", "tool_use",
                            "id", b.getOrDefault("id", ""),
                            "name", b.getOrDefault("name", ""),
                            "input", b.getOrDefault("input", Map.of())
                    );
                }
                if ("tool_result".equals(type)) {
                    Object inner = b.get("content");
                    if (inner instanceof List<?> innerList) {
                        @SuppressWarnings("unchecked")
                        List<Object> filtered = (List<Object>) innerList.stream()
                                .filter(c -> !(c instanceof Map<?, ?> cm
                                        && "tool_reference".equals(
                                                ((Map<?, ?>) cm).get("type"))))
                                .collect(java.util.stream.Collectors.toList());
                        if (filtered.isEmpty()) {
                            Map<String, Object> copy = new java.util.HashMap<>(b);
                            copy.put("content",
                                    List.of(Map.of("type", "text", "text", "[tool references]")));
                            return copy;
                        }
                        if (filtered.size() != innerList.size()) {
                            Map<String, Object> copy = new java.util.HashMap<>(b);
                            copy.put("content", filtered);
                            return copy;
                        }
                    }
                }
                return block;
            }).toList();

            Map<String, Object> copy = new java.util.HashMap<>(message);
            copy.put("content", normalised);
            return copy;
        }).toList();
    }

    private Integer countTokensWithBedrock(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        // Bedrock token counting: delegate to BedrockUtils or return null
        // (full implementation requires @aws-sdk/client-bedrock-runtime equivalent)
        log.debug("[TokenEstimation] Bedrock token counting not yet implemented; returning null");
        return null;
    }

    private static boolean isEnvTruthy(String envVar) {
        String val = System.getenv(envVar);
        return val != null && !val.isBlank() && !"0".equals(val) && !"false".equalsIgnoreCase(val);
    }

    private static String getSmallFastModel() {
        String override = System.getenv("ANTHROPIC_SMALL_FAST_MODEL");
        return (override != null && !override.isBlank()) ? override : "claude-haiku-4-5";
    }

    private static String getDefaultSonnetModel() {
        return "claude-sonnet-4-5";
    }

    private static String toJsonString(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /** Returns the token threshold for agent descriptions. */
    public int getAgentDescriptionsThreshold() {
        return 10_000;
    }

    /** Returns per-agent token info for doctor diagnostics. */
    public java.util.List<AgentTokenInfo> getAgentDescriptionTokens() {
        return java.util.List.of();
    }

    /**
     * Estimate tokens for a short text (fast local approximation).
     * Approximately 4 chars = 1 token.
     */
    public int estimateTokens(String text) {
        if (text == null) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }

    /** Per-agent token usage for context-warning purposes. */
    public record AgentTokenInfo(String agentType, String source, int tokens) {
        /** Convenience alias for agentType(), used in display formatting. */
        public String name() { return agentType; }
    }
}
