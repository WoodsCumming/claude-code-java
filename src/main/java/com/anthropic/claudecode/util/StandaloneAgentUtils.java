package com.anthropic.claudecode.util;

import com.anthropic.claudecode.service.AppState;

/**
 * Standalone agent utilities.
 * Translated from src/utils/standaloneAgent.ts
 *
 * Helpers for sessions with custom names/colors that are NOT part of a swarm team.
 */
public class StandaloneAgentUtils {

    /**
     * Get the standalone agent name if set and not a swarm teammate.
     * Translated from getStandaloneAgentName() in standaloneAgent.ts
     */
    public static String getStandaloneAgentName(AppState appState) {
        // If in a team (swarm), don't return standalone name
        if (TeammateContext.getTeamName() != null) {
            return null;
        }
        return appState.getStandaloneAgentName();
    }

    private StandaloneAgentUtils() {}
}
