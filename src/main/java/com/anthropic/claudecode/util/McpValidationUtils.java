package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP validation utilities for checking and truncating tool output.
 * Translated from src/utils/mcpValidation.ts
 *
 * Provides token-count-based truncation for MCP tool results so they do not
 * overflow the model's context window.
 */
@Slf4j
public class McpValidationUtils {



    public static final double MCP_TOKEN_COUNT_THRESHOLD_FACTOR = 0.5;
    public static final int IMAGE_TOKEN_ESTIMATE = 1600;
    private static final int DEFAULT_MAX_MCP_OUTPUT_TOKENS = 25_000;

    // =========================================================================
    // MCP tool result types (sealed hierarchy)
    // =========================================================================

    /**
     * A content block within a tool result.
     * Represents a subset of Anthropic ContentBlockParam.
     */
    public sealed interface ContentBlock permits
            McpValidationUtils.ContentBlock.TextBlock,
            McpValidationUtils.ContentBlock.ImageBlock,
            McpValidationUtils.ContentBlock.OtherBlock {

        record TextBlock(String text) implements ContentBlock {}

        record ImageBlock(String mediaType, String data) implements ContentBlock {}

        record OtherBlock(String type) implements ContentBlock {}
    }

    /**
     * MCP tool result: either a plain string or a list of content blocks.
     * Equivalent to TypeScript's: string | ContentBlockParam[] | undefined
     */
    public sealed interface McpToolResult permits
            McpValidationUtils.McpToolResult.StringResult,
            McpValidationUtils.McpToolResult.BlocksResult,
            McpValidationUtils.McpToolResult.EmptyResult {

        record StringResult(String value) implements McpToolResult {}
        record BlocksResult(List<ContentBlock> blocks) implements McpToolResult {}
        record EmptyResult() implements McpToolResult {}
    }

    // =========================================================================
    // Token cap
    // =========================================================================

    /**
     * Resolve the MCP output token cap.
     * Precedence: MAX_MCP_OUTPUT_TOKENS env var → hardcoded default.
     * Translated from getMaxMcpOutputTokens() in mcpValidation.ts
     */
    public static int getMaxMcpOutputTokens() {
        String envValue = System.getenv("MAX_MCP_OUTPUT_TOKENS");
        if (envValue != null && !envValue.isBlank()) {
            try {
                int parsed = Integer.parseInt(envValue.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                log.warn("Invalid MAX_MCP_OUTPUT_TOKENS value: {}", envValue);
            }
        }
        return DEFAULT_MAX_MCP_OUTPUT_TOKENS;
    }

    // =========================================================================
    // Content size estimation
    // =========================================================================

    /**
     * Estimate the token count of a tool result.
     * Translated from getContentSizeEstimate() in mcpValidation.ts
     */
    public static int getContentSizeEstimate(McpToolResult content) {
        return switch (content) {
            case McpToolResult.EmptyResult ignored -> 0;
            case McpToolResult.StringResult sr -> roughTokenCountEstimation(sr.value());
            case McpToolResult.BlocksResult br -> br.blocks().stream().mapToInt(block ->
                    switch (block) {
                        case ContentBlock.TextBlock tb -> roughTokenCountEstimation(tb.text());
                        case ContentBlock.ImageBlock ignored -> IMAGE_TOKEN_ESTIMATE;
                        case ContentBlock.OtherBlock ignored -> 0;
                    }).sum();
        };
    }

    /**
     * Rough token count heuristic: ~4 characters per token.
     */
    private static int roughTokenCountEstimation(String text) {
        if (text == null) return 0;
        return text.length() / 4;
    }

    // =========================================================================
    // Truncation
    // =========================================================================

    private static int getMaxMcpOutputChars() {
        return getMaxMcpOutputTokens() * 4;
    }

    private static String getTruncationMessage() {
        return "\n\n[OUTPUT TRUNCATED - exceeded " + getMaxMcpOutputTokens() + " token limit]\n\n" +
               "The tool output was truncated. If this MCP server provides pagination or " +
               "filtering tools, use them to retrieve specific portions of the data. If " +
               "pagination is not available, inform the user that you are working with " +
               "truncated output and results may be incomplete.";
    }

    private static String truncateString(String content, int maxChars) {
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars);
    }

