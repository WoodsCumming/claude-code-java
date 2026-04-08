package com.anthropic.claudecode.util;

import java.util.Set;

/**
 * PowerShell utilities for command analysis and security.
 * Translated from src/utils/powershell/dangerousCmdlets.ts and parser.ts
 */
public class PowerShellUtils {

    /**
     * Cmdlets that accept a -FilePath and execute the file's contents as a script.
     */
    public static final Set<String> FILEPATH_EXECUTION_CMDLETS = Set.of(
        "invoke-command",
        "start-job",
        "start-threadjob",
        "register-scheduledjob"
    );

    /**
     * Cmdlets where a scriptblock argument executes arbitrary code.
     */
    public static final Set<String> DANGEROUS_SCRIPT_BLOCK_CMDLETS = Set.of(
        "invoke-command",
        "invoke-expression",
        "start-job",
        "start-threadjob",
        "register-scheduledjob",
        "register-engineevent",
        "register-objectevent",
        "register-wmievent",
        "new-pssession",
        "enter-pssession"
    );

    /**
     * Common PowerShell aliases.
     */
    public static final java.util.Map<String, String> COMMON_ALIASES = java.util.Map.ofEntries(
        java.util.Map.entry("iex", "invoke-expression"),
        java.util.Map.entry("icm", "invoke-command"),
        java.util.Map.entry("sajb", "start-job"),
        java.util.Map.entry("gcm", "get-command"),
        java.util.Map.entry("gal", "get-alias"),
        java.util.Map.entry("cat", "get-content"),
        java.util.Map.entry("cd", "set-location"),
        java.util.Map.entry("cls", "clear-host"),
        java.util.Map.entry("cp", "copy-item"),
        java.util.Map.entry("del", "remove-item"),
        java.util.Map.entry("dir", "get-childitem"),
        java.util.Map.entry("echo", "write-output"),
        java.util.Map.entry("ls", "get-childitem"),
        java.util.Map.entry("man", "get-help"),
        java.util.Map.entry("mv", "move-item"),
        java.util.Map.entry("ps", "get-process"),
        java.util.Map.entry("pwd", "get-location"),
        java.util.Map.entry("rm", "remove-item"),
        java.util.Map.entry("type", "get-content")
    );

    /**
     * Check if a PowerShell command contains dangerous cmdlets.
     */
    public static boolean containsDangerousCmdlets(String command) {
        if (command == null) return false;
        String lowerCommand = command.toLowerCase();

        for (String cmdlet : DANGEROUS_SCRIPT_BLOCK_CMDLETS) {
            if (lowerCommand.contains(cmdlet)) {
                return true;
            }
        }
        // Check aliases
        for (java.util.Map.Entry<String, String> entry : COMMON_ALIASES.entrySet()) {
            if (DANGEROUS_SCRIPT_BLOCK_CMDLETS.contains(entry.getValue())) {
                if (lowerCommand.contains(entry.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    private PowerShellUtils() {}
}
