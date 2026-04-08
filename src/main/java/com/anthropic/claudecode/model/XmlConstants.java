package com.anthropic.claudecode.model;

import java.util.List;

/**
 * XML tag name constants used in messages and structured output.
 * Translated from src/constants/xml.ts
 */
public class XmlConstants {

    // XML tag names used to mark skill/command metadata in messages
    public static final String COMMAND_NAME_TAG = "command-name";
    public static final String COMMAND_MESSAGE_TAG = "command-message";
    public static final String COMMAND_ARGS_TAG = "command-args";

    // XML tag names for terminal/bash command input and output in user messages.
    // These wrap content that represents terminal activity, not actual user prompts.
    public static final String BASH_INPUT_TAG = "bash-input";
    public static final String BASH_STDOUT_TAG = "bash-stdout";
    public static final String BASH_STDERR_TAG = "bash-stderr";
    public static final String LOCAL_COMMAND_STDOUT_TAG = "local-command-stdout";
    public static final String LOCAL_COMMAND_STDERR_TAG = "local-command-stderr";
    public static final String LOCAL_COMMAND_CAVEAT_TAG = "local-command-caveat";

    /**
     * All terminal-related tags that indicate a message is terminal output, not a user prompt.
     */
    public static final List<String> TERMINAL_OUTPUT_TAGS = List.of(
        BASH_INPUT_TAG,
        BASH_STDOUT_TAG,
        BASH_STDERR_TAG,
        LOCAL_COMMAND_STDOUT_TAG,
        LOCAL_COMMAND_STDERR_TAG,
        LOCAL_COMMAND_CAVEAT_TAG
    );

    public static final String TICK_TAG = "tick";

    // XML tag names for task notifications (background task completions)
    public static final String TASK_NOTIFICATION_TAG = "task-notification";
    public static final String TASK_ID_TAG = "task-id";
    public static final String TOOL_USE_ID_TAG = "tool-use-id";
    public static final String TASK_TYPE_TAG = "task-type";
    public static final String OUTPUT_FILE_TAG = "output-file";
    public static final String STATUS_TAG = "status";
    public static final String SUMMARY_TAG = "summary";
    public static final String REASON_TAG = "reason";
    public static final String WORKTREE_TAG = "worktree";
    public static final String WORKTREE_PATH_TAG = "worktreePath";
    public static final String WORKTREE_BRANCH_TAG = "worktreeBranch";

    // XML tag names for ultraplan mode (remote parallel planning sessions)
    public static final String ULTRAPLAN_TAG = "ultraplan";

    // XML tag name for remote /review results (teleported review session output).
    // Remote session wraps its final review in this tag; local poller extracts it.
    public static final String REMOTE_REVIEW_TAG = "remote-review";

    // run_hunt.sh's heartbeat echoes the orchestrator's progress.json inside this
    // tag every ~10s. Local poller parses the latest for the task-status line.
    public static final String REMOTE_REVIEW_PROGRESS_TAG = "remote-review-progress";

    // XML tag name for teammate messages (swarm inter-agent communication)
    public static final String TEAMMATE_MESSAGE_TAG = "teammate-message";

    // XML tag name for external channel messages
    public static final String CHANNEL_MESSAGE_TAG = "channel-message";
    public static final String CHANNEL_TAG = "channel";

    // XML tag name for cross-session UDS messages (another Claude session's inbox)
    public static final String CROSS_SESSION_MESSAGE_TAG = "cross-session-message";

    // XML tag wrapping the rules/format boilerplate in a fork child's first message.
    // Lets the transcript renderer collapse the boilerplate and show only the directive.
    public static final String FORK_BOILERPLATE_TAG = "fork-boilerplate";

    // Prefix before the directive text, stripped by the renderer. Keep in sync
    // across buildChildMessage (generates) and UserForkBoilerplateMessage (parses).
    public static final String FORK_DIRECTIVE_PREFIX = "Your directive: ";

    // Common argument patterns for slash commands that request help
    public static final List<String> COMMON_HELP_ARGS = List.of("help", "-h", "--help");

    // Common argument patterns for slash commands that request current state/info
    public static final List<String> COMMON_INFO_ARGS = List.of(
        "list", "show", "display", "current", "view", "get", "check",
        "describe", "print", "version", "about", "status", "?"
    );

    private XmlConstants() {}
}
