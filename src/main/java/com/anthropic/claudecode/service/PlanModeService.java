package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Plan mode V2 management service.
 * Translated from src/utils/planModeV2.ts
 *
 * Provides configuration for plan mode agent counts, interview phase,
 * and the pewter-ledger plan file structure experiment.
 */
@Slf4j
@Service
public class PlanModeService {



    // =========================================================================
    // Plan mode state
    // =========================================================================

    private volatile boolean inPlanMode = false;
    private volatile String planDescription = null;
    private volatile String planContent = null;
    private volatile String planFilePath = null;

    /**
     * Check if plan mode is currently active.
     */
    public boolean isInPlanMode() {
        return inPlanMode;
    }

    /**
     * Enable plan mode.
     */
    public void enablePlanMode() {
        this.inPlanMode = true;
        if (this.planFilePath == null) {
            this.planFilePath = System.getProperty("user.dir", "") + "/.claude/plan.md";
        }
    }

    /**
     * Disable plan mode.
     */
    public void disablePlanMode() {
        this.inPlanMode = false;
    }

    /**
     * Set the task description for the current plan.
     */
    public void setPlanDescription(String description) {
        this.planDescription = description;
    }

    /**
     * Get the current plan description.
     */
    public String getPlanDescription() {
        return planDescription;
    }

