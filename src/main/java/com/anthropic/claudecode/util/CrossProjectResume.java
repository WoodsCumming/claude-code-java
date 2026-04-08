package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Cross-project resume utilities.
 * Translated from src/utils/crossProjectResume.ts
 *
 * Checks if a session is from a different project directory and
 * determines how to resume it.
 */
public class CrossProjectResume {

    public sealed interface CrossProjectResumeResult
        permits CrossProjectResumeResult.SameProject, CrossProjectResumeResult.SameRepoWorktree, CrossProjectResumeResult.DifferentProject {

        record SameProject() implements CrossProjectResumeResult {}

        record SameRepoWorktree(String projectPath) implements CrossProjectResumeResult {
            public boolean isCrossProject() { return true; }
            public boolean isSameRepoWorktree() { return true; }
        }

        record DifferentProject(String command, String projectPath) implements CrossProjectResumeResult {
        }
    }

    /**
     * Check if a session is from a different project.
     * Translated from checkCrossProjectResume() in crossProjectResume.ts
     */
    public static CrossProjectResumeResult checkCrossProjectResume(
            String sessionProjectPath,
            String currentCwd,
            boolean showAllProjects,
            List<String> worktreePaths) {

        if (!showAllProjects || sessionProjectPath == null
                || sessionProjectPath.equals(currentCwd)) {
            return new CrossProjectResumeResult.SameProject();
        }

        // Check if it's a worktree of the same repo
        for (String worktreePath : worktreePaths) {
            if (sessionProjectPath.equals(worktreePath)) {
                return new CrossProjectResumeResult.SameRepoWorktree(sessionProjectPath);
            }
        }

        // Different project - generate cd command
        String command = "cd " + ShellQuoteUtils.quoteSingleArg(sessionProjectPath);
        return new CrossProjectResumeResult.DifferentProject(command, sessionProjectPath);
    }

    private CrossProjectResume() {}
}
