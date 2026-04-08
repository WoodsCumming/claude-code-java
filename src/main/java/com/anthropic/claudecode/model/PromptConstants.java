package com.anthropic.claudecode.model;

/**
 * Prompt-related constants extracted from the system prompt configuration.
 * Translated from src/constants/prompts.ts
 *
 * Note: The bulk of prompts.ts contains runtime functions that dynamically
 * compose the system prompt from environment data, enabled tools, and feature
 * flags. Only the standalone exported constants are represented here.
 */
public class PromptConstants {

    /** URL for the Claude Code documentation map. */
    public static final String CLAUDE_CODE_DOCS_MAP_URL =
        "https://code.claude.com/docs/en/claude_code_docs_map.md";

    /**
     * Boundary marker separating static (cross-org cacheable) content from
     * dynamic content in the system prompt array.
     *
     * Everything BEFORE this marker can use scope: 'global'.
     * Everything AFTER contains user/session-specific content and should not
     * be globally cached.
     */
    public static final String SYSTEM_PROMPT_DYNAMIC_BOUNDARY =
        "__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__";

    /**
     * Default system prompt used when running Claude Code as an agent (subagent
     * / async agent context).
     */
    public static final String DEFAULT_AGENT_PROMPT =
        "You are an agent for Claude Code, Anthropic's official CLI for Claude. "
        + "Given the user's message, you should use the tools available to complete "
        + "the task. Complete the task fully\u2014don't gold-plate, but don't leave it "
        + "half-done. When you complete the task, respond with a concise report "
        + "covering what was done and any key findings \u2014 the caller will relay "
        + "this to the user, so it only needs the essentials.";

    private PromptConstants() {}
}
