package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Status notice helper utilities.
 * Translated from src/utils/statusNoticeHelpers.ts
 *
 * Calculates token estimates for agent descriptions.
 */
public class StatusNoticeHelpers {

    public static final int AGENT_DESCRIPTIONS_THRESHOLD = 15_000;

    /**
     * Calculate cumulative token estimate for agent descriptions.
     * Translated from getAgentDescriptionsTotalTokens() in statusNoticeHelpers.ts
     */
    public static int getAgentDescriptionsTotalTokens(List<Map<String, Object>> agentDefinitions) {
        if (agentDefinitions == null || agentDefinitions.isEmpty()) return 0;

        int total = 0;
        for (Map<String, Object> agent : agentDefinitions) {
            String source = (String) agent.get("source");
            if ("built-in".equals(source)) continue;

            String agentType = (String) agent.getOrDefault("agentType", "");
            String whenToUse = (String) agent.getOrDefault("whenToUse", "");
            String description = agentType + ": " + whenToUse;

            // Rough token estimation: ~4 chars per token
            total += description.length() / 4;
        }

        return total;
    }

    private StatusNoticeHelpers() {}
}
