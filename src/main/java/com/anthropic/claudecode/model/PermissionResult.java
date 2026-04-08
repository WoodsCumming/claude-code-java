package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;
import java.util.Map;

/**
 * Permission decision/result types.
 * Translated from src/types/permissions.ts
 */
public sealed interface PermissionResult permits
        PermissionResult.AllowDecision,
        PermissionResult.AskDecision,
        PermissionResult.DenyDecision,
        PermissionResult.PassthroughDecision {

    String getBehavior();

    // =========================================================================
    // Allow
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    final class AllowDecision implements PermissionResult {
        private String behavior = "allow";
        private Map<String, Object> updatedInput;
        private Boolean userModified;
        private PermissionDecisionReason decisionReason;
        private String toolUseId;
        private String acceptFeedback;

        @Override
        public String getBehavior() { return behavior != null ? behavior : "allow"; }

        public static AllowDecisionBuilder builder() { return new AllowDecisionBuilder(); }
        public static class AllowDecisionBuilder {
            private Map<String, Object> updatedInput;
            private Boolean userModified;
            private PermissionDecisionReason decisionReason;
            private String toolUseId;
            private String acceptFeedback;
            public AllowDecisionBuilder updatedInput(Map<String, Object> v) { this.updatedInput = v; return this; }
            public AllowDecisionBuilder userModified(Boolean v) { this.userModified = v; return this; }
            public AllowDecisionBuilder decisionReason(PermissionDecisionReason v) { this.decisionReason = v; return this; }
            public AllowDecisionBuilder toolUseId(String v) { this.toolUseId = v; return this; }
            public AllowDecisionBuilder acceptFeedback(String v) { this.acceptFeedback = v; return this; }
            public AllowDecision build() {
                AllowDecision d = new AllowDecision();
                d.updatedInput = updatedInput;
                d.userModified = userModified;
                d.decisionReason = decisionReason;
                d.toolUseId = toolUseId;
                d.acceptFeedback = acceptFeedback;
                return d;
            }
        }
    

    }

    // =========================================================================
    // Ask
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    final class AskDecision implements PermissionResult {
        private String behavior = "ask";
        private String message;
        private Map<String, Object> updatedInput;
        private PermissionDecisionReason decisionReason;
        private List<PermissionUpdate> suggestions;
        private String blockedPath;
        private Boolean isBashSecurityCheckForMisparsing;
        private PendingClassifierCheck pendingClassifierCheck;

        @Override public String getBehavior() { return behavior != null ? behavior : "ask"; }

        public static AskDecisionBuilder builder() { return new AskDecisionBuilder(); }
        public static class AskDecisionBuilder {
            private String message;
            private Map<String, Object> updatedInput;
            private PermissionDecisionReason decisionReason;
            private List<PermissionUpdate> suggestions;
            private String blockedPath;
            private Boolean isBashSecurityCheckForMisparsing;
            private PendingClassifierCheck pendingClassifierCheck;
            public AskDecisionBuilder message(String v) { this.message = v; return this; }
            public AskDecisionBuilder updatedInput(Map<String, Object> v) { this.updatedInput = v; return this; }
            public AskDecisionBuilder decisionReason(PermissionDecisionReason v) { this.decisionReason = v; return this; }
            public AskDecisionBuilder suggestions(List<PermissionUpdate> v) { this.suggestions = v; return this; }
            public AskDecisionBuilder blockedPath(String v) { this.blockedPath = v; return this; }
            public AskDecisionBuilder isBashSecurityCheckForMisparsing(Boolean v) { this.isBashSecurityCheckForMisparsing = v; return this; }
            public AskDecisionBuilder pendingClassifierCheck(PendingClassifierCheck v) { this.pendingClassifierCheck = v; return this; }
            public AskDecision build() {
                AskDecision d = new AskDecision();
                d.message = message; d.updatedInput = updatedInput; d.decisionReason = decisionReason;
                d.suggestions = suggestions; d.blockedPath = blockedPath;
                d.isBashSecurityCheckForMisparsing = isBashSecurityCheckForMisparsing;
                d.pendingClassifierCheck = pendingClassifierCheck;
                return d;
            }
        }
    

    }

    // =========================================================================
    // Deny
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    final class DenyDecision implements PermissionResult {
        private String behavior = "deny";
        private String message;
        private PermissionDecisionReason decisionReason;
        private String toolUseId;

        @Override public String getBehavior() { return behavior != null ? behavior : "deny"; }

        public static DenyDecisionBuilder builder() { return new DenyDecisionBuilder(); }
        public static class DenyDecisionBuilder {
            private String message;
            private PermissionDecisionReason decisionReason;
            private String toolUseId;
            public DenyDecisionBuilder message(String v) { this.message = v; return this; }
            public DenyDecisionBuilder decisionReason(PermissionDecisionReason v) { this.decisionReason = v; return this; }
            public DenyDecisionBuilder toolUseId(String v) { this.toolUseId = v; return this; }
            public DenyDecision build() {
                DenyDecision d = new DenyDecision();
                d.message = message; d.decisionReason = decisionReason; d.toolUseId = toolUseId;
                return d;
            }
        }
    

    }

    // =========================================================================
    // Passthrough
    // =========================================================================
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    final class PassthroughDecision implements PermissionResult {
        private String behavior = "passthrough";
        private String message;
        private PermissionDecisionReason decisionReason;
        private List<PermissionUpdate> suggestions;
        private String blockedPath;
        private PendingClassifierCheck pendingClassifierCheck;

        @Override public String getBehavior() { return behavior != null ? behavior : "passthrough"; }

        public static PassthroughDecisionBuilder builder() { return new PassthroughDecisionBuilder(); }
        public static class PassthroughDecisionBuilder {
            private String message;
            private PermissionDecisionReason decisionReason;
            private List<PermissionUpdate> suggestions;
            private String blockedPath;
            private PendingClassifierCheck pendingClassifierCheck;
            public PassthroughDecisionBuilder message(String v) { this.message = v; return this; }
            public PassthroughDecisionBuilder decisionReason(PermissionDecisionReason v) { this.decisionReason = v; return this; }
            public PassthroughDecisionBuilder suggestions(List<PermissionUpdate> v) { this.suggestions = v; return this; }
            public PassthroughDecisionBuilder blockedPath(String v) { this.blockedPath = v; return this; }
            public PassthroughDecisionBuilder pendingClassifierCheck(PendingClassifierCheck v) { this.pendingClassifierCheck = v; return this; }
            public PassthroughDecision build() {
                PassthroughDecision d = new PassthroughDecision();
                d.message = message; d.decisionReason = decisionReason; d.suggestions = suggestions;
                d.blockedPath = blockedPath; d.pendingClassifierCheck = pendingClassifierCheck;
                return d;
            }
        }
    

    }

    // =========================================================================
    // Nested types
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class PendingClassifierCheck {
        private String command;
        private String cwd;
        private List<String> descriptions;

        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public List<String> getDescriptions() { return descriptions; }
        public void setDescriptions(List<String> v) { descriptions = v; }
    }

    /**
     * Reason for a permission decision.
     * Translated from PermissionDecisionReason union type.
     */
    sealed interface PermissionDecisionReason permits
            PermissionDecisionReason.RuleReason,
            PermissionDecisionReason.ModeReason,
            PermissionDecisionReason.HookReason,
            PermissionDecisionReason.ClassifierReason,
            PermissionDecisionReason.OtherReason {

        String getType();

        @Data
        final class RuleReason implements PermissionDecisionReason {
            private final String type = "rule";
            private PermissionRule rule;
            public RuleReason() {}
            public RuleReason(PermissionRule rule) { this.rule = rule; }
            @Override public String getType() { return type; }
            public PermissionRule getRule() { return rule; }
            public void setRule(PermissionRule v) { rule = v; }
        }

        @Data
        final class ModeReason implements PermissionDecisionReason {
            private final String type = "mode";
            private PermissionMode mode;
            public ModeReason() {}
            public ModeReason(PermissionMode mode) { this.mode = mode; }
            @Override public String getType() { return type; }
            public PermissionMode getMode() { return mode; }
            public void setMode(PermissionMode v) { mode = v; }
        }

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        final class HookReason implements PermissionDecisionReason {
            private final String type = "hook";
            private String hookName;
            private String hookSource;
            private String reason;
            @Override public String getType() { return type; }
        }

        @Data
        final class ClassifierReason implements PermissionDecisionReason {
            private final String type = "classifier";
            private String classifier;
            private String reason;
            public ClassifierReason() {}
            public ClassifierReason(String classifier, String reason) {
                this.classifier = classifier; this.reason = reason;
            }
            @Override public String getType() { return type; }
        }

        @Data
        final class OtherReason implements PermissionDecisionReason {
            private final String type = "other";
            private String reason;
            public OtherReason() {}
            public OtherReason(String reason) { this.reason = reason; }
            @Override public String getType() { return type; }
            public String getReason() { return reason; }
            public void setReason(String v) { reason = v; }
        }
    }

    /**
     * A permission rule.
     * Translated from PermissionRule type.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class PermissionRule {
        private PermissionRuleSource source;
        private PermissionBehavior ruleBehavior;
        private PermissionRuleValue ruleValue;

        public void setSource(PermissionRuleSource v) { source = v; }
        public PermissionBehavior getRuleBehavior() { return ruleBehavior; }
        public void setRuleBehavior(PermissionBehavior v) { ruleBehavior = v; }
        public PermissionRuleValue getRuleValue() { return ruleValue; }
        public void setRuleValue(PermissionRuleValue v) { ruleValue = v; }
    }

    enum PermissionRuleSource {
        USER_SETTINGS("userSettings"),
        PROJECT_SETTINGS("projectSettings"),
        LOCAL_SETTINGS("localSettings"),
        FLAG_SETTINGS("flagSettings"),
        POLICY_SETTINGS("policySettings"),
        CLI_ARG("cliArg"),
        COMMAND("command"),
        SESSION("session");

        private final String value;
        PermissionRuleSource(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    enum PermissionBehavior {
        ALLOW("allow"),
        DENY("deny"),
        ASK("ask");

        private final String value;
        PermissionBehavior(String value) { this.value = value; }
    }

    class PermissionRuleValue {
        private String toolName;
        private String ruleContent;
        public PermissionRuleValue() {}
        public PermissionRuleValue(String toolName, String ruleContent) { this.toolName = toolName; this.ruleContent = ruleContent; }
        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public String getRuleContent() { return ruleContent; }
        public void setRuleContent(String v) { ruleContent = v; }
    }

    /**
     * Permission update operations.
     * Translated from PermissionUpdate union type.
     */
    sealed interface PermissionUpdate permits
            PermissionUpdate.AddRules,
            PermissionUpdate.ReplaceRules,
            PermissionUpdate.RemoveRules,
            PermissionUpdate.SetMode,
            PermissionUpdate.AddDirectories,
            PermissionUpdate.RemoveDirectories {

        String getType();

        final class AddRules implements PermissionUpdate {
            private final String type = "addRules";
            private String destination;
            private List<PermissionRuleValue> rules;
            private PermissionBehavior behavior;
            public AddRules() {}
            @Override public String getType() { return type; }
            public String getDestination() { return destination; }
            public void setDestination(String v) { destination = v; }
            public List<PermissionRuleValue> getRules() { return rules; }
            public void setRules(List<PermissionRuleValue> v) { rules = v; }
            public PermissionBehavior getBehavior() { return behavior; }
            public void setBehavior(PermissionBehavior v) { behavior = v; }
        }

        final class ReplaceRules implements PermissionUpdate {
            private final String type = "replaceRules";
            private String destination;
            private List<PermissionRuleValue> rules;
            private PermissionBehavior behavior;
            public ReplaceRules() {}
            @Override public String getType() { return type; }
            public String getDestination() { return destination; }
            public void setDestination(String v) { destination = v; }
            public List<PermissionRuleValue> getRules() { return rules; }
            public void setRules(List<PermissionRuleValue> v) { rules = v; }
            public PermissionBehavior getBehavior() { return behavior; }
            public void setBehavior(PermissionBehavior v) { behavior = v; }
        }

        final class RemoveRules implements PermissionUpdate {
            private final String type = "removeRules";
            private String destination;
            private List<PermissionRuleValue> rules;
            private PermissionBehavior behavior;
            public RemoveRules() {}
            @Override public String getType() { return type; }
            public String getDestination() { return destination; }
            public void setDestination(String v) { destination = v; }
            public List<PermissionRuleValue> getRules() { return rules; }
            public void setRules(List<PermissionRuleValue> v) { rules = v; }
            public PermissionBehavior getBehavior() { return behavior; }
            public void setBehavior(PermissionBehavior v) { behavior = v; }
        }

        final class SetMode implements PermissionUpdate {
            private final String type = "setMode";
            private String destination;
            private PermissionMode mode;
            public SetMode() {}
            @Override public String getType() { return type; }
            public PermissionMode getMode() { return mode; }
            public void setMode(PermissionMode v) { mode = v; }
        }

        final class AddDirectories implements PermissionUpdate {
            private final String type = "addDirectories";
            private String destination;
            private List<String> directories;
            public AddDirectories() {}
            @Override public String getType() { return type; }
            public List<String> getDirectories() { return directories; }
            public void setDirectories(List<String> v) { directories = v; }
        }

        final class RemoveDirectories implements PermissionUpdate {
            private final String type = "removeDirectories";
            private String destination;
            private List<String> directories;
            public RemoveDirectories() {}
            @Override public String getType() { return type; }
        }
    }
}
