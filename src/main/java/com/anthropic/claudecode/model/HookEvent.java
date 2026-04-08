package com.anthropic.claudecode.model;

/**
 * Hook event types for Claude Code lifecycle hooks.
 * Translated from HOOK_EVENTS in src/entrypoints/sdk/coreTypes.ts
 */
public enum HookEvent {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure"),
    NOTIFICATION("Notification"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    SESSION_START("SessionStart"),
    SESSION_END("SessionEnd"),
    STOP("Stop"),
    STOP_FAILURE("StopFailure"),
    SUBAGENT_START("SubagentStart"),
    SUBAGENT_STOP("SubagentStop"),
    PRE_COMPACT("PreCompact"),
    POST_COMPACT("PostCompact"),
    PERMISSION_REQUEST("PermissionRequest"),
    PERMISSION_DENIED("PermissionDenied"),
    SETUP("Setup"),
    TEAMMATE_IDLE("TeammateIdle"),
    TASK_CREATED("TaskCreated"),
    TASK_COMPLETED("TaskCompleted"),
    ELICITATION("Elicitation"),
    ELICITATION_RESULT("ElicitationResult"),
    CONFIG_CHANGE("ConfigChange"),
    WORKTREE_CREATE("WorktreeCreate"),
    WORKTREE_REMOVE("WorktreeRemove"),
    INSTRUCTIONS_LOADED("InstructionsLoaded"),
    CWD_CHANGED("CwdChanged"),
    FILE_CHANGED("FileChanged");

    private final String value;

    HookEvent(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static HookEvent fromValue(String value) {
        for (HookEvent event : values()) {
            if (event.value.equals(value)) {
                return event;
            }
        }
        throw new IllegalArgumentException("Unknown hook event: " + value);
    }
}
