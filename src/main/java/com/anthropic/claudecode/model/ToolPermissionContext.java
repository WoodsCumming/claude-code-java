package com.anthropic.claudecode.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

/**
 * Context for tool permission checking.
 * Translated from src/Tool.ts ToolPermissionContext type.
 */
public class ToolPermissionContext {

    private PermissionMode mode;
    private Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories;
    private Map<String, List<String>> alwaysAllowRules;
    private Map<String, List<String>> alwaysDenyRules;
    private Map<String, List<String>> alwaysAskRules;
    private boolean isBypassPermissionsModeAvailable;
    private boolean isAutoModeAvailable;
    private Map<String, List<String>> strippedDangerousRules;
    private boolean shouldAvoidPermissionPrompts;
    private boolean awaitAutomatedChecksBeforeDialog;
    private PermissionMode prePlanMode;

    public ToolPermissionContext() {}

    public PermissionMode getMode() { return mode; }
    public void setMode(PermissionMode mode) { this.mode = mode; }
    public Map<String, AdditionalWorkingDirectory> getAdditionalWorkingDirectories() { return additionalWorkingDirectories; }
    public void setAdditionalWorkingDirectories(Map<String, AdditionalWorkingDirectory> v) { this.additionalWorkingDirectories = v; }
    public Map<String, List<String>> getAlwaysAllowRules() { return alwaysAllowRules; }
    public void setAlwaysAllowRules(Map<String, List<String>> v) { this.alwaysAllowRules = v; }
    public Map<String, List<String>> getAlwaysDenyRules() { return alwaysDenyRules; }
    public void setAlwaysDenyRules(Map<String, List<String>> v) { this.alwaysDenyRules = v; }
    public Map<String, List<String>> getAlwaysAskRules() { return alwaysAskRules; }
    public void setAlwaysAskRules(Map<String, List<String>> v) { this.alwaysAskRules = v; }
    public boolean isBypassPermissionsModeAvailable() { return isBypassPermissionsModeAvailable; }
    public void setBypassPermissionsModeAvailable(boolean v) { this.isBypassPermissionsModeAvailable = v; }
    public boolean isAutoModeAvailable() { return isAutoModeAvailable; }
    public void setAutoModeAvailable(boolean v) { this.isAutoModeAvailable = v; }
    public Map<String, List<String>> getStrippedDangerousRules() { return strippedDangerousRules; }
    public void setStrippedDangerousRules(Map<String, List<String>> v) { this.strippedDangerousRules = v; }
    public boolean isShouldAvoidPermissionPrompts() { return shouldAvoidPermissionPrompts; }
    public void setShouldAvoidPermissionPrompts(boolean v) { this.shouldAvoidPermissionPrompts = v; }
    public boolean isAwaitAutomatedChecksBeforeDialog() { return awaitAutomatedChecksBeforeDialog; }
    public void setAwaitAutomatedChecksBeforeDialog(boolean v) { this.awaitAutomatedChecksBeforeDialog = v; }
    public PermissionMode getPrePlanMode() { return prePlanMode; }
    public void setPrePlanMode(PermissionMode v) { this.prePlanMode = v; }

    /**
     * Creates an empty/default permission context.
     * Translated from getEmptyToolPermissionContext()
     */
    public static ToolPermissionContext empty() {
        ToolPermissionContext ctx = new ToolPermissionContext();
        ctx.setMode(PermissionMode.DEFAULT);
        ctx.setAdditionalWorkingDirectories(Collections.emptyMap());
        ctx.setAlwaysAllowRules(Collections.emptyMap());
        ctx.setAlwaysDenyRules(Collections.emptyMap());
        ctx.setAlwaysAskRules(Collections.emptyMap());
        return ctx;
    }

    /** Return a builder for ToolPermissionContext. */
    public static Builder builder() { return new Builder(); }

    /** Return a builder initialized with this instance's values. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.ctx.setMode(this.mode);
        b.ctx.setAdditionalWorkingDirectories(this.additionalWorkingDirectories);
        b.ctx.setAlwaysAllowRules(this.alwaysAllowRules);
        b.ctx.setAlwaysDenyRules(this.alwaysDenyRules);
        b.ctx.setAlwaysAskRules(this.alwaysAskRules);
        b.ctx.setBypassPermissionsModeAvailable(this.isBypassPermissionsModeAvailable);
        b.ctx.setAutoModeAvailable(this.isAutoModeAvailable);
        b.ctx.setStrippedDangerousRules(this.strippedDangerousRules);
        b.ctx.setShouldAvoidPermissionPrompts(this.shouldAvoidPermissionPrompts);
        b.ctx.setAwaitAutomatedChecksBeforeDialog(this.awaitAutomatedChecksBeforeDialog);
        b.ctx.setPrePlanMode(this.prePlanMode);
        return b;
    }

    public static class Builder {
        private final ToolPermissionContext ctx = new ToolPermissionContext();
        public Builder mode(PermissionMode mode) { ctx.setMode(mode); return this; }
        public Builder additionalWorkingDirectories(Map<String, AdditionalWorkingDirectory> v) { ctx.setAdditionalWorkingDirectories(v); return this; }
        public Builder alwaysAllowRules(Map<String, List<String>> v) { ctx.setAlwaysAllowRules(v); return this; }
        public Builder alwaysDenyRules(Map<String, List<String>> v) { ctx.setAlwaysDenyRules(v); return this; }
        public Builder alwaysAskRules(Map<String, List<String>> v) { ctx.setAlwaysAskRules(v); return this; }
        public Builder strippedDangerousRules(Map<String, List<String>> v) { ctx.setStrippedDangerousRules(v); return this; }
        public Builder shouldAvoidPermissionPrompts(boolean v) { ctx.setShouldAvoidPermissionPrompts(v); return this; }
        public Builder awaitAutomatedChecksBeforeDialog(boolean v) { ctx.setAwaitAutomatedChecksBeforeDialog(v); return this; }
        public Builder prePlanMode(PermissionMode v) { ctx.setPrePlanMode(v); return this; }
        public Builder isBypassPermissionsModeAvailable(boolean v) { ctx.setBypassPermissionsModeAvailable(v); return this; }
        public Builder autoModeAvailable(boolean v) { ctx.setAutoModeAvailable(v); return this; }
        public ToolPermissionContext build() { return ctx; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AdditionalWorkingDirectory {
        private String path;
        private String source;

        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
    }
}
