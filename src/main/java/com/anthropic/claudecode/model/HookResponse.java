package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * Hook response types for Claude Code hooks.
 * Translated from src/types/hooks.ts syncHookResponseSchema and asyncHookResponseSchema
 */
@Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HookResponse {

    /**
     * Whether Claude should continue after hook (default: true).
     */
    @JsonProperty("continue")
    private Boolean continueExecution;

    /**
     * Hide stdout from transcript (default: false).
     */
    @JsonProperty("suppressOutput")
    private Boolean suppressOutput;

    /**
     * Reason for stopping (if continue is false).
     */
    @JsonProperty("stopReason")
    private String stopReason;

    /**
     * Permission updates to apply.
     */
    @JsonProperty("permissionUpdate")
    private Map<String, Object> permissionUpdate;

    /**
     * Whether this is an async hook response.
     */
    @JsonProperty("async")
    private Boolean async;

    /**
     * Hook output for display.
     */
    @JsonProperty("output")
    private String output;

    /**
     * Tool result override (for PostToolUse hooks).
     */
    @JsonProperty("toolResult")
    private Map<String, Object> toolResult;

    /**
     * Prompt response (for elicitation hooks).
     */
    @JsonProperty("prompt_response")
    private String promptResponse;

    /**
     * Selected option (for elicitation hooks).
     */
    @JsonProperty("selected")
    private String selected;

        public boolean isContinueExecution() { return continueExecution; }
        public void setContinueExecution(Boolean v) { continueExecution = v; }
        public boolean isSuppressOutput() { return suppressOutput; }
        public void setSuppressOutput(Boolean v) { suppressOutput = v; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String v) { stopReason = v; }
        public Map<String, Object> getPermissionUpdate() { return permissionUpdate; }
        public void setPermissionUpdate(Map<String, Object> v) { permissionUpdate = v; }
        public boolean isAsync() { return async; }
        public void setAsync(Boolean v) { async = v; }
        public String getOutput() { return output; }
        public void setOutput(String v) { output = v; }
        public Map<String, Object> getToolResult() { return toolResult; }
        public void setToolResult(Map<String, Object> v) { toolResult = v; }
        public String getPromptResponse() { return promptResponse; }
        public void setPromptResponse(String v) { promptResponse = v; }
        public String getSelected() { return selected; }
        public void setSelected(String v) { selected = v; }
}