    /**
     * Truncate content blocks to fit within maxChars.
     * Translated from truncateContentBlocks() in mcpValidation.ts
     */
    private static List<ContentBlock> truncateContentBlocks(List<ContentBlock> blocks, int maxChars) {
        java.util.List<ContentBlock> result = new java.util.ArrayList<>();
        AtomicInteger currentChars = new AtomicInteger(0);

        for (ContentBlock block : blocks) {
            switch (block) {
                case ContentBlock.TextBlock tb -> {
                    int remaining = maxChars - currentChars.get();
                    if (remaining <= 0) return result;
                    if (tb.text().length() <= remaining) {
                        result.add(tb);
                        currentChars.addAndGet(tb.text().length());
                    } else {
                        result.add(new ContentBlock.TextBlock(tb.text().substring(0, remaining)));
                        return result;
                    }
                }
                case ContentBlock.ImageBlock img -> {
                    int imageChars = IMAGE_TOKEN_ESTIMATE * 4;
                    if (currentChars.get() + imageChars <= maxChars) {
                        result.add(img);
                        currentChars.addAndGet(imageChars);
                    }
                    // If image exceeds budget we skip it (compression not available in this layer)
                }
                case ContentBlock.OtherBlock other -> result.add(other);
            }
        }
        return result;
    }

    /**
     * Check whether MCP content needs truncation.
     * Translated from mcpContentNeedsTruncation() in mcpValidation.ts
     */
    public static CompletableFuture<Boolean> mcpContentNeedsTruncation(McpToolResult content) {
        if (content instanceof McpToolResult.EmptyResult) {
            return CompletableFuture.completedFuture(false);
        }

        int estimate = getContentSizeEstimate(content);
        if (estimate <= getMaxMcpOutputTokens() * MCP_TOKEN_COUNT_THRESHOLD_FACTOR) {
            return CompletableFuture.completedFuture(false);
        }

        // Without a live API token counter we fall back to the char-based heuristic
        int charEstimate = switch (content) {
            case McpToolResult.StringResult sr -> sr.value().length();
            case McpToolResult.BlocksResult br -> br.blocks().stream().mapToInt(b ->
                    b instanceof ContentBlock.TextBlock tb ? tb.text().length() : IMAGE_TOKEN_ESTIMATE * 4
            ).sum();
            default -> 0;
        };
        return CompletableFuture.completedFuture(charEstimate > getMaxMcpOutputChars());
    }

    /**
     * Truncate MCP content to the configured token limit.
     * Translated from truncateMcpContent() in mcpValidation.ts
     */
    public static CompletableFuture<McpToolResult> truncateMcpContent(McpToolResult content) {
        if (content instanceof McpToolResult.EmptyResult) {
            return CompletableFuture.completedFuture(content);
        }

        int maxChars = getMaxMcpOutputChars();
        String truncationMsg = getTruncationMessage();

        return switch (content) {
            case McpToolResult.StringResult sr -> CompletableFuture.completedFuture(
                    new McpToolResult.StringResult(truncateString(sr.value(), maxChars) + truncationMsg));
            case McpToolResult.BlocksResult br -> {
                List<ContentBlock> truncated = truncateContentBlocks(br.blocks(), maxChars);
                truncated.add(new ContentBlock.TextBlock(truncationMsg));
                yield CompletableFuture.completedFuture(new McpToolResult.BlocksResult(truncated));
            }
            default -> CompletableFuture.completedFuture(content);
        };
    }

    /**
     * Truncate MCP content only if it exceeds the token limit.
     * Translated from truncateMcpContentIfNeeded() in mcpValidation.ts
     */
    public static CompletableFuture<McpToolResult> truncateMcpContentIfNeeded(McpToolResult content) {
        return mcpContentNeedsTruncation(content).thenCompose(needsTruncation -> {
            if (!needsTruncation) return CompletableFuture.completedFuture(content);
            return truncateMcpContent(content);
        });
    }

    private McpValidationUtils() {}
}
