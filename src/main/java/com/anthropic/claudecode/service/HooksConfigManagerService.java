package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.HookEvent;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Hooks configuration manager service.
 * Translated from src/utils/hooks.ts
 *
 * User-defined shell commands executed at various points in Claude Code's lifecycle.
 * Manages hook configuration, execution metadata, and trust/security checks.
 */
@Slf4j
@Service
public class HooksConfigManagerService {



    /** Default timeout for tool hooks: 10 minutes. */
    public static final long TOOL_HOOK_EXECUTION_TIMEOUT_MS = 10 * 60 * 1000L;

    /**
     * Default timeout for SessionEnd hooks.
     * These run during shutdown and need a tight bound.
     * Overridable via CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS.
     */
    private static final long SESSION_END_HOOK_TIMEOUT_MS_DEFAULT = 1500L;

    /**
     * Get the effective SessionEnd hook timeout in milliseconds.
     * Translated from getSessionEndHookTimeoutMs() in hooks.ts
     */
    public static long getSessionEndHookTimeoutMs() {
        String raw = System.getenv("CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS");
        if (raw != null) {
            try {
                long parsed = Long.parseLong(raw);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return SESSION_END_HOOK_TIMEOUT_MS_DEFAULT;
    }

    // =========================================================================
    // Hook-output data structures
    // =========================================================================

    /**
     * Permission behavior decision from a hook.
     * Translated from the permissionBehavior field in HookResult.
     */
    public enum PermissionBehavior {
        ASK, DENY, ALLOW, PASSTHROUGH
    }

    /**
     * Aggregated outcome code from one hook execution.
     * Translated from HookResult.outcome in hooks.ts
     */
    public enum HookOutcome {
        SUCCESS, BLOCKING, NON_BLOCKING_ERROR, CANCELLED
    }

    /**
     * A hook blocking error with the command that produced it.
     * Translated from HookBlockingError in hooks.ts
     */
    public static class HookBlockingError {
        private final String blockingError;
        private final String command;

        public HookBlockingError(String blockingError, String command) {
            this.blockingError = blockingError; this.command = command;
        }
        public String getBlockingError() { return blockingError; }
        public String getCommand() { return command; }
    }

    /**
     * Result from executing a single hook.
     * Translated from HookResult in hooks.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HookResult {
        private Object message;
        private String systemMessage;
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
        private Object permissionRequestResult;
        private Object elicitationResponse;
        private List<String> watchPaths;
        private Object elicitationResultResponse;
        private Boolean retry;
        private Object hook;

        public Object getMessage() { return message; }
        public void setMessage(Object v) { message = v; }
        public String getSystemMessage() { return systemMessage; }
        public void setSystemMessage(String v) { systemMessage = v; }
        public HookOutcome getOutcome() { return outcome; }
        public void setOutcome(HookOutcome v) { outcome = v; }
        public boolean isPreventContinuation() { return preventContinuation; }
        public void setPreventContinuation(Boolean v) { preventContinuation = v; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String v) { stopReason = v; }
        public PermissionBehavior getPermissionBehavior() { return permissionBehavior; }
        public void setPermissionBehavior(PermissionBehavior v) { permissionBehavior = v; }
        public String getHookPermissionDecisionReason() { return hookPermissionDecisionReason; }
        public void setHookPermissionDecisionReason(String v) { hookPermissionDecisionReason = v; }
        public String getAdditionalContext() { return additionalContext; }
        public void setAdditionalContext(String v) { additionalContext = v; }
        public String getInitialUserMessage() { return initialUserMessage; }
        public void setInitialUserMessage(String v) { initialUserMessage = v; }
        public Map<String, Object> getUpdatedInput() { return updatedInput; }
        public void setUpdatedInput(Map<String, Object> v) { updatedInput = v; }
        public Object getUpdatedMCPToolOutput() { return updatedMCPToolOutput; }
        public void setUpdatedMCPToolOutput(Object v) { updatedMCPToolOutput = v; }
        public Object getPermissionRequestResult() { return permissionRequestResult; }
        public void setPermissionRequestResult(Object v) { permissionRequestResult = v; }
        public Object getElicitationResponse() { return elicitationResponse; }
        public void setElicitationResponse(Object v) { elicitationResponse = v; }
        public List<String> getWatchPaths() { return watchPaths; }
        public void setWatchPaths(List<String> v) { watchPaths = v; }
        public Object getElicitationResultResponse() { return elicitationResultResponse; }
        public void setElicitationResultResponse(Object v) { elicitationResultResponse = v; }
        public boolean isRetry() { return retry; }
        public void setRetry(Boolean v) { retry = v; }
        public Object getHook() { return hook; }
        public void setHook(Object v) { hook = v; }
    }

    /**
     * Aggregated result across multiple hooks for the same event.
     * Translated from AggregatedHookResult in hooks.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AggregatedHookResult {
        private Object message;
        private HookBlockingError blockingError;
        private Boolean preventContinuation;
        private String stopReason;
        private String hookPermissionDecisionReason;
        private String hookSource;
        private PermissionBehavior permissionBehavior;
        private List<String> additionalContexts;
        private String initialUserMessage;
        private Map<String, Object> updatedInput;
        private Object updatedMCPToolOutput;
        private Object permissionRequestResult;
        private List<String> watchPaths;
        private Object elicitationResponse;
        private Object elicitationResultResponse;
        private Boolean retry;

        public String getHookSource() { return hookSource; }
        public void setHookSource(String v) { hookSource = v; }
        public List<String> getAdditionalContexts() { return additionalContexts; }
        public void setAdditionalContexts(List<String> v) { additionalContexts = v; }
    }

    // =========================================================================
    // Hook event metadata (for hooks configuration UI)
    // =========================================================================

    /**
     * Matcher metadata describing which field and values a hook can match on.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MatcherMetadata {
        private String fieldToMatch;
        private List<String> values;

        public String getFieldToMatch() { return fieldToMatch; }
        public void setFieldToMatch(String v) { fieldToMatch = v; }
        public List<String> getValues() { return values; }
        public void setValues(List<String> v) { values = v; }
    
    }

    /**
     * Metadata describing a hook event for display in the configuration UI.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HookEventMetadata {
        private String summary;
        private String description;
        private MatcherMetadata matcherMetadata;

        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public MatcherMetadata getMatcherMetadata() { return matcherMetadata; }
        public void setMatcherMetadata(MatcherMetadata v) { matcherMetadata = v; }
    
    }

    /**
     * Get metadata for each hook event.
     * Translated from getHookEventMetadata() in hooksConfigManager.ts (referenced by hooks.ts).
     *
     * @param toolNames Available tool names for tool-scoped events
     * @return Map from hook event name to its metadata
     */
    public Map<String, HookEventMetadata> getHookEventMetadata(List<String> toolNames) {
        Map<String, HookEventMetadata> metadata = new LinkedHashMap<>();

        metadata.put("PreToolUse", new HookEventMetadata(
                "Before tool execution",
                "Input to command is JSON of tool call arguments.\n"
                        + "Exit code 0 - stdout/stderr not shown\n"
                        + "Exit code 2 - show stderr to model and block tool call\n"
                        + "Other exit codes - show stderr to user only but continue with tool call",
                new MatcherMetadata("tool_name", toolNames)
        ));

        metadata.put("PostToolUse", new HookEventMetadata(
                "After tool execution",
                "Input to command is JSON with fields 'inputs' (tool call arguments) and 'response' (tool call response).\n"
                        + "Exit code 0 - stdout shown in transcript mode (ctrl+o)\n"
                        + "Exit code 2 - show stderr to model immediately\n"
                        + "Other exit codes - show stderr to user only",
                new MatcherMetadata("tool_name", toolNames)
        ));

        metadata.put("PostToolUseFailure", new HookEventMetadata(
                "After tool execution fails",
                "Input to command is JSON with tool_name, tool_input, tool_use_id, error, error_type, is_interrupt, and is_timeout.\n"
                        + "Exit code 0 - stdout shown in transcript mode (ctrl+o)\n"
                        + "Exit code 2 - show stderr to model immediately\n"
                        + "Other exit codes - show stderr to user only",
                new MatcherMetadata("tool_name", toolNames)
        ));

        metadata.put("UserPromptSubmit", new HookEventMetadata(
                "When user submits a prompt",
                "Input to command is JSON with prompt text.\n"
                        + "Exit code 0 - continue normally\n"
                        + "Exit code 2 - block prompt and show stderr to user",
                null
        ));

        metadata.put("SessionStart", new HookEventMetadata(
                "When a session starts",
                "Input to command is JSON with session metadata.\n"
                        + "Exit code 0 - continue normally",
                null
        ));

        metadata.put("SessionEnd", new HookEventMetadata(
                "When a session ends",
                "Input to command is JSON with exit reason.\n"
                        + "Exit code 0 - continue normally",
                null
        ));

        metadata.put("Stop", new HookEventMetadata(
                "When Claude stops responding",
                "Input to command is JSON with stop reason.\n"
                        + "Exit code 0 - continue normally",
                null
        ));

        metadata.put("Notification", new HookEventMetadata(
                "When a notification is sent",
                "Input to command is JSON with notification details.\n"
                        + "Exit code 0 - continue normally",
                null
        ));

        metadata.put("PermissionDenied", new HookEventMetadata(
                "After auto mode classifier denies a tool call",
                "Input to command is JSON with tool_name, tool_input, tool_use_id, and reason.\n"
                        + "Return {\"hookSpecificOutput\":{\"hookEventName\":\"PermissionDenied\",\"retry\":true}} to tell the model it may retry.\n"
                        + "Exit code 0 - stdout shown in transcript mode (ctrl+o)\n"
                        + "Other exit codes - show stderr to user only",
                new MatcherMetadata("tool_name", toolNames)
        ));

        metadata.put("SubagentStart", new HookEventMetadata(
                "When a subagent starts",
                "Input to command is JSON with subagent metadata.",
                null
        ));

        metadata.put("SubagentStop", new HookEventMetadata(
                "When a subagent stops",
                "Input to command is JSON with subagent metadata.",
                null
        ));

        return metadata;
    }

    // =========================================================================
    // Trust / security checks
    // =========================================================================

    /**
     * Determine if a hook should be skipped due to lack of workspace trust.
     * Translated from shouldSkipHookDueToTrust() in hooks.ts
     *
     * ALL hooks require workspace trust because they execute arbitrary commands
     * from .claude/settings.json.
     *
     * @return true if hook should be skipped, false if it should execute
     */
    public boolean shouldSkipHookDueToTrust() {
        boolean isNonInteractive = isNonInteractiveSession();
        if (isNonInteractive) {
            // In SDK (non-interactive) mode, trust is implicit — always execute
            return false;
        }
        // In interactive mode, ALL hooks require trust dialog acceptance
        boolean hasTrust = checkHasTrustDialogAccepted();
        return !hasTrust;
    }

    /**
     * Create the base hook input common to all hook types.
     * Translated from createBaseHookInput() in hooks.ts
     */
    public Map<String, Object> createBaseHookInput(
            String permissionMode,
            String sessionId,
            String agentId,
            String agentType) {

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("session_id", sessionId != null ? sessionId : getSessionId());
        input.put("transcript_path", getTranscriptPathForSession(sessionId));
        input.put("cwd", getCwd());
        if (permissionMode != null) {
            input.put("permission_mode", permissionMode);
        }
        if (agentId != null) {
            input.put("agent_id", agentId);
        }
        // agent_type: subagent's type takes precedence over session's --agent flag
        String resolvedAgentType = agentType != null ? agentType : getMainThreadAgentType();
        if (resolvedAgentType != null) {
            input.put("agent_type", resolvedAgentType);
        }
        return input;
    }

    // =========================================================================
    // Delegated state accessors (stubs — wired via BootstrapStateService)
    // =========================================================================

    private boolean isNonInteractiveSession() {
        String val = System.getenv("CLAUDE_NON_INTERACTIVE");
        return "1".equals(val) || "true".equalsIgnoreCase(val);
    }

    private boolean checkHasTrustDialogAccepted() {
        // Wired through ConfigService in production
        return true;
    }

    private String getSessionId() {
        return System.getenv("CLAUDE_SESSION_ID");
    }

    private String getTranscriptPathForSession(String sessionId) {
        String homeDir = System.getenv("CLAUDE_CONFIG_HOME");
        if (homeDir == null) homeDir = System.getProperty("user.home") + "/.claude";
        return homeDir + "/sessions/" + (sessionId != null ? sessionId : "unknown") + ".jsonl";
    }

    private String getCwd() {
        return System.getProperty("user.dir");
    }

    private String getMainThreadAgentType() {
        return System.getenv("CLAUDE_AGENT_TYPE");
    }
}
