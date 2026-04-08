package com.anthropic.claudecode.model;

import java.util.Set;

/**
 * Beta header constants for the Anthropic API.
 * Translated from src/constants/betas.ts
 */
public class BetaHeaders {

    public static final String CLAUDE_CODE_20250219 = "claude-code-20250219";
    public static final String INTERLEAVED_THINKING = "interleaved-thinking-2025-05-14";
    public static final String CONTEXT_1M = "context-1m-2025-08-07";
    public static final String CONTEXT_MANAGEMENT = "context-management-2025-06-27";
    public static final String STRUCTURED_OUTPUTS = "structured-outputs-2025-12-15";
    public static final String WEB_SEARCH = "web-search-2025-03-05";
    public static final String TOOL_SEARCH_1P = "advanced-tool-use-2025-11-20";
    public static final String TOOL_SEARCH_3P = "tool-search-tool-2025-10-19";
    public static final String EFFORT = "effort-2025-11-24";
    public static final String TASK_BUDGETS = "task-budgets-2026-03-13";
    public static final String PROMPT_CACHING_SCOPE = "prompt-caching-scope-2026-01-05";
    public static final String FAST_MODE = "fast-mode-2026-02-01";
    public static final String REDACT_THINKING = "redact-thinking-2026-02-12";
    public static final String TOKEN_EFFICIENT_TOOLS = "token-efficient-tools-2026-03-28";
    public static final String ADVISOR = "advisor-tool-2026-03-01";

    /** Bedrock extra params headers (not in Bedrock headers) */
    public static final Set<String> BEDROCK_EXTRA_PARAMS_HEADERS = Set.of(
        INTERLEAVED_THINKING,
        CONTEXT_1M,
        REDACT_THINKING,
        TOKEN_EFFICIENT_TOOLS
    );

    private BetaHeaders() {}
}
