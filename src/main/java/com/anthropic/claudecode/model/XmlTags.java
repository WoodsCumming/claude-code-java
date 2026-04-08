package com.anthropic.claudecode.model;

import java.util.List;

/**
 * XML tag constants used in messages.
 * Translated from src/constants/xml.ts
 */
public class XmlTags {

    // Command metadata tags
    public static final String COMMAND_NAME_TAG = "command-name";
    public static final String COMMAND_MESSAGE_TAG = "command-message";
    public static final String COMMAND_ARGS_TAG = "command-args";

    // Terminal/bash tags
    public static final String BASH_INPUT_TAG = "bash-input";
    public static final String BASH_STDOUT_TAG = "bash-stdout";
    public static final String BASH_STDERR_TAG = "bash-stderr";
    public static final String LOCAL_COMMAND_STDOUT_TAG = "local-command-stdout";
    public static final String LOCAL_COMMAND_STDERR_TAG = "local-command-stderr";
    public static final String LOCAL_COMMAND_CAVEAT_TAG = "local-command-caveat";

    public static final List<String> TERMINAL_OUTPUT_TAGS = List.of(
        BASH_INPUT_TAG, BASH_STDOUT_TAG, BASH_STDERR_TAG,
        LOCAL_COMMAND_STDOUT_TAG, LOCAL_COMMAND_STDERR_TAG, LOCAL_COMMAND_CAVEAT_TAG
    );

    // Other tags
    public static final String TICK_TAG = "tick";
    public static final String TASK_NOTIFICATION_TAG = "task-notification";
    public static final String TASK_ID_TAG = "task-id";
    public static final String TOOL_USE_ID_TAG = "tool-use-id";
    public static final String TASK_TYPE_TAG = "task-type";
    public static final String OUTPUT_FILE_TAG = "output-file";
    public static final String STATUS_TAG = "status";
    public static final String SUMMARY_TAG = "summary";
    public static final String REASON_TAG = "reason";
    public static final String WORKTREE_TAG = "worktree";
    public static final String TEAMMATE_MESSAGE_TAG = "teammate-message";
    public static final String CHANNEL_MESSAGE_TAG = "channel-message";
    public static final String CHANNEL_TAG = "channel";
    public static final String CROSS_SESSION_MESSAGE_TAG = "cross-session-message";
    public static final String FORK_BOILERPLATE_TAG = "fork-boilerplate";
    public static final String FORK_DIRECTIVE_PREFIX = "Your directive: ";

    // Common slash command arguments
    public static final List<String> COMMON_HELP_ARGS = List.of("help", "-h", "--help");
    public static final List<String> COMMON_INFO_ARGS = List.of(
        "list", "show", "display", "current", "view", "get", "check",
        "describe", "print", "version", "about", "status", "?"
    );

    private XmlTags() {}
}
