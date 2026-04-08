package com.anthropic.claudecode.model;

import java.util.Set;

/**
 * System constants for the Claude Code CLI.
 * Translated from src/constants/system.ts
 */
public class SystemConstants {

    public static final String DEFAULT_PREFIX =
        "You are Claude Code, Anthropic's official CLI for Claude.";

    public static final String AGENT_SDK_CLAUDE_CODE_PRESET_PREFIX =
        "You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK.";

    public static final String AGENT_SDK_PREFIX =
        "You are a Claude agent, built on Anthropic's Claude Agent SDK.";

    public static final Set<String> CLI_SYSPROMPT_PREFIXES = Set.of(
        DEFAULT_PREFIX,
        AGENT_SDK_CLAUDE_CODE_PRESET_PREFIX,
        AGENT_SDK_PREFIX
    );

    /**
     * Get the CLI system prompt prefix.
     * Translated from getCLISyspromptPrefix() in system.ts
     */
    public static String getCLISyspromptPrefix(boolean isNonInteractive, boolean hasAppendSystemPrompt) {
        if (isNonInteractive) {
            if (hasAppendSystemPrompt) {
                return AGENT_SDK_CLAUDE_CODE_PRESET_PREFIX;
            }
            return AGENT_SDK_PREFIX;
        }
        return DEFAULT_PREFIX;
    }

    /**
     * Get the attribution header.
     * Translated from getAttributionHeader() in system.ts
     */
    public static String getAttributionHeader() {
        // Simplified version - full implementation would check GrowthBook
        return "claude-code-attribution";
    }

    private SystemConstants() {}
}
