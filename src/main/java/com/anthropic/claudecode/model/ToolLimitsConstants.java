package com.anthropic.claudecode.model;

/**
 * Constants related to tool result size limits.
 * Translated from src/constants/toolLimits.ts
 */
public class ToolLimitsConstants {

    /**
     * Default maximum size in characters for tool results before they get persisted
     * to disk. When exceeded, the result is saved to a file and the model receives
     * a preview with the file path instead of the full content.
     *
     * Individual tools may declare a lower maxResultSizeChars, but this constant
     * acts as a system-wide cap regardless of what tools declare.
     */
    public static final int DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000;

    /**
     * Maximum size for tool results in tokens.
     * Based on analysis of tool result sizes, we set this to a reasonable upper bound
     * to prevent excessively large tool results from consuming too much context.
     *
     * This is approximately 400KB of text (assuming ~4 bytes per token).
     */
    public static final int MAX_TOOL_RESULT_TOKENS = 100_000;

    /**
     * Bytes per token estimate for calculating token count from byte size.
     * This is a conservative estimate - actual token count may vary.
     */
    public static final int BYTES_PER_TOKEN = 4;

    /**
     * Maximum size for tool results in bytes (derived from token limit).
     */
    public static final int MAX_TOOL_RESULT_BYTES = MAX_TOOL_RESULT_TOKENS * BYTES_PER_TOKEN;

    /**
     * Default maximum aggregate size in characters for tool_result blocks within
     * a SINGLE user message (one turn's batch of parallel tool results). When a
     * message's blocks together exceed this, the largest blocks in that message
     * are persisted to disk and replaced with previews until under budget.
     * Messages are evaluated independently.
     *
     * This prevents N parallel tools from each hitting the per-tool max and
     * collectively producing e.g. 10 × 40K = 400K in one turn's user message.
     */
    public static final int MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200_000;

    /**
     * Maximum character length for tool summary strings in compact views.
     * Used by getToolUseSummary() implementations to truncate long inputs
     * for display in grouped agent rendering.
     */
    public static final int TOOL_SUMMARY_MAX_LENGTH = 50;

    private ToolLimitsConstants() {}
}
