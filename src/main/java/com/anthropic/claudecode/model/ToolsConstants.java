package com.anthropic.claudecode.model;

import java.util.Set;

/**
 * Constants defining which tools are allowed or disallowed in different
 * agent execution contexts.
 * Translated from src/constants/tools.ts
 *
 * Note: The TypeScript source uses dynamic feature flags (bun:bundle feature())
 * and environment variables (USER_TYPE) to conditionally include tool names.
 * This Java translation captures the static sets that apply in the default
 * (non-ant, no feature flags active) runtime configuration.
 */
public class ToolsConstants {

    /**
     * Tools that are disallowed for all agent types (async subagents).
     * Prevents recursion and main-thread-only abstractions from being used
     * inside agents.
     */
    public static final Set<String> ALL_AGENT_DISALLOWED_TOOLS = Set.of(
        ToolConstants.TASK_OUTPUT_TOOL_NAME,
        ToolConstants.EXIT_PLAN_MODE_V2_TOOL_NAME,
        ToolConstants.ENTER_PLAN_MODE_TOOL_NAME,
        ToolConstants.AGENT_TOOL_NAME,           // allowed when USER_TYPE=ant
        ToolConstants.ASK_USER_QUESTION_TOOL_NAME,
        ToolConstants.TASK_STOP_TOOL_NAME
    );

    /**
     * Tools disallowed for custom (user-defined) agents.
     * Currently a superset of {@link #ALL_AGENT_DISALLOWED_TOOLS}.
     */
    public static final Set<String> CUSTOM_AGENT_DISALLOWED_TOOLS =
        ALL_AGENT_DISALLOWED_TOOLS;

    /**
     * Tools available to async (background) agents.
     * Excludes tools that require main-thread state or interactive terminal access.
     */
    public static final Set<String> ASYNC_AGENT_ALLOWED_TOOLS = Set.of(
        ToolConstants.FILE_READ_TOOL_NAME,
        ToolConstants.WEB_SEARCH_TOOL_NAME,
        ToolConstants.TODO_WRITE_TOOL_NAME,
        ToolConstants.GREP_TOOL_NAME,
        ToolConstants.WEB_FETCH_TOOL_NAME,
        ToolConstants.GLOB_TOOL_NAME,
        ToolConstants.BASH_TOOL_NAME,            // representative of SHELL_TOOL_NAMES
        ToolConstants.FILE_EDIT_TOOL_NAME,
        ToolConstants.FILE_WRITE_TOOL_NAME,
        ToolConstants.NOTEBOOK_EDIT_TOOL_NAME,
        ToolConstants.SKILL_TOOL_NAME,
        ToolConstants.TOOL_SEARCH_TOOL_NAME,
        ToolConstants.ENTER_WORKTREE_TOOL_NAME,
        ToolConstants.EXIT_WORKTREE_TOOL_NAME
    );

    /**
     * Tools allowed only for in-process teammates (not general async agents).
     * These are injected by inProcessRunner and filtered via isInProcessTeammate().
     *
     * Note: CRON_* tools are excluded here; they are conditionally included at
     * runtime when the AGENT_TRIGGERS feature flag is active.
     */
    public static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED_TOOLS = Set.of(
        ToolConstants.TASK_CREATE_TOOL_NAME,
        ToolConstants.TASK_GET_TOOL_NAME,
        ToolConstants.TASK_LIST_TOOL_NAME,
        ToolConstants.TASK_UPDATE_TOOL_NAME,
        ToolConstants.SEND_MESSAGE_TOOL_NAME
    );

    /**
     * Tools allowed in coordinator mode — only output and agent management tools
     * for the coordinator agent.
     */
    public static final Set<String> COORDINATOR_MODE_ALLOWED_TOOLS = Set.of(
        ToolConstants.AGENT_TOOL_NAME,
        ToolConstants.TASK_STOP_TOOL_NAME,
        ToolConstants.SEND_MESSAGE_TOOL_NAME
        // SYNTHETIC_OUTPUT_TOOL_NAME omitted — not in ToolConstants yet
    );

    private ToolsConstants() {}
}
