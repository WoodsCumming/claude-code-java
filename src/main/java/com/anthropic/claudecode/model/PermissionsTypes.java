package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Pure permission type definitions.
 * Translated from src/types/permissions.ts
 *
 * This file contains only type definitions and constants with no runtime
 * dependencies, mirroring the TypeScript design that keeps this file
 * import-cycle-free.
 */
public final class PermissionsTypes {

    // ========================================================================
    // Permission Modes
    // ========================================================================

    /**
     * User-addressable external permission modes (exposed via CLI / settings.json).
     * Corresponds to TypeScript: type ExternalPermissionMode
     */
    public enum ExternalPermissionMode {
        ACCEPT_EDITS("acceptEdits"),
        BYPASS_PERMISSIONS("bypassPermissions"),
        DEFAULT("default"),
        DONT_ASK("dontAsk"),
        PLAN("plan");

        private final String value;

        ExternalPermissionMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * Full internal permission mode union, including internal-only modes.
     * Corresponds to TypeScript: type InternalPermissionMode = ExternalPermissionMode | 'auto' | 'bubble'
     */
    public enum PermissionMode {
        ACCEPT_EDITS("acceptEdits"),
        BYPASS_PERMISSIONS("bypassPermissions"),
        DEFAULT("default"),
        DONT_ASK("dontAsk"),
        PLAN("plan"),
        AUTO("auto"),
        BUBBLE("bubble");

        private final String value;

        PermissionMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // ========================================================================
    // Permission Behaviors
    // ========================================================================

    /**
     * Corresponds to TypeScript: type PermissionBehavior = 'allow' | 'deny' | 'ask'
     */
    public enum PermissionBehavior {
        ALLOW("allow"),
        DENY("deny"),
        ASK("ask");

        private final String value;

