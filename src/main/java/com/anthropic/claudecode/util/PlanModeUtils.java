package com.anthropic.claudecode.util;

/**
 * Plan mode utilities.
 * Translated from src/utils/planModeV2.ts
 */
public class PlanModeUtils {

    /**
     * Get the plan mode v2 agent count.
     * Translated from getPlanModeV2AgentCount() in planModeV2.ts
     */
    public static int getPlanModeV2AgentCount() {
        String envCount = System.getenv("CLAUDE_CODE_PLAN_V2_AGENT_COUNT");
        if (envCount != null) {
            try {
                int count = Integer.parseInt(envCount);
                if (count > 0 && count <= 10) return count;
            } catch (NumberFormatException e) {
                // Use default
            }
        }
        return 1; // Default
    }

    /**
     * Get the plan mode v2 explore agent count.
     * Translated from getPlanModeV2ExploreAgentCount() in planModeV2.ts
     */
    public static int getPlanModeV2ExploreAgentCount() {
        String envCount = System.getenv("CLAUDE_CODE_PLAN_V2_EXPLORE_AGENT_COUNT");
        if (envCount != null) {
            try {
                int count = Integer.parseInt(envCount);
                if (count > 0 && count <= 10) return count;
            } catch (NumberFormatException e) {
                // Use default
            }
        }
        return 1; // Default
    }

    /**
     * Check if plan mode interview phase is enabled.
     * Translated from isPlanModeInterviewPhaseEnabled() in planModeV2.ts
     */
    public static boolean isPlanModeInterviewPhaseEnabled() {
        return false; // Disabled by default
    }

    /**
     * Get the Pewter Ledger variant.
     * Translated from getPewterLedgerVariant() in planModeV2.ts
     */
    public static String getPewterLedgerVariant() {
        return "default";
    }

    private PlanModeUtils() {}
}
