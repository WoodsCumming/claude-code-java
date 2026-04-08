package com.anthropic.claudecode.model;

/**
 * Tool result size limit constants.
 * Translated from src/constants/toolLimits.ts
 */
public class ToolLimits {

    /** Default maximum size in characters for tool results before persistence */
    public static final int DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000;

    /** Maximum size for tool results in tokens */
    public static final int MAX_TOOL_RESULT_TOKENS = 100_000;

    /** Bytes per token estimate */
    public static final int BYTES_PER_TOKEN = 4;

    /** Maximum size for tool results in bytes */
    public static final int MAX_TOOL_RESULT_BYTES = MAX_TOOL_RESULT_TOKENS * BYTES_PER_TOKEN;

    /** Maximum aggregate size for tool_result blocks in a single user message */
    public static final int MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200_000;

    /** Maximum character length for tool summary strings */
    public static final int TOOL_SUMMARY_MAX_LENGTH = 50;

    private ToolLimits() {}
}
