package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Hook-related types for Claude Code lifecycle hooks.
 * Translated from src/types/hooks.ts
 *
 * The existing {@link HookEvent} enum and {@link HookResponse} class cover the
 * event enum and basic sync/async response. This class provides the remaining
 * types: PromptRequest/Response, callback wrappers, HookResult, and aggregated
 * results.
 */
public class HooksTypes {

    // -------------------------------------------------------------------------
    // Prompt elicitation protocol
    // -------------------------------------------------------------------------

    /**
     * Option presented to the user during a prompt-elicitation request.
     */
    public record PromptOption(
        String key,
        String label,
        String description
    ) {}

    /**
     * Prompt-elicitation request sent by a hook.
     * The {@code prompt} field acts as the request ID discriminator.
     */
    public record PromptRequest(
        String prompt,   // request id
        String message,
        List<PromptOption> options
    ) {}

    /**
     * Response to a prompt-elicitation request.
     */
    public record PromptResponse(
        @JsonProperty("prompt_response") String promptResponse,  // request id
        String selected
    ) {}

    // -------------------------------------------------------------------------
    // Permission request result
    // -------------------------------------------------------------------------

    /**
     * Result of a permission-request hook decision.
     * Models the TypeScript union:
     *   | { behavior: 'allow'; updatedInput?; updatedPermissions? }
     *   | { behavior: 'deny';  message?; interrupt? }
     */
    public sealed interface PermissionRequestResult
        permits PermissionRequestResult.Allow,
                PermissionRequestResult.Deny {

        record Allow(
            Map<String, Object> updatedInput,
            List<Map<String, Object>> updatedPermissions
        ) implements PermissionRequestResult {}

        record Deny(
            String message,
            Boolean interrupt
        ) implements PermissionRequestResult {}
    }

    // -------------------------------------------------------------------------
    // HookCallback — programmatic (in-process) hook
    // -------------------------------------------------------------------------

    /**
     * A matcher-scoped collection of hook callbacks.
     */
    @Data
    @lombok.Builder
    
    public static class HookCallbackMatcher {
        private String matcher;
        private List<HookCallbackEntry> hooks;
        private String pluginName;
    }

    /**
     * A single hook callback entry (type = "callback").
     *
     * @param timeout  Optional timeout in seconds for this hook.
     * @param internal Internal hooks are excluded from hook-run metrics.
     */
    @Data
    @lombok.Builder
    
    public static class HookCallbackEntry {
        private String type;   // "callback"
        private Integer timeout;
        private Boolean internal;
    }

    // -------------------------------------------------------------------------
    // HookProgress — progress events emitted during hook execution
    // -------------------------------------------------------------------------

    /**
     * Progress event emitted while a hook is running.
     */
    public record HookProgress(
        String type,            // "hook_progress"
        HookEvent hookEvent,
        String hookName,
        String command,
        String promptText,
        String statusMessage
    ) {}

    // -------------------------------------------------------------------------
    // HookBlockingError
    // -------------------------------------------------------------------------

    /**
     * A blocking error produced by a hook (prevents Claude from continuing).
     */
    public record HookBlockingError(
        String blockingError,
        String command
    ) {}

    // -------------------------------------------------------------------------
    // HookResult — result of executing one hook
    // -------------------------------------------------------------------------

    /**
     * Outcome values for a hook execution.
     */
    public enum HookOutcome {
        SUCCESS("success"),
        BLOCKING("blocking"),
        NON_BLOCKING_ERROR("non_blocking_error"),
        CANCELLED("cancelled");

        private final String value;

        HookOutcome(String value) { this.value = value; }

        public String getValue() { return value; }
    }

    /**
     * Permission behavior values used in hook results.
     */
    public enum PermissionBehavior {
        ASK("ask"),
        DENY("deny"),
        ALLOW("allow"),
        PASSTHROUGH("passthrough");

        private final String value;

        PermissionBehavior(String value) { this.value = value; }

    }

    /**
     * The result of executing a single hook.
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HookResult {
        private Message message;
        private Message systemMessage;
        private HookBlockingError blockingError;
        private HookOutcome outcome;
        private Boolean preventContinuation;
        private String stopReason;
        private PermissionBehavior permissionBehavior;
        private String hookPermissionDecisionReason;
        private String additionalContext;
        private String initialUserMessage;
        private Map<String, Object> updatedInput;
        private Object updatedMCPToolOutput;
        private PermissionRequestResult permissionRequestResult;
        private Boolean retry;
    }

    // -------------------------------------------------------------------------
    // AggregatedHookResult — merged result from multiple hooks for one event
    // -------------------------------------------------------------------------

    /**
     * Merged result of executing all hooks registered for a single event.
     */
    @Data
    @lombok.Builder
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AggregatedHookResult {
        private Message message;
        private List<HookBlockingError> blockingErrors;
        private Boolean preventContinuation;
        private String stopReason;
        private String hookPermissionDecisionReason;
        private PermissionBehavior permissionBehavior;
        private List<String> additionalContexts;
        private String initialUserMessage;
        private Map<String, Object> updatedInput;
        private Object updatedMCPToolOutput;
        private PermissionRequestResult permissionRequestResult;
        private Boolean retry;
    }

    private HooksTypes() {}
}
