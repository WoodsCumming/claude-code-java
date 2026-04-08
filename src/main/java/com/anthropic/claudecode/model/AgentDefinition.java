package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.function.Supplier;

/**
 * Definition of an agent (built-in, plugin, or custom).
 * Translated from AgentDefinition / AgentConfig in src/tools/AgentTool/
 */
@Data
@Builder
public class AgentDefinition {

    /** Unique identifier / type name for this agent. */
    private String agentType;

    /** Human-readable description of when to use this agent. */
    private String whenToUse;

    /**
     * Source scope: "built-in" | "plugin" | "userSettings" | "projectSettings"
     * | "policySettings" | "flagSettings"
     */
    private String source;

    /** Filename (without extension) of the markdown file defining this agent. */
    private String filename;

    /** Base directory of the .claude folder this agent was loaded from. */
    private String baseDir;

    /** Model override (e.g. "claude-opus-4-6", "inherit"). Null = inherit from session. */
    private String model;

    /** Allowed tool names (null = all tools). */
    private List<String> tools;

    /** Explicitly disallowed tool names. */
    private List<String> disallowedTools;

    /** Skill names this agent can invoke. */
    private List<String> skills;

    /** Maximum number of agentic turns. Null = no limit. */
    private Integer maxTurns;

    /** Permission mode override. Null = inherit. */
    private String permissionMode;

    /** Optional initial prompt sent before the user turn. */
    private String initialPrompt;

    /** Whether the agent runs in the background. Null = false. */
    private Boolean background;

    /** Memory scope: "user" | "project" | "local". Null = no memory. */
    private String memory;

    /** Isolation mode: "worktree" | "remote". Null = none. */
    private String isolation;

    /** Required MCP server patterns (agent is hidden when they are absent). */
    private List<String> requiredMcpServers;

    /**
     * Supplier that builds the system prompt at runtime.
     * Using a Supplier defers memory-prompt injection to query time.
     */
    private transient Supplier<String> systemPromptSupplier;

    /**
     * Timestamp of a pending snapshot update the user should be notified about.
     * Set by LoadAgentsDirService when a newer project snapshot is available.
     */
    private Long pendingSnapshotUpdate;

    // -------------------------------------------------------------------------
    // Convenience helpers
    // -------------------------------------------------------------------------

    public static AgentDefinitionBuilder builder() { return new AgentDefinitionBuilder(); }
    public static class AgentDefinitionBuilder {
        private final AgentDefinition a = new AgentDefinition();
        public AgentDefinitionBuilder agentType(String v) { a.agentType = v; return this; }
        public AgentDefinitionBuilder whenToUse(String v) { a.whenToUse = v; return this; }
        public AgentDefinitionBuilder source(String v) { a.source = v; return this; }
        public AgentDefinitionBuilder filename(String v) { a.filename = v; return this; }
        public AgentDefinitionBuilder memory(String v) { a.memory = v; return this; }
        public AgentDefinitionBuilder requiredMcpServers(List<String> v) { a.requiredMcpServers = v; return this; }
        public AgentDefinitionBuilder systemPromptSupplier(Supplier<String> v) { a.systemPromptSupplier = v; return this; }
        public AgentDefinitionBuilder pendingSnapshotUpdate(Long v) { a.pendingSnapshotUpdate = v; return this; }
        public AgentDefinitionBuilder baseDir(String v) { a.baseDir = v; return this; }
        public AgentDefinitionBuilder model(String v) { a.model = v; return this; }
        public AgentDefinitionBuilder tools(List<String> v) { a.tools = v; return this; }
        public AgentDefinitionBuilder disallowedTools(List<String> v) { a.disallowedTools = v; return this; }
        public AgentDefinitionBuilder skills(List<String> v) { a.skills = v; return this; }
        public AgentDefinitionBuilder maxTurns(Integer v) { a.maxTurns = v; return this; }
        public AgentDefinitionBuilder permissionMode(String v) { a.permissionMode = v; return this; }
        public AgentDefinitionBuilder initialPrompt(String v) { a.initialPrompt = v; return this; }
        public AgentDefinitionBuilder background(Boolean v) { a.background = v; return this; }
        public AgentDefinitionBuilder isolation(String v) { a.isolation = v; return this; }
        public AgentDefinition build() { return a; }
    }

    // Explicit getters since @Data @Builder may not generate them
    public String getAgentType() { return agentType; }
    public void setAgentType(String v) { agentType = v; }
    public String getWhenToUse() { return whenToUse; }
    public void setWhenToUse(String v) { whenToUse = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public String getFilename() { return filename; }
    public void setFilename(String v) { filename = v; }
    public String getMemory() { return memory; }
    public void setMemory(String v) { memory = v; }
    public List<String> getRequiredMcpServers() { return requiredMcpServers; }
    public void setRequiredMcpServers(List<String> v) { requiredMcpServers = v; }
    public Long getPendingSnapshotUpdate() { return pendingSnapshotUpdate; }
    public void setPendingSnapshotUpdate(Long v) { pendingSnapshotUpdate = v; }

    /**
     * Returns the system prompt, invoking the supplier if present.
     * Falls back to an empty string if no supplier is set.
     */
    public String getSystemPrompt() {
        return systemPromptSupplier != null ? systemPromptSupplier.get() : "";
    }

    /** Whether this agent is a built-in agent (source is "built-in"). */
    public boolean isBuiltIn() {
        return "built-in".equals(source);
    }

    /** Optional callback to run after the agent completes. */
    private transient Runnable callback;
    public Runnable getCallback() { return callback; }
    public void setCallback(Runnable v) { callback = v; }

    // -------------------------------------------------------------------------
    // Well-known agent singletons
    // -------------------------------------------------------------------------

    /**
     * The fork agent — mirrors the FORK_AGENT constant used when resuming a
     * forked subagent. Translated from FORK_AGENT in BuiltInAgents.ts.
     */
    public static final AgentDefinition FORK_AGENT = AgentDefinition.builder()
            .agentType("fork")
            .source("built-in")
            .build();

    /**
     * The default general-purpose agent — used when no other agent is matched.
     * Translated from GENERAL_PURPOSE_AGENT in BuiltInAgents.ts.
     */
    public static final AgentDefinition GENERAL_PURPOSE_AGENT = AgentDefinition.builder()
            .agentType("general")
            .source("built-in")
            .build();

        public String getModel() { return model; }
    

        public AgentDefinition() {}
        public AgentDefinition(String agentType, String whenToUse, String source, String filename, String baseDir, String model, List<String> tools, List<String> disallowedTools, List<String> skills, Integer maxTurns, String permissionMode, String initialPrompt, Boolean background, String memory, String isolation, List<String> requiredMcpServers, Supplier<String> systemPromptSupplier, Long pendingSnapshotUpdate) {
            this.agentType = agentType;
            this.whenToUse = whenToUse;
            this.source = source;
            this.filename = filename;
            this.baseDir = baseDir;
            this.model = model;
            this.tools = tools;
            this.disallowedTools = disallowedTools;
            this.skills = skills;
            this.maxTurns = maxTurns;
            this.permissionMode = permissionMode;
            this.initialPrompt = initialPrompt;
            this.background = background;
            this.memory = memory;
            this.isolation = isolation;
            this.requiredMcpServers = requiredMcpServers;
            this.systemPromptSupplier = systemPromptSupplier;
            this.pendingSnapshotUpdate = pendingSnapshotUpdate;
        }
    }
