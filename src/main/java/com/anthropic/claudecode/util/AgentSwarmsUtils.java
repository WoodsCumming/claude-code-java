package com.anthropic.claudecode.util;

/**
 * Agent swarms (multi-agent teams) enablement utilities.
 * Translated from src/utils/agentSwarmsEnabled.ts
 *
 * Centralized runtime check for agent teams/teammate features.
 * This is the single gate that should be checked everywhere teammates
 * are referenced (prompts, code, tools isEnabled, UI, etc.).
 *
 * Ant builds: always enabled.
 * External builds require both:
 *   1. Opt-in via CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS env var OR --agent-teams flag
 *   2. GrowthBook gate 'tengu_amber_flint' enabled (killswitch)
 */
public class AgentSwarmsUtils {

    /** GrowthBook feature key acting as the killswitch for external users. */
    private static final String KILLSWITCH_FEATURE = "tengu_amber_flint";

    /**
     * Centralized runtime check for agent swarms (multi-agent teams).
     * Translated from isAgentSwarmsEnabled() in agentSwarmsEnabled.ts
     *
     * @return true if agent swarms are enabled for the current runtime context
     */
    public static boolean isAgentSwarmsEnabled() {
        // Ant users: always enabled
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            return true;
        }

        // External: require opt-in via env var or --agent-teams flag
        if (!EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"))
                && !isAgentTeamsFlagSet()) {
            return false;
        }

        // Killswitch — always respected for external users.
        // GrowthBook gate defaults to true (enabled) when the feature is absent,
        // matching the TS behaviour: getFeatureValue_CACHED_MAY_BE_STALE('tengu_amber_flint', true)
        if (!getKillswitchValue()) {
            return false;
        }

        return true;
    }

    /**
     * Check if the --agent-teams CLI flag was provided.
     * Checks process arguments rather than AppState to avoid import cycles.
     * Translated from isAgentTeamsFlagSet() in agentSwarmsEnabled.ts
     */
    private static boolean isAgentTeamsFlagSet() {
        // In Java this is surfaced via a system property set by the CLI argument parser
        return "true".equals(System.getProperty("claude.agent-teams"));
    }

    /**
     * Get the GrowthBook killswitch value for external users.
     * Defaults to true (enabled) when the feature flag is not configured,
     * matching the TypeScript default parameter behaviour.
     *
     * This method can be overridden in tests or replaced with a real
     * GrowthBook SDK call once that is wired up.
     */
    static boolean getKillswitchValue() {
        // Default: enabled unless explicitly disabled via system property (for testing)
        String override = System.getProperty("claude.feature." + KILLSWITCH_FEATURE);
        if (override != null) {
            return !"false".equalsIgnoreCase(override);
        }
        // Production: assume enabled until a real GrowthBook integration is in place
        return true;
    }

    private AgentSwarmsUtils() {}
}