    /**
     * Get the current plan content (from the plan file if it exists).
     */
    public String getCurrentPlan() {
        if (planFilePath == null) return null;
        try {
            java.io.File f = new java.io.File(planFilePath);
            if (!f.exists()) return null;
            return java.nio.file.Files.readString(f.toPath());
        } catch (Exception e) {
            log.debug("Could not read plan file: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the path to the current plan file.
     */
    public String getPlanFilePath() {
        return planFilePath;
    }

    /**
     * Set plan file path explicitly.
     */
    public void setPlanFilePath(String path) {
        this.planFilePath = path;
    }

    /**
     * Get the configured editor name from environment variables.
     */
    public String getConfiguredEditorName() {
        String visual = System.getenv("VISUAL");
        if (visual != null && !visual.isBlank()) return visual;
        String editor = System.getenv("EDITOR");
        if (editor != null && !editor.isBlank()) return editor;
        return null;
    }

    // =========================================================================
    // Agent count configuration
    // =========================================================================

    /**
     * Get the number of agents to use in plan mode V2.
     * Environment variable CLAUDE_CODE_PLAN_V2_AGENT_COUNT takes precedence.
     * Translated from getPlanModeV2AgentCount() in planModeV2.ts
     */
    public int getPlanModeV2AgentCount() {
        String envVal = System.getenv("CLAUDE_CODE_PLAN_V2_AGENT_COUNT");
        if (envVal != null) {
            try {
                int count = Integer.parseInt(envVal);
                if (count > 0 && count <= 10) {
                    return count;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to subscription-based logic
            }
        }

        String subscriptionType = getSubscriptionType();
        String rateLimitTier = getRateLimitTier();

        if ("max".equals(subscriptionType) && "default_claude_max_20x".equals(rateLimitTier)) {
            return 3;
        }

        if ("enterprise".equals(subscriptionType) || "team".equals(subscriptionType)) {
            return 3;
        }

        return 1;
    }

    /**
     * Get the number of explore agents to use in plan mode V2.
     * Environment variable CLAUDE_CODE_PLAN_V2_EXPLORE_AGENT_COUNT takes precedence.
     * Translated from getPlanModeV2ExploreAgentCount() in planModeV2.ts
     */
    public int getPlanModeV2ExploreAgentCount() {
        String envVal = System.getenv("CLAUDE_CODE_PLAN_V2_EXPLORE_AGENT_COUNT");
        if (envVal != null) {
            try {
                int count = Integer.parseInt(envVal);
                if (count > 0 && count <= 10) {
                    return count;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to default
            }
        }
        return 3;
    }

    // =========================================================================
    // Interview phase
    // =========================================================================

    /**
     * Check if plan mode interview phase is enabled.
     * Config: ant=always_on, external=tengu_plan_mode_interview_phase gate, envVar=true
     * Translated from isPlanModeInterviewPhaseEnabled() in planModeV2.ts
     */
    public boolean isPlanModeInterviewPhaseEnabled() {
        // Always on for ants
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            return true;
        }

        String env = System.getenv("CLAUDE_CODE_PLAN_MODE_INTERVIEW_PHASE");
        if (isEnvTruthy(env)) return true;
        if (isEnvDefinedFalsy(env)) return false;

        // Feature flag check — defaults to false when not configured
        return getFeatureFlag("tengu_plan_mode_interview_phase", false);
    }

    // =========================================================================
    // Pewter Ledger experiment
    // =========================================================================

    /**
     * Possible variants for the tengu_pewter_ledger plan file structure experiment.
     * Translated from PewterLedgerVariant union type in planModeV2.ts.
     * CONTROL maps to null (the control arm).
     */
    public enum PewterLedgerVariant {
        CONTROL,
        TRIM,
        CUT,
        CAP
    }

    /**
     * Get the active pewter ledger variant for the plan file structure experiment.
     *
     * Controls Phase 4 "Final Plan" bullets in the 5-phase plan mode workflow.
     * Arms: CONTROL (null), TRIM, CUT, CAP — progressively stricter guidance on
     * plan file size.
     *
     * Translated from getPewterLedgerVariant() in planModeV2.ts
     */
    public PewterLedgerVariant getPewterLedgerVariant() {
        String raw = getFeatureFlagString("tengu_pewter_ledger", null);
        if ("trim".equals(raw)) return PewterLedgerVariant.TRIM;
        if ("cut".equals(raw)) return PewterLedgerVariant.CUT;
        if ("cap".equals(raw)) return PewterLedgerVariant.CAP;
        return PewterLedgerVariant.CONTROL;
    }

    // =========================================================================
    // Private helpers — thin wrappers that mirror TS auth/envUtils imports
    // =========================================================================

    private String getSubscriptionType() {
        // In production this would delegate to AuthService / auth token claims
        return System.getenv().getOrDefault("CLAUDE_SUBSCRIPTION_TYPE", "");
    }

    private String getRateLimitTier() {
        // In production this would delegate to AuthService
        return System.getenv().getOrDefault("CLAUDE_RATE_LIMIT_TIER", "");
    }

    /**
     * Returns true if the env value is a recognised truthy string (1, true, yes, on).
     * Mirrors isEnvTruthy() in envUtils.ts
     */
    private static boolean isEnvTruthy(String value) {
        if (value == null) return false;
        return value.equalsIgnoreCase("1") ||
               value.equalsIgnoreCase("true") ||
               value.equalsIgnoreCase("yes") ||
               value.equalsIgnoreCase("on");
    }

    /**
     * Returns true if the env value is defined AND a recognised falsy string (0, false, no, off).
     * Mirrors isEnvDefinedFalsy() in envUtils.ts
     */
    private static boolean isEnvDefinedFalsy(String value) {
        if (value == null) return false;
        return value.equalsIgnoreCase("0") ||
               value.equalsIgnoreCase("false") ||
               value.equalsIgnoreCase("no") ||
               value.equalsIgnoreCase("off");
    }

    /**
     * Stub for GrowthBook / feature-flag lookup.
     * In production, wire this to the real GrowthBook service.
     * Mirrors getFeatureValue_CACHED_MAY_BE_STALE() in growthbook.ts
     */
    private static boolean getFeatureFlag(String key, boolean defaultValue) {
        // Feature flag integration point
        return defaultValue;
    }

    private static String getFeatureFlagString(String key, String defaultValue) {
        // Feature flag integration point
        return defaultValue;
    }
}
