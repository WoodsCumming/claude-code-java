package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.Map;

/**
 * Session state types.
 * Translated from src/utils/sessionState.ts
 */
public class SessionStateTypes {

    public enum SessionState {
        IDLE("idle"),
        RUNNING("running"),
        REQUIRES_ACTION("requires_action");

        private final String value;
        SessionState(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Context for requires_action transitions.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequiresActionDetails {
        @JsonProperty("tool_name")
        private String toolName;

        @JsonProperty("action_description")
        private String actionDescription;

        @JsonProperty("tool_use_id")
        private String toolUseId;

        @JsonProperty("request_id")
        private String requestId;

        @JsonProperty("input")
        private Map<String, Object> input;

        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public String getActionDescription() { return actionDescription; }
        public void setActionDescription(String v) { actionDescription = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public Map<String, Object> getInput() { return input; }
        public void setInput(Map<String, Object> v) { input = v; }
    }

    /**
     * Session external metadata for CCR.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionExternalMetadata {
        @JsonProperty("permission_mode")
        private String permissionMode;

        @JsonProperty("is_ultraplan_mode")
        private Boolean isUltraplanMode;

        @JsonProperty("model")
        private String model;

        @JsonProperty("pending_action")
        private RequiresActionDetails pendingAction;

        @JsonProperty("post_turn_summary")
        private Object postTurnSummary;

        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String v) { permissionMode = v; }
        public boolean isIsUltraplanMode() { return isUltraplanMode; }
        public void setIsUltraplanMode(Boolean v) { isUltraplanMode = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public RequiresActionDetails getPendingAction() { return pendingAction; }
        public void setPendingAction(RequiresActionDetails v) { pendingAction = v; }
        public Object getPostTurnSummary() { return postTurnSummary; }
        public void setPostTurnSummary(Object v) { postTurnSummary = v; }
    }
}
