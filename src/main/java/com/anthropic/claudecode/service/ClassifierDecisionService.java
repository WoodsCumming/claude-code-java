package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ToolConstants;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Classifier decision service.
 * Translated from src/utils/permissions/classifierDecision.ts
 *
 * Provides classifier-based permission decisions for tools.
 * This is ANT-ONLY in external builds.
 */
@Slf4j
@Service
public class ClassifierDecisionService {



    /**
     * Tools that are always allowed without classifier check.
     */
    private static final Set<String> ALWAYS_ALLOWED_TOOLS = Set.of(
        ToolConstants.FILE_READ_TOOL_NAME,
        ToolConstants.GLOB_TOOL_NAME,
        ToolConstants.GREP_TOOL_NAME,
        "AskUserQuestion",
        "EnterPlanMode",
        "ExitPlanMode",
        "ListMcpResourcesTool",
        "LSP",
        ToolConstants.SEND_MESSAGE_TOOL_NAME,
        "Sleep",
        ToolConstants.TASK_CREATE_TOOL_NAME,
        ToolConstants.TASK_GET_TOOL_NAME,
        ToolConstants.TASK_LIST_TOOL_NAME,
        ToolConstants.TASK_OUTPUT_TOOL_NAME,
        ToolConstants.TASK_STOP_TOOL_NAME,
        ToolConstants.TASK_UPDATE_TOOL_NAME,
        "TeamCreate",
        "TeamDelete",
        ToolConstants.TODO_WRITE_TOOL_NAME
    );

    /**
     * Check if a tool is always allowed.
     * Translated from isAlwaysAllowedTool() in classifierDecision.ts
     */
    public boolean isAlwaysAllowedTool(String toolName) {
        return ALWAYS_ALLOWED_TOOLS.contains(toolName);
    }

    /**
     * Get a classifier decision for a tool.
     * Translated from getClassifierDecision() in classifierDecision.ts
     *
     * In external builds, this always returns null (no classifier).
     */
    public ClassifierDecision getClassifierDecision(String toolName, Map<String, Object> input) {
        if (isAlwaysAllowedTool(toolName)) {
            return new ClassifierDecision("allow", "Tool is always allowed", null);
        }
        return null; // No classifier in external builds
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ClassifierDecision {
        private String behavior; // "allow" | "deny" | "ask"
        private String reason;
        private String matchedRule;

        public String getBehavior() { return behavior; }
        public void setBehavior(String v) { behavior = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
        public String getMatchedRule() { return matchedRule; }
        public void setMatchedRule(String v) { matchedRule = v; }
    }
}
