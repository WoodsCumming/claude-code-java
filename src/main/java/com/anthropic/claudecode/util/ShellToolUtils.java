package com.anthropic.claudecode.util;

import java.util.List;

/**
 * Shell tool utilities.
 * Translated from src/utils/shell/shellToolUtils.ts
 */
public class ShellToolUtils {

    public static final List<String> SHELL_TOOL_NAMES = List.of("Bash", "PowerShell");

    /**
     * Check if PowerShell tool is enabled.
     * Translated from isPowerShellToolEnabled() in shellToolUtils.ts
     */
    public static boolean isPowerShellToolEnabled() {
        if (!PlatformUtils.isWindows()) return false;
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_POWERSHELL_TOOL"));
    }

    private ShellToolUtils() {}
}
