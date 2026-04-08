package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.*;

/**
 * SDK control protocol types.
 * Translated from src/entrypoints/sdk/controlSchemas.ts
 *
 * Defines the control protocol between SDK implementations and the CLI.
 * Used by SDK builders (e.g., Python SDK) to communicate with the CLI process.
 */
public class SdkControlTypes {

    // =========================================================================
    // Hook Callback Types
    // =========================================================================

    /**
     * Configuration for matching and routing hook callbacks.
     * Translated from SDKHookCallbackMatcherSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKHookCallbackMatcher {
        private String matcher;
        private List<String> hookCallbackIds;
        private Integer timeout;

        public String getMatcher() { return matcher; }
        public void setMatcher(String v) { matcher = v; }
        public List<String> getHookCallbackIds() { return hookCallbackIds; }
        public void setHookCallbackIds(List<String> v) { hookCallbackIds = v; }
        public Integer getTimeout() { return timeout; }
        public void setTimeout(Integer v) { timeout = v; }
    }

    // =========================================================================
    // Initialize Request / Response
    // =========================================================================

    /**
     * Initializes the SDK session with hooks, MCP servers, and agent configuration.
     * Translated from SDKControlInitializeRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlInitializeRequest {
        private String subtype = "initialize";
        /** Map of HookEvent → list of matchers. */
        private Map<String, List<SDKHookCallbackMatcher>> hooks;
        private List<String> sdkMcpServers;
        private Map<String, Object> jsonSchema;
        private String systemPrompt;
        private String appendSystemPrompt;
        private Map<String, Object> agents;
        private Boolean promptSuggestions;
        private Boolean agentProgressSummaries;

