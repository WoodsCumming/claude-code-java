package com.anthropic.claudecode.util;

/**
 * Worktree mode enablement utilities.
 * Translated from src/utils/worktreeModeEnabled.ts
 *
 * Worktree mode is unconditionally enabled for all users.
 */
public class WorktreeModeEnabled {

    /**
     * Check if worktree mode is enabled.
     * Translated from isWorktreeModeEnabled() in worktreeModeEnabled.ts
     *
     * Worktree mode is now unconditionally enabled for all users.
     */
    public static boolean isWorktreeModeEnabled() {
        return true;
    }

    private WorktreeModeEnabled() {}
}
