package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Dangerous shell pattern constants.
 * Translated from src/utils/permissions/dangerousPatterns.ts
 *
 * Pattern lists for dangerous shell-tool allow-rule prefixes.
 */
public class DangerousPatterns {

    /**
     * Cross-platform code-execution entry points.
     */
    public static final List<String> CROSS_PLATFORM_CODE_EXEC = List.of(
        // Interpreters
        "python", "python3", "python2",
        "node", "deno", "tsx",
        "ruby", "perl", "php", "lua",
        // Package runners
        "npx", "bunx", "pipx", "uvx",
        // Shells
        "bash", "sh", "zsh", "fish", "dash", "ksh",
        // Build tools
        "make", "cmake", "gradle", "maven", "mvn",
        // Other
        "java", "javac", "go", "rustc", "cargo",
        "dotnet", "mono"
    );

    /**
     * Unix-specific code-execution entry points.
     */
    public static final List<String> UNIX_CODE_EXEC = List.of(
        "eval", "exec", "source", ".",
        "xargs", "env", "sudo", "su"
    );

    /**
     * Check if a command is a dangerous code execution entry point.
     */
    public static boolean isDangerousCodeExec(String command) {
        if (command == null) return false;
        String lower = command.toLowerCase().trim();
        String baseCmd = lower.split("\\s+")[0];

        return CROSS_PLATFORM_CODE_EXEC.contains(baseCmd)
            || UNIX_CODE_EXEC.contains(baseCmd);
    }

    private DangerousPatterns() {}
}
