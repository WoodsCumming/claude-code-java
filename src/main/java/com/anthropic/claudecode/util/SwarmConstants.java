package com.anthropic.claudecode.util;

/**
 * Swarm constants for multi-agent coordination.
 * Translated from src/utils/swarm/constants.ts
 */
public class SwarmConstants {

    public static final String TEAM_LEAD_NAME = "team-lead";
    public static final String SWARM_SESSION_NAME = "claude-swarm";
    public static final String SWARM_VIEW_WINDOW_NAME = "swarm-view";
    public static final String TMUX_COMMAND = "tmux";
    public static final String HIDDEN_SESSION_NAME = "claude-hidden";

    public static final String TEAMMATE_COMMAND_ENV_VAR = "CLAUDE_CODE_TEAMMATE_COMMAND";
    public static final String TEAMMATE_COLOR_ENV_VAR = "CLAUDE_CODE_AGENT_COLOR";
    public static final String PLAN_MODE_REQUIRED_ENV_VAR = "CLAUDE_CODE_PLAN_MODE_REQUIRED";

    /**
     * Get the swarm socket name.
     * Translated from getSwarmSocketName() in constants.ts
     */
    public static String getSwarmSocketName() {
        return "claude-swarm-" + ProcessHandle.current().pid();
    }

    private SwarmConstants() {}
}
