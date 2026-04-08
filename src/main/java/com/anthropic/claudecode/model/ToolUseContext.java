package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import com.anthropic.claudecode.tool.Tool;

/**
 * Context passed to tools during execution.
 * Translated from src/Tool.ts ToolUseContext type.
 */
@Data
@lombok.Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class ToolUseContext {

    // =========================================================================
    // Options
    // =========================================================================
    private Options options;

    // =========================================================================
    // Execution state
    // =========================================================================
    private volatile boolean aborted;
    private Runnable abortHandler;

    // =========================================================================
    // Message history
    // =========================================================================
    private List<Message> messages;

    // =========================================================================
    // File state
    // =========================================================================
    private Map<String, FileState> readFileState;

    // =========================================================================
    // In-progress tracking
    // =========================================================================
    private Set<String> inProgressToolUseIds;

    // =========================================================================
    // Response tracking
    // =========================================================================
    private int responseLength;

    // =========================================================================
    // Agent info
    // =========================================================================
    private String agentId;
    private String agentType;

    // =========================================================================
    // Limits
    // =========================================================================
    private FileReadingLimits fileReadingLimits;
    private GlobLimits globLimits;

    // Additional fields
    private Object contentReplacementState;
    private Object renderedSystemPrompt;
    private String worktreePath;
    private boolean preserveToolUseResults;

    // Explicit getters/setters for outer class
    public Options getOptions() { return options; }
    public void setOptions(Options v) { options = v; }
    public Object getContentReplacementState() { return contentReplacementState; }
    public void setContentReplacementState(Object v) { contentReplacementState = v; }
    public Object getRenderedSystemPrompt() { return renderedSystemPrompt; }
    public void setRenderedSystemPrompt(Object v) { renderedSystemPrompt = v; }
    public String getWorktreePath() { return worktreePath; }
    public void setWorktreePath(String v) { worktreePath = v; }
    public boolean isPreserveToolUseResults() { return preserveToolUseResults; }
    public void setPreserveToolUseResults(boolean v) { preserveToolUseResults = v; }

    /** Add a tool-use ID to the in-progress set. */
    public void addInProgressToolUseId(String id) {
        if (inProgressToolUseIds == null) inProgressToolUseIds = new java.util.HashSet<>();
        inProgressToolUseIds.add(id);
    }

    /** Remove a tool-use ID from the in-progress set. */
    public void removeInProgressToolUseId(String id) {
        if (inProgressToolUseIds != null) inProgressToolUseIds.remove(id);
    }

    /** Check if a tool-use ID is currently in progress. */
    public boolean isInProgressToolUse(String id) {
        return inProgressToolUseIds != null && inProgressToolUseIds.contains(id);
    }

    // Explicit builder
    public static ToolUseContextBuilder builder() { return new ToolUseContextBuilder(); }
    public static class ToolUseContextBuilder {
        private final ToolUseContext c = new ToolUseContext();
        public ToolUseContextBuilder options(Options v) { c.options = v; return this; }
        public ToolUseContextBuilder aborted(boolean v) { c.aborted = v; return this; }
        public ToolUseContextBuilder abortHandler(Runnable v) { c.abortHandler = v; return this; }
        public ToolUseContextBuilder messages(List<Message> v) { c.messages = v; return this; }
        public ToolUseContextBuilder readFileState(Map<String, FileState> v) { c.readFileState = v; return this; }
        public ToolUseContextBuilder inProgressToolUseIds(Set<String> v) { c.inProgressToolUseIds = v; return this; }
        public ToolUseContextBuilder responseLength(int v) { c.responseLength = v; return this; }
        public ToolUseContextBuilder agentId(String v) { c.agentId = v; return this; }
        public ToolUseContextBuilder agentType(String v) { c.agentType = v; return this; }
        public ToolUseContextBuilder fileReadingLimits(FileReadingLimits v) { c.fileReadingLimits = v; return this; }
        public ToolUseContextBuilder globLimits(GlobLimits v) { c.globLimits = v; return this; }
        public ToolUseContextBuilder preserveToolUseResults(boolean v) { c.preserveToolUseResults = v; return this; }
        public ToolUseContext build() { return c; }
    }

    // =========================================================================
    // Nested types
    // =========================================================================

    @Data
    @lombok.Builder
    public static class Options {
        private List<Command> commands;
        private boolean debug;
        private String mainLoopModel;
        private List<Tool<?, ?>> tools;
        private boolean verbose;
        private ThinkingConfig thinkingConfig;
        private List<McpServerConnection> mcpClients;
        private Map<String, List<Object>> mcpResources;
        private boolean isNonInteractiveSession;
        private AgentDefinitionsResult agentDefinitions;
        private Double maxBudgetUsd;
        private String customSystemPrompt;
        private String appendSystemPrompt;
        private String querySource;

        public List<Command> getCommands() { return commands; }
        public void setCommands(List<Command> v) { commands = v; }
        public boolean isDebug() { return debug; }
        public void setDebug(boolean v) { debug = v; }
        public String getMainLoopModel() { return mainLoopModel; }
        public void setMainLoopModel(String v) { mainLoopModel = v; }
        public List<Tool<?, ?>> getTools() { return tools; }
        public void setTools(List<Tool<?, ?>> v) { tools = v; }
        public boolean isVerbose() { return verbose; }
        public void setVerbose(boolean v) { verbose = v; }
        public ThinkingConfig getThinkingConfig() { return thinkingConfig; }
        public void setThinkingConfig(ThinkingConfig v) { thinkingConfig = v; }
        public List<McpServerConnection> getMcpClients() { return mcpClients; }
        public void setMcpClients(List<McpServerConnection> v) { mcpClients = v; }
        public Map<String, List<Object>> getMcpResources() { return mcpResources; }
        public void setMcpResources(Map<String, List<Object>> v) { mcpResources = v; }
        public boolean isNonInteractiveSession() { return isNonInteractiveSession; }
        public void setNonInteractiveSession(boolean v) { isNonInteractiveSession = v; }
        public AgentDefinitionsResult getAgentDefinitions() { return agentDefinitions; }
        public void setAgentDefinitions(AgentDefinitionsResult v) { agentDefinitions = v; }
        public Double getMaxBudgetUsd() { return maxBudgetUsd; }
        public void setMaxBudgetUsd(Double v) { maxBudgetUsd = v; }
        public String getCustomSystemPrompt() { return customSystemPrompt; }
        public void setCustomSystemPrompt(String v) { customSystemPrompt = v; }
        public String getAppendSystemPrompt() { return appendSystemPrompt; }
        public void setAppendSystemPrompt(String v) { appendSystemPrompt = v; }
        public String getQuerySource() { return querySource; }
        public void setQuerySource(String v) { querySource = v; }

        public static OptionsBuilder builder() { return new OptionsBuilder(); }
        public static class OptionsBuilder {
            private final Options o = new Options();
            public OptionsBuilder commands(List<Command> v) { o.setCommands(v); return this; }
            public OptionsBuilder debug(boolean v) { o.setDebug(v); return this; }
            public OptionsBuilder mainLoopModel(String v) { o.setMainLoopModel(v); return this; }
            public OptionsBuilder tools(List<Tool<?, ?>> v) { o.setTools(v); return this; }
            public OptionsBuilder verbose(boolean v) { o.setVerbose(v); return this; }
            public OptionsBuilder thinkingConfig(ThinkingConfig v) { o.setThinkingConfig(v); return this; }
            public OptionsBuilder mcpClients(List<McpServerConnection> v) { o.setMcpClients(v); return this; }
            public OptionsBuilder mcpResources(Map<String, List<Object>> v) { o.setMcpResources(v); return this; }
            public OptionsBuilder isNonInteractiveSession(boolean v) { o.setNonInteractiveSession(v); return this; }
            public OptionsBuilder agentDefinitions(AgentDefinitionsResult v) { o.setAgentDefinitions(v); return this; }
            public OptionsBuilder maxBudgetUsd(Double v) { o.setMaxBudgetUsd(v); return this; }
            public OptionsBuilder customSystemPrompt(String v) { o.setCustomSystemPrompt(v); return this; }
            public OptionsBuilder appendSystemPrompt(String v) { o.setAppendSystemPrompt(v); return this; }
            public OptionsBuilder querySource(String v) { o.setQuerySource(v); return this; }
            public Options build() { return o; }
        }
    

        public Options() {}
        public Options(List<Command> commands, boolean debug, String mainLoopModel, boolean verbose, ThinkingConfig thinkingConfig, List<McpServerConnection> mcpClients, Map<String, List<Object>> mcpResources, boolean isNonInteractiveSession, AgentDefinitionsResult agentDefinitions, Double maxBudgetUsd, String customSystemPrompt, String appendSystemPrompt, String querySource) {
            this.commands = commands;
            this.debug = debug;
            this.mainLoopModel = mainLoopModel;
            this.verbose = verbose;
            this.thinkingConfig = thinkingConfig;
            this.mcpClients = mcpClients;
            this.mcpResources = mcpResources;
            this.isNonInteractiveSession = isNonInteractiveSession;
            this.agentDefinitions = agentDefinitions;
            this.maxBudgetUsd = maxBudgetUsd;
            this.customSystemPrompt = customSystemPrompt;
            this.appendSystemPrompt = appendSystemPrompt;
            this.querySource = querySource;
        }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FileState {
        private String path;
        private String content;
        private long lastModified;
        private String hash;

        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public long getLastModified() { return lastModified; }
        public void setLastModified(long v) { lastModified = v; }
        public String getHash() { return hash; }
        public void setHash(String v) { hash = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FileReadingLimits {
        private Integer maxTokens;
        private Integer maxSizeBytes;

        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer v) { maxTokens = v; }
        public Integer getMaxSizeBytes() { return maxSizeBytes; }
        public void setMaxSizeBytes(Integer v) { maxSizeBytes = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GlobLimits {
        private Integer maxResults;

        public Integer getMaxResults() { return maxResults; }
        public void setMaxResults(Integer v) { maxResults = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ThinkingConfig {
        private boolean enabled;
        private Integer budgetTokens;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public Integer getBudgetTokens() { return budgetTokens; }
        public void setBudgetTokens(Integer v) { budgetTokens = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class McpServerConnection {
        private String name;
        private String transport;
        private Map<String, Object> config;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getTransport() { return transport; }
        public void setTransport(String v) { transport = v; }
        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> v) { config = v; }
    }

    public static class AgentDefinitionsResult {
        private List<AgentDefinition> agents;
        private String error;

        public AgentDefinitionsResult() {}
        public AgentDefinitionsResult(List<AgentDefinition> agents, String error) { this.agents = agents; this.error = error; }
        public List<AgentDefinition> getAgents() { return agents; }
        public void setAgents(List<AgentDefinition> v) { agents = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AgentDefinition {
        private String name;
        private String description;
        private String systemPrompt;
        private List<String> allowedTools;

        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String v) { systemPrompt = v; }
        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> v) { allowedTools = v; }
    }

    /**
     * Simplified Command type for tool context.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Command {
        private String name;
        private String description;
        private Map<String, Object> options;

        public Map<String, Object> getOptions() { return options; }
        public void setOptions(Map<String, Object> v) { options = v; }
    }

    // =========================================================================
    // Convenience accessors
    // =========================================================================

    /**
     * Check if context is read-only (no write tools allowed).
     */
    public Boolean isReadOnly() {
        return false; // Default: not read-only; override in subclasses or via options
    }

    /**
     * Get the current query depth (how many nested queries deep we are).
     */
    public int getQueryDepth() {
        return 0;
    }

    /**
     * Abort the current operation.
     */
    public void abort() {
        this.aborted = true;
        if (abortHandler != null) {
            abortHandler.run();
        }
    }

    /**
     * Check if the operation has been aborted.
     */
    public boolean isAborted() {
        return aborted;
    }
}
