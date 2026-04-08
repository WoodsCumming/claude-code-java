package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects potentially destructive bash commands and returns a warning string
 * for display in the permission dialog. This is purely informational — it
 * does not affect permission logic or auto-approval.
 *
 * Translated from src/tools/BashTool/destructiveCommandWarning.ts
 */
@Slf4j
@Service
public class DestructiveCommandWarningService {



    /**
     * Pair of (compiled pattern, warning message).
     */
    private record DestructivePattern(Pattern pattern, String warning) {}

    private static final List<DestructivePattern> DESTRUCTIVE_PATTERNS = List.of(

        // -----------------------------------------------------------------------
        // Git — data loss / hard to reverse
        // -----------------------------------------------------------------------
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+reset\\s+--hard\\b"),
            "Note: may discard uncommitted changes"
        ),
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+push\\b[^;&|\\n]*[ \\t](--force|--force-with-lease|-f)\\b"),
            "Note: may overwrite remote history"
        ),
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+clean\\b(?![^;&|\\n]*(?:-[a-zA-Z]*n|--dry-run))[^;&|\\n]*-[a-zA-Z]*f"),
            "Note: may permanently delete untracked files"
        ),
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+checkout\\s+(--\\s+)?\\.[ \\t]*($|[;&|\\n])"),
            "Note: may discard all working tree changes"
        ),
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+restore\\s+(--\\s+)?\\.[ \\t]*($|[;&|\\n])"),
            "Note: may discard all working tree changes"
        ),
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+stash[ \\t]+(drop|clear)\\b"),
            "Note: may permanently remove stashed changes"
        ),
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+branch\\s+(-D[ \\t]|--delete\\s+--force|--force\\s+--delete)\\b"),
            "Note: may force-delete a branch"
        ),

        // -----------------------------------------------------------------------
        // Git — safety bypass
        // -----------------------------------------------------------------------
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+(commit|push|merge)\\b[^;&|\\n]*--no-verify\\b"),
            "Note: may skip safety hooks"
        ),
        new DestructivePattern(
            Pattern.compile("\\bgit\\s+commit\\b[^;&|\\n]*--amend\\b"),
            "Note: may rewrite the last commit"
        ),

        // -----------------------------------------------------------------------
        // File deletion
        // -----------------------------------------------------------------------
        new DestructivePattern(
            Pattern.compile("(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*[rR][a-zA-Z]*f|(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*f[a-zA-Z]*[rR]"),
            "Note: may recursively force-remove files"
        ),
        new DestructivePattern(
            Pattern.compile("(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*[rR]"),
            "Note: may recursively remove files"
        ),
        new DestructivePattern(
            Pattern.compile("(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*f"),
            "Note: may force-remove files"
        ),

        // -----------------------------------------------------------------------
        // Database
        // -----------------------------------------------------------------------
        new DestructivePattern(
            Pattern.compile("(?i)\\b(DROP|TRUNCATE)\\s+(TABLE|DATABASE|SCHEMA)\\b"),
            "Note: may drop or truncate database objects"
        ),
        new DestructivePattern(
            Pattern.compile("(?i)\\bDELETE\\s+FROM\\s+\\w+[ \\t]*(;|\"|'|\\n|$)"),
            "Note: may delete all rows from a database table"
        ),

        // -----------------------------------------------------------------------
        // Infrastructure
        // -----------------------------------------------------------------------
        new DestructivePattern(
            Pattern.compile("\\bkubectl\\s+delete\\b"),
            "Note: may delete Kubernetes resources"
        ),
        new DestructivePattern(
            Pattern.compile("\\bterraform\\s+destroy\\b"),
            "Note: may destroy Terraform infrastructure"
        )
    );

    /**
     * Checks if a bash command matches known destructive patterns.
     * Returns a human-readable warning string, or {@code null} if no destructive
     * pattern is detected.
     *
     * @param command the bash command to check
     * @return warning string or {@code null}
     */
    public String getDestructiveCommandWarning(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        for (DestructivePattern dp : DESTRUCTIVE_PATTERNS) {
            if (dp.pattern().matcher(command).find()) {
                return dp.warning();
            }
        }
        return null;
    }
}
