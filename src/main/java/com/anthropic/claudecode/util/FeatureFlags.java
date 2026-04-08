package com.anthropic.claudecode.util;

/**
 * Feature flag utilities.
 * Translated from various feature flag checks in the codebase.
 */
public class FeatureFlags {

    /**
     * Check if agent swarms (multi-agent teams) are enabled.
     * Translated from isAgentSwarmsEnabled() in agentSwarmsEnabled.ts
     */
    public static boolean isAgentSwarmsEnabled() {
        // Check for opt-in via environment variable
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"))
            || hasAgentTeamsFlag();
    }

    /**
     * Check if worktree mode is enabled.
     * Translated from isWorktreeModeEnabled() in worktreeModeEnabled.ts
     */
    public static boolean isWorktreeModeEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_WORKTREE_MODE"));
    }

    /**
     * Check if the REPL bridge is active.
     * Translated from isReplBridgeActive() in bootstrap/state.ts
     */
    public static boolean isReplBridgeActive() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_REPL_BRIDGE"));
    }

    /**
     * Check if running in non-interactive session.
     */
    public static boolean isNonInteractiveSession() {
        return EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_NON_INTERACTIVE"))
            || System.console() == null;
    }

    private static boolean hasAgentTeamsFlag() {
        // Check command line arguments
        String[] args = System.getProperty("sun.java.command", "").split("\\s+");
        for (String arg : args) {
            if ("--agent-teams".equals(arg)) return true;
        }
        return false;
    }

    private FeatureFlags() {}
}