        PermissionBehavior(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // ========================================================================
    // Permission Rules
    // ========================================================================

    /**
     * Where a permission rule originated from.
     * Corresponds to TypeScript: type PermissionRuleSource
     */
    public enum PermissionRuleSource {
        USER_SETTINGS("userSettings"),
        PROJECT_SETTINGS("projectSettings"),
        LOCAL_SETTINGS("localSettings"),
        FLAG_SETTINGS("flagSettings"),
        POLICY_SETTINGS("policySettings"),
        CLI_ARG("cliArg"),
        COMMAND("command"),
        SESSION("session");

        private final String value;

        PermissionRuleSource(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * The value of a permission rule - specifies which tool and optional content.
     * Corresponds to TypeScript: type PermissionRuleValue
     */
    @Data
    @Builder
    public static class PermissionRuleValue {
        private String toolName;
        private String ruleContent; // optional
    }

    /**
     * A permission rule with its source and behavior.
     * Corresponds to TypeScript: type PermissionRule
     */
    @Data
    @Builder
    public static class PermissionRule {
        private PermissionRuleSource source;
        private PermissionBehavior ruleBehavior;
        private PermissionRuleValue ruleValue;
    }

    // ========================================================================
    // Permission Updates
    // ========================================================================

    /**
     * Where a permission update should be persisted.
     * Corresponds to TypeScript: type PermissionUpdateDestination
     */
    public enum PermissionUpdateDestination {
        USER_SETTINGS("userSettings"),
        PROJECT_SETTINGS("projectSettings"),
        LOCAL_SETTINGS("localSettings"),
        SESSION("session"),
        CLI_ARG("cliArg");

        private final String value;

        PermissionUpdateDestination(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * Sealed hierarchy for permission update operations.
     * Corresponds to TypeScript discriminated union: type PermissionUpdate
     */
    public sealed interface PermissionUpdate permits
            PermissionsTypes.AddRulesUpdate,
            PermissionsTypes.ReplaceRulesUpdate,
            PermissionsTypes.RemoveRulesUpdate,
            PermissionsTypes.SetModeUpdate,
            PermissionsTypes.AddDirectoriesUpdate,
            PermissionsTypes.RemoveDirectoriesUpdate {

        PermissionUpdateDestination destination();
    }

    public record AddRulesUpdate(
            PermissionUpdateDestination destination,
            List<PermissionRuleValue> rules,
            PermissionBehavior behavior
    ) implements PermissionUpdate {}

    public record ReplaceRulesUpdate(
            PermissionUpdateDestination destination,
            List<PermissionRuleValue> rules,
            PermissionBehavior behavior
    ) implements PermissionUpdate {}

    public record RemoveRulesUpdate(
            PermissionUpdateDestination destination,
            List<PermissionRuleValue> rules,
            PermissionBehavior behavior
    ) implements PermissionUpdate {}

    public record SetModeUpdate(
            PermissionUpdateDestination destination,
            ExternalPermissionMode mode
    ) implements PermissionUpdate {}

    public record AddDirectoriesUpdate(
            PermissionUpdateDestination destination,
            List<String> directories
    ) implements PermissionUpdate {}

    public record RemoveDirectoriesUpdate(
            PermissionUpdateDestination destination,
            List<String> directories
    ) implements PermissionUpdate {}

    // ========================================================================
    // Working Directories
    // ========================================================================

    /**
     * An additional directory included in the permission scope.
     * Corresponds to TypeScript: type AdditionalWorkingDirectory
     */
    public record AdditionalWorkingDirectory(
            String path,
            PermissionRuleSource source
    ) {}

    // ========================================================================
    // Permission Decisions & Results
    // ========================================================================

    /**
     * Minimal command shape for permission metadata, avoiding import cycles.
     * Corresponds to TypeScript: type PermissionCommandMetadata
     */
    @Data
    @Builder
    public static class PermissionCommandMetadata {
        private String name;
        private String description; // optional
        private Map<String, Object> additionalProperties; // forward compatibility
    }

    /**
     * Metadata attached to permission decisions.
     * Corresponds to TypeScript: type PermissionMetadata = { command: ... } | undefined
     */
    public record PermissionMetadata(PermissionCommandMetadata command) {}

    /**
     * Result when permission is granted.
     * Corresponds to TypeScript: type PermissionAllowDecision
     */
    @Data
    @Builder
    public static class PermissionAllowDecision {
        private final String behavior = "allow";
        private Map<String, Object> updatedInput;
        private Boolean userModified;
        private Object decisionReason; // PermissionDecisionReason
        private String toolUseID;
        private String acceptFeedback;
        private List<Map<String, Object>> contentBlocks;
    }

    /**
     * Metadata for a pending classifier check that will run asynchronously.
     * Corresponds to TypeScript: type PendingClassifierCheck
     */
    public record PendingClassifierCheck(
            String command,
            String cwd,
            List<String> descriptions
    ) {}

    /**
     * Result when user should be prompted.
     * Corresponds to TypeScript: type PermissionAskDecision
     */
    @Data
    @Builder
    public static class PermissionAskDecision {
        private final String behavior = "ask";
        private String message;
        private Map<String, Object> updatedInput;
        private Object decisionReason; // PermissionDecisionReason
        private List<PermissionUpdate> suggestions;
        private String blockedPath;
        private PermissionMetadata metadata;
        private Boolean isBashSecurityCheckForMisparsing;
        private PendingClassifierCheck pendingClassifierCheck;
        private List<Map<String, Object>> contentBlocks;
    }

    /**
     * Result when permission is denied.
     * Corresponds to TypeScript: type PermissionDenyDecision
     */
    @Data
    @Builder
    public static class PermissionDenyDecision {
        private final String behavior = "deny";
        private String message;
        private Object decisionReason; // PermissionDecisionReason — required
        private String toolUseID;
    }

    /**
     * A permission decision - allow, ask, or deny.
     * Corresponds to TypeScript discriminated union: type PermissionDecision
     */
    public sealed interface PermissionDecision permits
            PermissionsTypes.AllowDecision,
            PermissionsTypes.AskDecision,
            PermissionsTypes.DenyDecision {

        String behavior();
    }

    public record AllowDecision(PermissionAllowDecision data) implements PermissionDecision {
        public String behavior() { return "allow"; }
    }

    public record AskDecision(PermissionAskDecision data) implements PermissionDecision {
        public String behavior() { return "ask"; }
    }

    public record DenyDecision(PermissionDenyDecision data) implements PermissionDecision {
        public String behavior() { return "deny"; }
    }

    /**
     * Permission result with additional passthrough option.
     * Corresponds to TypeScript: type PermissionResult
     */
    public sealed interface PermissionResult permits
            PermissionsTypes.PassthroughResult,
            PermissionsTypes.PermissionDecisionResult {

        String behavior();
    }

    /** Wrapper to bridge PermissionDecision into PermissionResult. */
    public record PermissionDecisionResult(PermissionDecision decision) implements PermissionResult {
        public String behavior() { return decision.behavior(); }
    }

    public record PassthroughResult(
            String message,
            Object decisionReason,
            List<PermissionUpdate> suggestions,
            String blockedPath,
            PendingClassifierCheck pendingClassifierCheck
    ) implements PermissionResult {
        public String behavior() { return "passthrough"; }
    }

    // ========================================================================
    // Permission Decision Reason (sealed hierarchy)
    // ========================================================================

    /**
     * Explanation of why a permission decision was made.
     * Corresponds to TypeScript discriminated union: type PermissionDecisionReason
     */
    public sealed interface PermissionDecisionReason permits
            PermissionsTypes.RuleReason,
            PermissionsTypes.ModeReason,
            PermissionsTypes.SubcommandResultsReason,
            PermissionsTypes.PermissionPromptToolReason,
            PermissionsTypes.HookReason,
            PermissionsTypes.AsyncAgentReason,
            PermissionsTypes.SandboxOverrideReason,
            PermissionsTypes.ClassifierReason,
            PermissionsTypes.WorkingDirReason,
            PermissionsTypes.SafetyCheckReason,
            PermissionsTypes.OtherReason {

        String type();
    }

    public record RuleReason(PermissionRule rule) implements PermissionDecisionReason {
        public String type() { return "rule"; }
    }

    public record ModeReason(PermissionMode mode) implements PermissionDecisionReason {
        public String type() { return "mode"; }
    }

    public record SubcommandResultsReason(Map<String, PermissionResult> reasons) implements PermissionDecisionReason {
        public String type() { return "subcommandResults"; }
    }

    public record PermissionPromptToolReason(
            String permissionPromptToolName,
            Object toolResult
    ) implements PermissionDecisionReason {
        public String type() { return "permissionPromptTool"; }
    }

    public record HookReason(
            String hookName,
            String hookSource,  // optional
            String reason       // optional
    ) implements PermissionDecisionReason {
        public String type() { return "hook"; }
    }

    public record AsyncAgentReason(String reason) implements PermissionDecisionReason {
        public String type() { return "asyncAgent"; }
    }

    public enum SandboxOverrideType {
        EXCLUDED_COMMAND("excludedCommand"),
        DANGEROUSLY_DISABLE_SANDBOX("dangerouslyDisableSandbox");

        private final String value;

        SandboxOverrideType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public record SandboxOverrideReason(SandboxOverrideType reason) implements PermissionDecisionReason {
        public String type() { return "sandboxOverride"; }
    }

    public record ClassifierReason(
            String classifier,
            String reason
    ) implements PermissionDecisionReason {
        public String type() { return "classifier"; }
    }

    public record WorkingDirReason(String reason) implements PermissionDecisionReason {
        public String type() { return "workingDir"; }
    }

    public record SafetyCheckReason(
            String reason,
            boolean classifierApprovable
    ) implements PermissionDecisionReason {
        public String type() { return "safetyCheck"; }
    }

    public record OtherReason(String reason) implements PermissionDecisionReason {
        public String type() { return "other"; }
    }

    // ========================================================================
    // Bash Classifier Types
    // ========================================================================

    public enum ClassifierConfidence {
        HIGH, MEDIUM, LOW
    }

    /**
     * Corresponds to TypeScript: type ClassifierResult
     */
    @Data
    @Builder
    public static class ClassifierResult {
        private boolean matches;
        private String matchedDescription;
        private ClassifierConfidence confidence;
        private String reason;
    }

    /**
     * Corresponds to TypeScript: type ClassifierBehavior = 'deny' | 'ask' | 'allow'
     */
    public enum ClassifierBehavior {
        DENY, ASK, ALLOW
    }

    /**
     * Corresponds to TypeScript: type ClassifierUsage
     */
    public record ClassifierUsage(
            int inputTokens,
            int outputTokens,
            int cacheReadInputTokens,
            int cacheCreationInputTokens
    ) {}

    /**
     * Corresponds to TypeScript: type YoloClassifierResult
     */
    @Data
    @Builder
    public static class YoloClassifierResult {
        private String thinking;
        private boolean shouldBlock;
        private String reason;
        private Boolean unavailable;
        private Boolean transcriptTooLong;
        private String model;
        private ClassifierUsage usage;
        private Long durationMs;
        private String errorDumpPath;
        private String stage; // "fast" | "thinking"
        private ClassifierUsage stage1Usage;
        private Long stage1DurationMs;
        private String stage1RequestId;
        private String stage1MsgId;
        private ClassifierUsage stage2Usage;
        private Long stage2DurationMs;
        private String stage2RequestId;
        private String stage2MsgId;
        private PromptLengths promptLengths;

        public record PromptLengths(int systemPrompt, int toolCalls, int userPrompts) {}
    }

    // ========================================================================
    // Permission Explainer Types
    // ========================================================================

    /**
     * Corresponds to TypeScript: type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'
     */
    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    /**
     * Corresponds to TypeScript: type PermissionExplanation
     */
    public record PermissionExplanation(
            RiskLevel riskLevel,
            String explanation,
            String reasoning,
            String risk
    ) {}

    // ========================================================================
    // Tool Permission Context
    // ========================================================================

    /**
     * Mapping of permission rules by their source.
     * Corresponds to TypeScript: type ToolPermissionRulesBySource
     */
    public record ToolPermissionRulesBySource(
            Map<PermissionRuleSource, List<String>> rulesBySource
    ) {}

    /**
     * Context needed for permission checking in tools.
     * Corresponds to TypeScript: type ToolPermissionContext
     */
    @Data
    @Builder
    public static class ToolPermissionContext {
        private PermissionMode mode;
        private Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories;
        private ToolPermissionRulesBySource alwaysAllowRules;
        private ToolPermissionRulesBySource alwaysDenyRules;
        private ToolPermissionRulesBySource alwaysAskRules;
        private boolean isBypassPermissionsModeAvailable;
        private ToolPermissionRulesBySource strippedDangerousRules;
        private Boolean shouldAvoidPermissionPrompts;
        private Boolean awaitAutomatedChecksBeforeDialog;
        private PermissionMode prePlanMode;
    }

    private PermissionsTypes() {}
}
