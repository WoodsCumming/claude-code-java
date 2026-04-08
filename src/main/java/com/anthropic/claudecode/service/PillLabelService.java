package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Pill label service for background task UI.
 * Translated from src/tasks/pillLabel.ts
 *
 * Produces compact footer-pill labels for background tasks.
 * Used by both the footer pill and the turn-duration transcript line
 * so the two surfaces agree on terminology.
 */
@Slf4j
@Service
public class PillLabelService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PillLabelService.class);


    // Unicode diamond characters — matches figures.ts constants
    private static final String DIAMOND_FILLED = "\u25C6"; // ◆
    private static final String DIAMOND_OPEN   = "\u25C7"; // ◇

    // =========================================================================
    // getPillLabel
    // Translated from getPillLabel() in pillLabel.ts
    // =========================================================================

    /**
     * Produces the compact footer-pill label for a set of background tasks.
     * Translated from getPillLabel() in pillLabel.ts
     *
     * Each task map must have at minimum:
     *   "type" : String   — one of the TaskType values
     * Shell tasks additionally use:
     *   "kind" : "monitor" | null
     * Teammate tasks additionally use:
     *   "identity" : Map<String, Object> with "teamName" : String
     * Remote-agent tasks additionally use:
     *   "isUltraplan"    : Boolean
     *   "ultraplanPhase" : "plan_ready" | "needs_input" | null
     */
    public String getPillLabel(List<Map<String, Object>> tasks) {
        if (tasks == null || tasks.isEmpty()) return "";

        int n = tasks.size();
        String firstType = (String) tasks.get(0).get("type");
        boolean allSameType = tasks.stream()
                .allMatch(t -> firstType.equals(t.get("type")));

        if (allSameType) {
            return switch (firstType) {
                case "local_bash" -> buildBashLabel(tasks, n);
                case "in_process_teammate" -> buildTeammateLabel(tasks);
                case "local_agent" -> n == 1 ? "1 local agent" : n + " local agents";
                case "remote_agent" -> buildRemoteAgentLabel(tasks, n);
                case "local_workflow" -> n == 1 ? "1 background workflow" : n + " background workflows";
                case "monitor_mcp" -> n == 1 ? "1 monitor" : n + " monitors";
                case "dream" -> "dreaming";
                default -> n + " background " + (n == 1 ? "task" : "tasks");
            };
        }

        // Mixed types
        return n + " background " + (n == 1 ? "task" : "tasks");
    }

    // =========================================================================
    // pillNeedsCta
    // Translated from pillNeedsCta() in pillLabel.ts
    // =========================================================================

    /**
     * True when the pill should show the dimmed " · ↓ to view" call-to-action.
     * Only the two attention states (needs_input, plan_ready) for an ultraplan
     * task surface the CTA; plain running shows just the diamond + label.
     * Translated from pillNeedsCta() in pillLabel.ts
     */
    public boolean pillNeedsCta(List<Map<String, Object>> tasks) {
        if (tasks == null || tasks.size() != 1) return false;
        Map<String, Object> t = tasks.get(0);
        return "remote_agent".equals(t.get("type"))
                && Boolean.TRUE.equals(t.get("isUltraplan"))
                && t.get("ultraplanPhase") != null;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String buildBashLabel(List<Map<String, Object>> tasks, int n) {
        long monitors = tasks.stream()
                .filter(t -> "monitor".equals(t.get("kind")))
                .count();
        long shells = n - monitors;
        List<String> parts = new ArrayList<>();
        if (shells > 0)   parts.add(shells == 1   ? "1 shell"   : shells   + " shells");
        if (monitors > 0) parts.add(monitors == 1 ? "1 monitor" : monitors + " monitors");
        return String.join(", ", parts);
    }

    private String buildTeammateLabel(List<Map<String, Object>> tasks) {
        Set<String> teamNames = new HashSet<>();
        for (Map<String, Object> t : tasks) {
            Object identity = t.get("identity");
            if (identity instanceof Map<?, ?> identityMap) {
                Object teamName = identityMap.get("teamName");
                if (teamName instanceof String s) teamNames.add(s);
            }
        }
        int teamCount = teamNames.isEmpty() ? 1 : teamNames.size();
        return teamCount == 1 ? "1 team" : teamCount + " teams";
    }

    private String buildRemoteAgentLabel(List<Map<String, Object>> tasks, int n) {
        if (n == 1) {
            Map<String, Object> first = tasks.get(0);
            if (Boolean.TRUE.equals(first.get("isUltraplan"))) {
                String phase = (String) first.get("ultraplanPhase");
                if ("plan_ready".equals(phase)) {
                    return DIAMOND_FILLED + " ultraplan ready";
                } else if ("needs_input".equals(phase)) {
                    return DIAMOND_OPEN + " ultraplan needs your input";
                } else {
                    return DIAMOND_OPEN + " ultraplan";
                }
            }
            return DIAMOND_OPEN + " 1 cloud session";
        }
        return DIAMOND_OPEN + " " + n + " cloud sessions";
    }
}
