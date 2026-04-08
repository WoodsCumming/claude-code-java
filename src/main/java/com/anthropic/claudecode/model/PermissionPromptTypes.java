package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.*;

/**
 * Permission prompt tool types.
 * Translated from src/utils/permissions/PermissionPromptToolResultSchema.ts
 */
public class PermissionPromptTypes {

    /**
     * Input for the permission prompt tool.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PermissionPromptInput {
        @JsonProperty("tool_name")
        private String toolName;
        private Map<String, Object> input;
        @JsonProperty("tool_use_id")
        private String toolUseId;
        @JsonProperty("permission_mode")
        private String permissionMode;

        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public Map<String, Object> getInput() { return input; }
        public void setInput(Map<String, Object> v) { input = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String v) { permissionMode = v; }
    }

    /**
     * Output from the permission prompt tool.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PermissionPromptOutput {
        private String behavior; // "allow" | "deny" | "ask"
        @JsonProperty("updated_input")
        private Map<String, Object> updatedInput;
        @JsonProperty("permission_updates")
        private List<Map<String, Object>> permissionUpdates;

        public String getBehavior() { return behavior; }
        public void setBehavior(String v) { behavior = v; }
        public Map<String, Object> getUpdatedInput() { return updatedInput; }
        public void setUpdatedInput(Map<String, Object> v) { updatedInput = v; }
        public List<Map<String, Object>> getPermissionUpdates() { return permissionUpdates; }
        public void setPermissionUpdates(List<Map<String, Object>> v) { permissionUpdates = v; }
    }
}