        public String getSubtype() { return subtype; }
        public void setSubtype(String v) { subtype = v; }
        public Map<String, List<SDKHookCallbackMatcher>> getHooks() { return hooks; }
        public void setHooks(Map<String, List<SDKHookCallbackMatcher>> v) { hooks = v; }
        public List<String> getSdkMcpServers() { return sdkMcpServers; }
        public void setSdkMcpServers(List<String> v) { sdkMcpServers = v; }
        public Map<String, Object> getJsonSchema() { return jsonSchema; }
        public void setJsonSchema(Map<String, Object> v) { jsonSchema = v; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String v) { systemPrompt = v; }
        public String getAppendSystemPrompt() { return appendSystemPrompt; }
        public void setAppendSystemPrompt(String v) { appendSystemPrompt = v; }
        public Map<String, Object> getAgents() { return agents; }
        public void setAgents(Map<String, Object> v) { agents = v; }
        public boolean isPromptSuggestions() { return promptSuggestions; }
        public void setPromptSuggestions(Boolean v) { promptSuggestions = v; }
        public boolean isAgentProgressSummaries() { return agentProgressSummaries; }
        public void setAgentProgressSummaries(Boolean v) { agentProgressSummaries = v; }
    }

    /**
     * Response from session initialization with available commands, models, and account info.
     * Translated from SDKControlInitializeResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlInitializeResponse {
        private List<Object> commands;
        private List<Object> agents;
        @JsonProperty("output_style")
        private String outputStyle;
        @JsonProperty("available_output_styles")
        private List<String> availableOutputStyles;
        private List<Object> models;
        private Object account;
        /** @internal CLI process PID for tmux socket isolation */
        private Integer pid;
        @JsonProperty("fast_mode_state")
        private Object fastModeState;

        public List<Object> getCommands() { return commands; }
        public void setCommands(List<Object> v) { commands = v; }
        public String getOutputStyle() { return outputStyle; }
        public void setOutputStyle(String v) { outputStyle = v; }
        public List<String> getAvailableOutputStyles() { return availableOutputStyles; }
        public void setAvailableOutputStyles(List<String> v) { availableOutputStyles = v; }
        public List<Object> getModels() { return models; }
        public void setModels(List<Object> v) { models = v; }
        public Object getAccount() { return account; }
        public void setAccount(Object v) { account = v; }
        public Integer getPid() { return pid; }
        public void setPid(Integer v) { pid = v; }
        public Object getFastModeState() { return fastModeState; }
        public void setFastModeState(Object v) { fastModeState = v; }
    }

    // =========================================================================
    // Interrupt Request
    // =========================================================================

    /**
     * Interrupts the currently running conversation turn.
     * Translated from SDKControlInterruptRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlInterruptRequest {
        private String subtype = "interrupt";

    }

    // =========================================================================
    // Permission Request
    // =========================================================================

    /**
     * Requests permission to use a tool with the given input.
     * Translated from SDKControlPermissionRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlPermissionRequest {
        private String subtype = "can_use_tool";
        @JsonProperty("tool_name")
        private String toolName;
        private Map<String, Object> input;
        @JsonProperty("permission_suggestions")
        private List<Object> permissionSuggestions;
        @JsonProperty("blocked_path")
        private String blockedPath;
        @JsonProperty("decision_reason")
        private String decisionReason;
        private String title;
        @JsonProperty("display_name")
        private String displayName;
        @JsonProperty("tool_use_id")
        private String toolUseId;
        @JsonProperty("agent_id")
        private String agentId;
        private String description;

        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public Map<String, Object> getInput() { return input; }
        public void setInput(Map<String, Object> v) { input = v; }
        public List<Object> getPermissionSuggestions() { return permissionSuggestions; }
        public void setPermissionSuggestions(List<Object> v) { permissionSuggestions = v; }
        public String getBlockedPath() { return blockedPath; }
        public void setBlockedPath(String v) { blockedPath = v; }
        public String getDecisionReason() { return decisionReason; }
        public void setDecisionReason(String v) { decisionReason = v; }
        public String getTitle() { return title; }
        public void setTitle(String v) { title = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { displayName = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
    }

    // =========================================================================
    // Set Permission Mode Request
    // =========================================================================

    /**
     * Sets the permission mode for tool execution handling.
     * Translated from SDKControlSetPermissionModeRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlSetPermissionModeRequest {
        private String subtype = "set_permission_mode";
        private String mode;
        /** @internal CCR ultraplan session marker. */
        private Boolean ultraplan;

        public String getMode() { return mode; }
        public void setMode(String v) { mode = v; }
        public boolean isUltraplan() { return ultraplan; }
        public void setUltraplan(Boolean v) { ultraplan = v; }
    }

    // =========================================================================
    // Set Model / Thinking Tokens Requests
    // =========================================================================

    /**
     * Sets the model to use for subsequent conversation turns.
     * Translated from SDKControlSetModelRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlSetModelRequest {
        private String subtype = "set_model";
        private String model;

        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
    }

    /**
     * Sets the maximum number of thinking tokens for extended thinking.
     * Translated from SDKControlSetMaxThinkingTokensRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlSetMaxThinkingTokensRequest {
        private String subtype = "set_max_thinking_tokens";
        @JsonProperty("max_thinking_tokens")
        private Integer maxThinkingTokens; // nullable

        public Integer getMaxThinkingTokens() { return maxThinkingTokens; }
        public void setMaxThinkingTokens(Integer v) { maxThinkingTokens = v; }
    }

    // =========================================================================
    // MCP Status Request / Response
    // =========================================================================

    /**
     * Requests the current status of all MCP server connections.
     * Translated from SDKControlMcpStatusRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlMcpStatusRequest {
        private String subtype = "mcp_status";

    }

    /**
     * Response containing the current status of all MCP server connections.
     * Translated from SDKControlMcpStatusResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlMcpStatusResponse {
        private List<Object> mcpServers;

        public List<Object> getMcpServers() { return mcpServers; }
        public void setMcpServers(List<Object> v) { mcpServers = v; }
    }

    // =========================================================================
    // Context Usage Request / Response
    // =========================================================================

    /**
     * Requests a breakdown of current context window usage by category.
     * Translated from SDKControlGetContextUsageRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlGetContextUsageRequest {
        private String subtype = "get_context_usage";

    }

    /**
     * Context category entry.
     * Translated from ContextCategorySchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContextCategory {
        private String name;
        private int tokens;
        private String color;
        private Boolean isDeferred;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public int getTokens() { return tokens; }
        public void setTokens(int v) { tokens = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public boolean isIsDeferred() { return isDeferred; }
        public void setIsDeferred(Boolean v) { isDeferred = v; }
    }

    /**
     * Context grid square for visual display.
     * Translated from ContextGridSquareSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContextGridSquare {
        private String color;
        private boolean isFilled;
        private String categoryName;
        private int tokens;
        private double percentage;
        private double squareFullness;

        public boolean isIsFilled() { return isFilled; }
        public void setIsFilled(boolean v) { isFilled = v; }
        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String v) { categoryName = v; }
        public double getPercentage() { return percentage; }
        public void setPercentage(double v) { percentage = v; }
        public double getSquareFullness() { return squareFullness; }
        public void setSquareFullness(double v) { squareFullness = v; }
    }

    /**
     * Breakdown of current context window usage by category.
     * Translated from SDKControlGetContextUsageResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlGetContextUsageResponse {
        private List<ContextCategory> categories;
        private int totalTokens;
        private int maxTokens;
        private int rawMaxTokens;
        private double percentage;
        private List<List<ContextGridSquare>> gridRows;
        private String model;
        private List<MemoryFileEntry> memoryFiles;
        private List<McpToolEntry> mcpTools;
        private List<DeferredToolEntry> deferredBuiltinTools;
        private List<NamedTokenEntry> systemTools;
        private List<NamedTokenEntry> systemPromptSections;
        private List<AgentEntry> agents;
        private SlashCommandsSummary slashCommands;
        private SkillsSummary skills;
        private Integer autoCompactThreshold;
        private boolean isAutoCompactEnabled;
        private MessageBreakdown messageBreakdown;
        private ApiUsage apiUsage;

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class MemoryFileEntry {
            private String path;
            private String type;
            private int tokens;
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class McpToolEntry {
            private String name;
            private String serverName;
            private int tokens;
            private Boolean isLoaded;
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class DeferredToolEntry {
            private String name;
            private int tokens;
            private boolean isLoaded;
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class NamedTokenEntry {
            private String name;
            private int tokens;
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class AgentEntry {
            private String agentType;
            private String source;
            private int tokens;
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class SlashCommandsSummary {
            private int totalCommands;
            private int includedCommands;
            private int tokens;
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class SkillsSummary {
            private int totalSkills;
            private int includedSkills;
            private int tokens;
            private List<SkillFrontmatterEntry> skillFrontmatter;

            @Data @NoArgsConstructor
@AllArgsConstructor 
            public static class SkillFrontmatterEntry {
                private String name;
                private String source;
                private int tokens;
            }
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class MessageBreakdown {
            private int toolCallTokens;
            private int toolResultTokens;
            private int attachmentTokens;
            private int assistantMessageTokens;
            private int userMessageTokens;
            private List<ToolCallByType> toolCallsByType;
            private List<NamedTokenEntry> attachmentsByType;

            @Data @NoArgsConstructor
@AllArgsConstructor 
            public static class ToolCallByType {
                private String name;
                private int callTokens;
                private int resultTokens;
            }
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class ApiUsage {
            private int inputTokens;
            private int outputTokens;
            @JsonProperty("cache_creation_input_tokens")
            private int cacheCreationInputTokens;
            @JsonProperty("cache_read_input_tokens")
            private int cacheReadInputTokens;
        }
    }

    // =========================================================================
    // Rewind Files Request / Response
    // =========================================================================

    /**
     * Rewinds file changes made since a specific user message.
     * Translated from SDKControlRewindFilesRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlRewindFilesRequest {
        private String subtype = "rewind_files";
        @JsonProperty("user_message_id")
        private String userMessageId;
        @JsonProperty("dry_run")
        private Boolean dryRun;

        public String getUserMessageId() { return userMessageId; }
        public void setUserMessageId(String v) { userMessageId = v; }
        public boolean isDryRun() { return dryRun; }
        public void setDryRun(Boolean v) { dryRun = v; }
    }

    /**
     * Result of a rewindFiles operation.
     * Translated from SDKControlRewindFilesResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlRewindFilesResponse {
        private boolean canRewind;
        private String error;
        private List<String> filesChanged;
        private Integer insertions;
        private Integer deletions;

        public boolean isCanRewind() { return canRewind; }
        public void setCanRewind(boolean v) { canRewind = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
        public List<String> getFilesChanged() { return filesChanged; }
        public void setFilesChanged(List<String> v) { filesChanged = v; }
        public Integer getInsertions() { return insertions; }
        public void setInsertions(Integer v) { insertions = v; }
        public Integer getDeletions() { return deletions; }
        public void setDeletions(Integer v) { deletions = v; }
    }

    // =========================================================================
    // Cancel Async Message Request / Response
    // =========================================================================

    /**
     * Drops a pending async user message from the command queue by uuid.
     * Translated from SDKControlCancelAsyncMessageRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlCancelAsyncMessageRequest {
        private String subtype = "cancel_async_message";
        @JsonProperty("message_uuid")
        private String messageUuid;

        public String getMessageUuid() { return messageUuid; }
        public void setMessageUuid(String v) { messageUuid = v; }
    }

    /**
     * Result of a cancel_async_message operation.
     * Translated from SDKControlCancelAsyncMessageResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlCancelAsyncMessageResponse {
        private boolean cancelled;

        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean v) { cancelled = v; }
    }

    // =========================================================================
    // Seed Read State Request
    // =========================================================================

    /**
     * Seeds the readFileState cache with a path+mtime entry.
     * Translated from SDKControlSeedReadStateRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlSeedReadStateRequest {
        private String subtype = "seed_read_state";
        private String path;
        private long mtime;

        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public long getMtime() { return mtime; }
        public void setMtime(long v) { mtime = v; }
    }

    // =========================================================================
    // Hook Callback Request
    // =========================================================================

    /**
     * Delivers a hook callback with its input data.
     * Translated from SDKHookCallbackRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKHookCallbackRequest {
        private String subtype = "hook_callback";
        @JsonProperty("callback_id")
        private String callbackId;
        private Object input;
        @JsonProperty("tool_use_id")
        private String toolUseId;

        public String getCallbackId() { return callbackId; }
        public void setCallbackId(String v) { callbackId = v; }
    }

    // =========================================================================
    // MCP Message / Set Servers Requests
    // =========================================================================

    /**
     * Sends a JSON-RPC message to a specific MCP server.
     * Translated from SDKControlMcpMessageRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlMcpMessageRequest {
        private String subtype = "mcp_message";
        @JsonProperty("server_name")
        private String serverName;
        private Object message; // JSONRPCMessage (treated as unknown)

        public String getServerName() { return serverName; }
        public void setServerName(String v) { serverName = v; }
        public Object getMessage() { return message; }
        public void setMessage(Object v) { message = v; }
    }

    /**
     * Replaces the set of dynamically managed MCP servers.
     * Translated from SDKControlMcpSetServersRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlMcpSetServersRequest {
        private String subtype = "mcp_set_servers";
        private Map<String, Object> servers;

        public Map<String, Object> getServers() { return servers; }
        public void setServers(Map<String, Object> v) { servers = v; }
    }

    /**
     * Result of replacing the set of dynamically managed MCP servers.
     * Translated from SDKControlMcpSetServersResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlMcpSetServersResponse {
        private List<String> added;
        private List<String> removed;
        private Map<String, String> errors;

        public List<String> getAdded() { return added; }
        public void setAdded(List<String> v) { added = v; }
        public List<String> getRemoved() { return removed; }
        public void setRemoved(List<String> v) { removed = v; }
        public Map<String, String> getErrors() { return errors; }
        public void setErrors(Map<String, String> v) { errors = v; }
    }

    // =========================================================================
    // Reload Plugins Request / Response
    // =========================================================================

    /**
     * Reloads plugins from disk and returns the refreshed session components.
     * Translated from SDKControlReloadPluginsRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlReloadPluginsRequest {
        private String subtype = "reload_plugins";

    }

    /**
     * Refreshed commands, agents, plugins, and MCP server status after reload.
     * Translated from SDKControlReloadPluginsResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlReloadPluginsResponse {
        private List<Object> commands;
        private List<Object> agents;
        private List<PluginEntry> plugins;
        private List<Object> mcpServers;
        @JsonProperty("error_count")
        private int errorCount;

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class PluginEntry {
            private String name;
            private String path;
            private String source;
        }

        public List<PluginEntry> getPlugins() { return plugins; }
        public void setPlugins(List<PluginEntry> v) { plugins = v; }
        public int getErrorCount() { return errorCount; }
        public void setErrorCount(int v) { errorCount = v; }
    }

    // =========================================================================
    // MCP Reconnect / Toggle
    // =========================================================================

    /**
     * Reconnects a disconnected or failed MCP server.
     * Translated from SDKControlMcpReconnectRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlMcpReconnectRequest {
        private String subtype = "mcp_reconnect";
        private String serverName;

    }

    /**
     * Enables or disables an MCP server.
     * Translated from SDKControlMcpToggleRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlMcpToggleRequest {
        private String subtype = "mcp_toggle";
        private String serverName;
        private boolean enabled;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
    }

    // =========================================================================
    // Stop Task / Apply Flag Settings / Get Settings
    // =========================================================================

    /**
     * Stops a running task.
     * Translated from SDKControlStopTaskRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlStopTaskRequest {
        private String subtype = "stop_task";
        @JsonProperty("task_id")
        private String taskId;

        public String getTaskId() { return taskId; }
        public void setTaskId(String v) { taskId = v; }
    }

    /**
     * Merges the provided settings into the flag settings layer.
     * Translated from SDKControlApplyFlagSettingsRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlApplyFlagSettingsRequest {
        private String subtype = "apply_flag_settings";
        private Map<String, Object> settings;

        public Map<String, Object> getSettings() { return settings; }
        public void setSettings(Map<String, Object> v) { settings = v; }
    }

    /**
     * Returns the effective merged settings and the raw per-source settings.
     * Translated from SDKControlGetSettingsRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlGetSettingsRequest {
        private String subtype = "get_settings";

    }

    /**
     * Settings source enum.
     * Translated from the source enum in SDKControlGetSettingsResponseSchema
     */
    public enum SettingsSource {
        USER_SETTINGS("userSettings"),
        PROJECT_SETTINGS("projectSettings"),
        LOCAL_SETTINGS("localSettings"),
        FLAG_SETTINGS("flagSettings"),
        POLICY_SETTINGS("policySettings");

        private final String value;
        SettingsSource(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Effective merged settings plus raw per-source settings.
     * Translated from SDKControlGetSettingsResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlGetSettingsResponse {
        private Map<String, Object> effective;
        private List<SourceEntry> sources;
        private AppliedSettings applied;

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class SourceEntry {
            private String source;
            private Map<String, Object> settings;
        }

        @Data @NoArgsConstructor
@AllArgsConstructor 
        public static class AppliedSettings {
            private String model;
            private String effort; // low | medium | high | max | null
        }

        public Map<String, Object> getEffective() { return effective; }
        public void setEffective(Map<String, Object> v) { effective = v; }
        public List<SourceEntry> getSources() { return sources; }
        public void setSources(List<SourceEntry> v) { sources = v; }
        public AppliedSettings getApplied() { return applied; }
        public void setApplied(AppliedSettings v) { applied = v; }
    }

    // =========================================================================
    // Elicitation Request / Response
    // =========================================================================

    /**
     * Requests the SDK consumer to handle an MCP elicitation (user input request).
     * Translated from SDKControlElicitationRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlElicitationRequest {
        private String subtype = "elicitation";
        @JsonProperty("mcp_server_name")
        private String mcpServerName;
        private String message;
        private String mode; // form | url
        private String url;
        @JsonProperty("elicitation_id")
        private String elicitationId;
        @JsonProperty("requested_schema")
        private Map<String, Object> requestedSchema;

        public String getMcpServerName() { return mcpServerName; }
        public void setMcpServerName(String v) { mcpServerName = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getElicitationId() { return elicitationId; }
        public void setElicitationId(String v) { elicitationId = v; }
        public Map<String, Object> getRequestedSchema() { return requestedSchema; }
        public void setRequestedSchema(Map<String, Object> v) { requestedSchema = v; }
    }

    /**
     * Response from the SDK consumer for an elicitation request.
     * Translated from SDKControlElicitationResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlElicitationResponse {
        private String action; // accept | decline | cancel
        private Map<String, Object> content;

        public String getAction() { return action; }
        public void setAction(String v) { action = v; }
        public Map<String, Object> getContent() { return content; }
        public void setContent(Map<String, Object> v) { content = v; }
    }

    // =========================================================================
    // Control Request / Response Wrappers
    // =========================================================================

    /**
     * SDK control request wrapper.
     * Translated from SDKControlRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlRequest {
        private String type = "control_request";
        @JsonProperty("request_id")
        private String requestId;
        private Object request; // one of the inner request types

        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public Object getRequest() { return request; }
        public void setRequest(Object v) { request = v; }
    }

    /**
     * SDK control success response inner.
     * Translated from ControlResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ControlResponse {
        private String subtype = "success";
        @JsonProperty("request_id")
        private String requestId;
        private Map<String, Object> response;

        public Map<String, Object> getResponse() { return response; }
        public void setResponse(Map<String, Object> v) { response = v; }
    }

    /**
     * SDK control error response inner.
     * Translated from ControlErrorResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ControlErrorResponse {
        private String subtype = "error";
        @JsonProperty("request_id")
        private String requestId;
        private String error;
        @JsonProperty("pending_permission_requests")
        private List<SDKControlRequest> pendingPermissionRequests;

        public List<SDKControlRequest> getPendingPermissionRequests() { return pendingPermissionRequests; }
        public void setPendingPermissionRequests(List<SDKControlRequest> v) { pendingPermissionRequests = v; }
    }

    /**
     * SDK control response wrapper.
     * Translated from SDKControlResponseSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlResponse {
        private String type = "control_response";
        private Object response; // ControlResponse | ControlErrorResponse

    }

    /**
     * Cancels a currently open control request.
     * Translated from SDKControlCancelRequestSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKControlCancelRequest {
        private String type = "control_cancel_request";
        @JsonProperty("request_id")
        private String requestId;

    }

    // =========================================================================
    // Keep-Alive / Environment Variables
    // =========================================================================

    /**
     * Keep-alive message to maintain WebSocket connection.
     * Translated from SDKKeepAliveMessageSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKKeepAliveMessage {
        private String type = "keep_alive";

    }

    /**
     * Updates environment variables at runtime.
     * Translated from SDKUpdateEnvironmentVariablesMessageSchema in controlSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKUpdateEnvironmentVariablesMessage {
        private String type = "update_environment_variables";
        private Map<String, String> variables;

        public Map<String, String> getVariables() { return variables; }
        public void setVariables(Map<String, String> v) { variables = v; }
    }
}
