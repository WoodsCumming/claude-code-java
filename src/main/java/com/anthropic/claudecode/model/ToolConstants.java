package com.anthropic.claudecode.model;

/**
 * Tool name and other constants.
 * Translated from various tool prompt.ts and constants.ts files.
 */
public class ToolConstants {

    // Tool names
    public static final String FILE_READ_TOOL_NAME = "Read";
    public static final String FILE_EDIT_TOOL_NAME = "Edit";
    public static final String FILE_WRITE_TOOL_NAME = "Write";
    public static final String BASH_TOOL_NAME = "Bash";
    public static final String GLOB_TOOL_NAME = "Glob";
    public static final String GREP_TOOL_NAME = "Grep";
    public static final String WEB_FETCH_TOOL_NAME = "WebFetch";
    public static final String WEB_SEARCH_TOOL_NAME = "WebSearch";
    public static final String AGENT_TOOL_NAME = "Agent";
    public static final String LEGACY_AGENT_TOOL_NAME = "dispatch_agent";
    public static final String NOTEBOOK_EDIT_TOOL_NAME = "NotebookEdit";
    public static final String TODO_WRITE_TOOL_NAME = "TodoWrite";
    public static final String TASK_CREATE_TOOL_NAME = "TaskCreate";
    public static final String TASK_UPDATE_TOOL_NAME = "TaskUpdate";
    public static final String TASK_LIST_TOOL_NAME = "TaskList";
    public static final String TASK_GET_TOOL_NAME = "TaskGet";
    public static final String TASK_OUTPUT_TOOL_NAME = "TaskOutput";
    public static final String TASK_STOP_TOOL_NAME = "TaskStop";
    public static final String CRON_CREATE_TOOL_NAME = "CronCreate";
    public static final String CRON_DELETE_TOOL_NAME = "CronDelete";
    public static final String CRON_LIST_TOOL_NAME = "CronList";
    public static final String SLEEP_TOOL_NAME = "Sleep";
    public static final String SEND_MESSAGE_TOOL_NAME = "SendMessage";
    public static final String CONFIG_TOOL_NAME = "Config";
    public static final String ENTER_PLAN_MODE_TOOL_NAME = "EnterPlanMode";
    public static final String EXIT_PLAN_MODE_V2_TOOL_NAME = "ExitPlanMode";
    public static final String ENTER_WORKTREE_TOOL_NAME = "EnterWorktree";
    public static final String EXIT_WORKTREE_TOOL_NAME = "ExitWorktree";
    public static final String TEAM_CREATE_TOOL_NAME = "TeamCreate";
    public static final String TEAM_DELETE_TOOL_NAME = "TeamDelete";
    public static final String ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion";
    public static final String SKILL_TOOL_NAME = "Skill";
    public static final String REPL_TOOL_NAME = "REPL";
    public static final String TOOL_SEARCH_TOOL_NAME = "ToolSearch";

    // File tool constants
    public static final String CLAUDE_FOLDER_PERMISSION_PATTERN = "/.claude/**";
    public static final String GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN = "~/.claude/**";
    public static final String FILE_UNEXPECTEDLY_MODIFIED_ERROR =
        "File has been unexpectedly modified. Read it again before attempting to write it.";
    public static final String FILE_UNCHANGED_STUB =
        "File unchanged since last read. The content from the earlier Read tool_result in this conversation is still current — refer to that instead of re-reading.";

    // File read limits
    public static final int MAX_LINES_TO_READ = 2000;

    private ToolConstants() {}
}
