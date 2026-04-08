package com.anthropic.claudecode.util;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Read-only command validation utilities.
 * Translated from src/utils/shell/readOnlyCommandValidation.ts
 *
 * Validates shell commands to ensure they are read-only and safe to execute.
 */
public class ReadOnlyCommandValidation {

    private static final Pattern UNC_PATH_PATTERN = Pattern.compile("\\\\\\\\[^\\\\]+\\\\");

    /**
     * Check if a path contains a vulnerable UNC path.
     * Translated from containsVulnerableUncPath() in readOnlyCommandValidation.ts
     *
     * UNC paths like \\server\share can be used for credential leaks via
     * NTLM authentication when accessed on Windows.
     */
    public static boolean containsVulnerableUncPath(String command) {
        if (command == null) return false;
        return UNC_PATH_PATTERN.matcher(command).find();
    }

    /**
     * Check if a git command is read-only.
     */
    public static boolean isReadOnlyGitCommand(String command) {
        if (command == null) return false;
        String lower = command.trim().toLowerCase();

        // Read-only git subcommands
        Set<String> readOnlySubcommands = Set.of(
            "status", "log", "diff", "show", "branch", "tag", "remote",
            "stash", "ls-files", "ls-tree", "cat-file", "rev-parse",
            "describe", "shortlog", "blame", "annotate", "archive",
            "bisect", "check-ignore", "check-attr", "config --get",
            "config --list", "for-each-ref", "ls-remote", "name-rev",
            "rev-list", "submodule status", "worktree list"
        );

        if (!lower.startsWith("git ")) return false;
        String subCommand = lower.substring(4).trim();

        return readOnlySubcommands.stream().anyMatch(subCommand::startsWith);
    }

    /**
     * Check if a command is in the list of allowed read-only commands.
     */
    public static boolean isAllowedReadOnlyCommand(String command) {
        if (command == null) return false;
        String lower = command.trim().toLowerCase();

        Set<String> allowedPrefixes = Set.of(
            "cat ", "head ", "tail ", "grep ", "find ", "ls ", "pwd",
            "echo ", "printf ", "wc ", "sort ", "uniq ", "diff ",
            "file ", "stat ", "du ", "df ", "ps ", "env", "printenv",
            "which ", "type ", "man ", "help ", "date", "uname",
            "git ", "gh ", "docker ps", "kubectl get", "npm list",
            "pip list", "pip show", "pip freeze"
        );

        return allowedPrefixes.stream().anyMatch(lower::startsWith);
    }

    private ReadOnlyCommandValidation() {}
}
