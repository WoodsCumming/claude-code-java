package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Tool validation configuration.
 * Translated from src/utils/settings/toolValidationConfig.ts
 *
 * Defines which tools accept file glob patterns vs bash prefix patterns.
 */
public class ToolValidationConfig {

    /**
     * Tools that accept file glob patterns (e.g., *.ts, src/**).
     */
    public static final Set<String> FILE_PATTERN_TOOLS = Set.of(
        "Read", "Write", "Edit", "Glob", "Grep", "NotebookEdit"
    );

    /**
     * Tools that accept bash wildcard patterns and legacy :* prefix syntax.
     */
    public static final Set<String> BASH_PREFIX_TOOLS = Set.of(
        "Bash", "PowerShell"
    );

    /**
     * Check if a tool accepts file glob patterns.
     * Translated from isFilePatternTool() in toolValidationConfig.ts
     */
    public static boolean isFilePatternTool(String toolName) {
        return FILE_PATTERN_TOOLS.contains(toolName);
    }

    /**
     * Check if a tool accepts bash prefix patterns.
     * Translated from isBashPrefixTool() in toolValidationConfig.ts
     */
    public static boolean isBashPrefixTool(String toolName) {
        return BASH_PREFIX_TOOLS.contains(toolName);
    }

    private ToolValidationConfig() {}
}
