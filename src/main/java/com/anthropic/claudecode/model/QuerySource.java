package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Query source types for analytics.
 * Translated from QuerySource type in the codebase.
 */
public enum QuerySource {
    REPL_MAIN_THREAD("repl_main_thread"),
    SDK("sdk"),
    AGENT_CUSTOM("agent:custom"),
    AGENT_DEFAULT("agent:default"),
    AGENT_BUILTIN("agent:builtin"),
    COMPACT("compact"),
    HOOK_AGENT("hook_agent"),
    HOOK_PROMPT("hook_prompt"),
    VERIFICATION_AGENT("verification_agent"),
    SIDE_QUESTION("side_question"),
    AUTO_MODE("auto_mode"),
    TITLE_GENERATION("title_generation"),
    SUMMARY("summary"),
    SPECULATION("speculation"),
    UNKNOWN("unknown");

    private final String value;

    QuerySource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static QuerySource fromValue(String value) {
        for (QuerySource source : values()) {
            if (source.value.equals(value)) return source;
        }
        return UNKNOWN;
    }
}
