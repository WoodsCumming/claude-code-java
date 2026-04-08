package com.anthropic.claudecode.util.powershell;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects potentially destructive PowerShell commands and returns a warning
 * string for display in the permission dialog. This is purely informational
 * — it does not affect permission logic or auto-approval.
 *
 * Translated from src/tools/PowerShellTool/destructiveCommandWarning.ts
 */
@Slf4j
public final class PowerShellDestructiveWarning {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PowerShellDestructiveWarning.class);


    /**
     * A compiled destructive-pattern entry pairing a regex with its human-readable warning.
     */
    private record DestructivePattern(Pattern pattern, String warning) {}

    /**
     * All known destructive-command patterns, checked in declaration order.
     * The first matching pattern's warning is returned.
     */
    private static final List<DestructivePattern> DESTRUCTIVE_PATTERNS = List.of(

            // Remove-Item with -Recurse AND -Force (and common aliases rm/del/rd/rmdir/ri).
            // Anchored to statement start so `git rm --force` doesn't match.
            new DestructivePattern(
                    Pattern.compile(
                            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Recurse\\b[^|;&\\n}]*-Force\\b",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may recursively force-remove files"),

            new DestructivePattern(
                    Pattern.compile(
                            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Force\\b[^|;&\\n}]*-Recurse\\b",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may recursively force-remove files"),

            new DestructivePattern(
                    Pattern.compile(
                            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Recurse\\b",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may recursively remove files"),

            new DestructivePattern(
                    Pattern.compile(
                            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Force\\b",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may force-remove files"),

            // Clear-Content on broad paths
            new DestructivePattern(
                    Pattern.compile("\\bClear-Content\\b[^|;&\\n]*\\*", Pattern.CASE_INSENSITIVE),
                    "Note: may clear content of multiple files"),

            // Format-Volume and Clear-Disk
            new DestructivePattern(
                    Pattern.compile("\\bFormat-Volume\\b", Pattern.CASE_INSENSITIVE),
                    "Note: may format a disk volume"),

            new DestructivePattern(
                    Pattern.compile("\\bClear-Disk\\b", Pattern.CASE_INSENSITIVE),
                    "Note: may clear a disk"),

            // Git destructive operations (same as BashTool)
            new DestructivePattern(
                    Pattern.compile("\\bgit\\s+reset\\s+--hard\\b", Pattern.CASE_INSENSITIVE),
                    "Note: may discard uncommitted changes"),

            new DestructivePattern(
                    Pattern.compile(
                            "\\bgit\\s+push\\b[^|;&\\n]*\\s+(--force|--force-with-lease|-f)\\b",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may overwrite remote history"),

            new DestructivePattern(
                    Pattern.compile(
                            "\\bgit\\s+clean\\b(?![^|;&\\n]*(?:-[a-zA-Z]*n|--dry-run))[^|;&\\n]*-[a-zA-Z]*f",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may permanently delete untracked files"),

            new DestructivePattern(
                    Pattern.compile("\\bgit\\s+stash\\s+(drop|clear)\\b", Pattern.CASE_INSENSITIVE),
                    "Note: may permanently remove stashed changes"),

            // Database operations
            new DestructivePattern(
                    Pattern.compile("\\b(DROP|TRUNCATE)\\s+(TABLE|DATABASE|SCHEMA)\\b",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may drop or truncate database objects"),

            // System operations
            new DestructivePattern(
                    Pattern.compile("\\bStop-Computer\\b", Pattern.CASE_INSENSITIVE),
                    "Note: will shut down the computer"),

            new DestructivePattern(
                    Pattern.compile("\\bRestart-Computer\\b", Pattern.CASE_INSENSITIVE),
                    "Note: will restart the computer"),

            new DestructivePattern(
                    Pattern.compile("\\bClear-RecycleBin\\b", Pattern.CASE_INSENSITIVE),
                    "Note: permanently deletes recycled files")
    );

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Checks if a PowerShell command matches known destructive patterns.
     *
     * @param command the PowerShell command string to inspect
     * @return a human-readable warning string, or {@code null} if no destructive
     *         pattern is detected
     */
    public static String getDestructiveCommandWarning(String command) {
        if (command == null || command.isBlank()) return null;
        for (DestructivePattern entry : DESTRUCTIVE_PATTERNS) {
            if (entry.pattern().matcher(command).find()) {
                return entry.warning();
            }
        }
        return null;
    }

    private PowerShellDestructiveWarning() {}
}
