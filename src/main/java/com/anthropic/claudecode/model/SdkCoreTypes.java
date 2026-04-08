package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.*;

/**
 * SDK core types for serializable SDK data.
 * Merged from:
 *   - src/entrypoints/agentSdkTypes.ts
 *   - src/entrypoints/sdk/coreTypes.ts
 *   - src/entrypoints/sdk/coreSchemas.ts
 */
public class SdkCoreTypes {

    // =========================================================================
    // Usage & Model Types
    // =========================================================================

    /**
     * Model usage statistics.
     * Translated from ModelUsageSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelUsage {
        private long inputTokens;
        private long outputTokens;
        private long cacheReadInputTokens;
        private long cacheCreationInputTokens;
        private int webSearchRequests;
        private double costUSD;
        private int contextWindow;
        private int maxOutputTokens;

        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long v) { inputTokens = v; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long v) { outputTokens = v; }
        public long getCacheReadInputTokens() { return cacheReadInputTokens; }
        public void setCacheReadInputTokens(long v) { cacheReadInputTokens = v; }
        public long getCacheCreationInputTokens() { return cacheCreationInputTokens; }
        public void setCacheCreationInputTokens(long v) { cacheCreationInputTokens = v; }
        public int getWebSearchRequests() { return webSearchRequests; }
        public void setWebSearchRequests(int v) { webSearchRequests = v; }
        public double getCostUSD() { return costUSD; }
        public void setCostUSD(double v) { costUSD = v; }
        public int getContextWindow() { return contextWindow; }
        public void setContextWindow(int v) { contextWindow = v; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int v) { maxOutputTokens = v; }
    }

    // =========================================================================
    // Config / Thinking Types
    // =========================================================================

    /**
     * API key source enum.
     * Translated from ApiKeySourceSchema in coreSchemas.ts
     */
    public enum ApiKeySource {
        USER("user"),
        PROJECT("project"),
        ORG("org"),
        TEMPORARY("temporary"),
        OAUTH("oauth");

        private final String value;
        ApiKeySource(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Config scope for settings.
     * Translated from ConfigScopeSchema in coreSchemas.ts
     */
    public enum ConfigScope {
        LOCAL("local"),
        USER("user"),
        PROJECT("project");

        private final String value;
        ConfigScope(String value) { this.value = value; }
    }

    /**
     * Thinking configuration — controls Claude's extended reasoning behavior.
     * Translated from ThinkingConfigSchema (union) in coreSchemas.ts
     */
    public sealed interface ThinkingConfig permits
            ThinkingConfig.Adaptive,
            ThinkingConfig.Enabled,
            ThinkingConfig.Disabled {

        /** Claude decides when and how much to think (Opus 4.6+). */
        record Adaptive(String type) implements ThinkingConfig {}

        /** Fixed thinking token budget (older models). */
        record Enabled(String type, Integer budgetTokens) implements ThinkingConfig {}

        /** No extended thinking. */
        record Disabled(String type) implements ThinkingConfig {}
    }

    // =========================================================================
    // Output Format Types
    // =========================================================================

    /**
     * JSON schema output format.
     * Translated from JsonSchemaOutputFormatSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonSchemaOutputFormat {
        private String type = "json_schema";
        private Map<String, Object> schema;

        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public Map<String, Object> getSchema() { return schema; }
        public void setSchema(Map<String, Object> v) { schema = v; }
    }

    // =========================================================================
    // MCP Server Config Types
    // =========================================================================

    /**
     * MCP stdio server configuration.
     * Translated from McpStdioServerConfigSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpStdioServerConfig {
        private String type = "stdio";
        private String command;
        private List<String> args;
        private Map<String, String> env;

        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> v) { args = v; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> v) { env = v; }
    }

    /**
     * MCP SSE server configuration.
     * Translated from McpSSEServerConfigSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpSSEServerConfig {
        private String type = "sse";
        private String url;
        private Map<String, String> headers;

        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> v) { headers = v; }
    }

    /**
     * MCP HTTP server configuration.
     * Translated from McpHttpServerConfigSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpHttpServerConfig {
        private String type = "http";
        private String url;
        private Map<String, String> headers;

    }

    /**
     * MCP SDK server configuration (in-process).
     * Translated from McpSdkServerConfigSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpSdkServerConfig {
        private String type = "sdk";
        private String name;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
    }

    /**
     * MCP server status.
     * Translated from McpServerStatusSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpServerStatus {
        private String name;
        private String status; // connected | failed | needs-auth | pending | disabled
        private ServerInfo serverInfo;
        private String error;
        private Object config;
        private String scope;
        private List<McpTool> tools;
        private McpCapabilities capabilities;

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class ServerInfo {
            private String name;
            private String version;

        public String getVersion() { return version; }
        public void setVersion(String v) { version = v; }
        }

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class McpTool {
            private String name;
            private String description;
            private McpToolAnnotations annotations;

        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public McpToolAnnotations getAnnotations() { return annotations; }
        public void setAnnotations(McpToolAnnotations v) { annotations = v; }
        }

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class McpToolAnnotations {
            private Boolean readOnly;
            private Boolean destructive;
            private Boolean openWorld;

        public boolean isReadOnly() { return readOnly; }
        public void setReadOnly(Boolean v) { readOnly = v; }
        public boolean isDestructive() { return destructive; }
        public void setDestructive(Boolean v) { destructive = v; }
        public boolean isOpenWorld() { return openWorld; }
        public void setOpenWorld(Boolean v) { openWorld = v; }
        }

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class McpCapabilities {
            private Map<String, Object> experimental;

        public Map<String, Object> getExperimental() { return experimental; }
        public void setExperimental(Map<String, Object> v) { experimental = v; }
        }

        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public ServerInfo getServerInfo() { return serverInfo; }
        public void setServerInfo(ServerInfo v) { serverInfo = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
        public Object getConfig() { return config; }
        public void setConfig(Object v) { config = v; }
        public String getScope() { return scope; }
        public void setScope(String v) { scope = v; }
        public List<McpTool> getTools() { return tools; }
        public void setTools(List<McpTool> v) { tools = v; }
        public McpCapabilities getCapabilities() { return capabilities; }
        public void setCapabilities(McpCapabilities v) { capabilities = v; }
    }

    /**
     * Result of a setMcpServers operation.
     * Translated from McpSetServersResultSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpSetServersResult {
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
    // Permission Types
    // =========================================================================

    /**
     * Permission update destination.
     * Translated from PermissionUpdateDestinationSchema in coreSchemas.ts
     */
    public enum PermissionUpdateDestination {
        USER_SETTINGS("userSettings"),
        PROJECT_SETTINGS("projectSettings"),
        LOCAL_SETTINGS("localSettings"),
        SESSION("session"),
        CLI_ARG("cliArg");

        private final String value;
        PermissionUpdateDestination(String value) { this.value = value; }
    }

    /**
     * Permission behavior.
     * Translated from PermissionBehaviorSchema in coreSchemas.ts
     */
    public enum PermissionBehavior {
        ALLOW("allow"),
        DENY("deny"),
        ASK("ask");

        private final String value;
        PermissionBehavior(String value) { this.value = value; }
    }

    /**
     * Permission mode for controlling how tool executions are handled.
     * Translated from PermissionModeSchema in coreSchemas.ts
     */
    public enum PermissionMode {
        DEFAULT("default"),
        ACCEPT_EDITS("acceptEdits"),
        BYPASS_PERMISSIONS("bypassPermissions"),
        PLAN("plan"),
        DONT_ASK("dontAsk");

        private final String value;
        PermissionMode(String value) { this.value = value; }
    }

    /**
     * Permission rule value (tool name + optional rule content).
     * Translated from PermissionRuleValueSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PermissionRuleValue {
        private String toolName;
        private String ruleContent;

        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public String getRuleContent() { return ruleContent; }
        public void setRuleContent(String v) { ruleContent = v; }
    }

    /**
     * Permission decision classification for telemetry.
     * Translated from PermissionDecisionClassificationSchema in coreSchemas.ts
     */
    public enum PermissionDecisionClassification {
        USER_TEMPORARY("user_temporary"),
        USER_PERMANENT("user_permanent"),
        USER_REJECT("user_reject");

        private final String value;
        PermissionDecisionClassification(String value) { this.value = value; }
    }

    // =========================================================================
    // Hook Types
    // =========================================================================

    /**
     * All supported hook event names.
     * Translated from HOOK_EVENTS constant in coreTypes.ts / coreSchemas.ts
     */
    public enum HookEvent {
        PRE_TOOL_USE("PreToolUse"),
        POST_TOOL_USE("PostToolUse"),
        POST_TOOL_USE_FAILURE("PostToolUseFailure"),
        NOTIFICATION("Notification"),
        USER_PROMPT_SUBMIT("UserPromptSubmit"),
        SESSION_START("SessionStart"),
        SESSION_END("SessionEnd"),
        STOP("Stop"),
        STOP_FAILURE("StopFailure"),
        SUBAGENT_START("SubagentStart"),
        SUBAGENT_STOP("SubagentStop"),
        PRE_COMPACT("PreCompact"),
        POST_COMPACT("PostCompact"),
        PERMISSION_REQUEST("PermissionRequest"),
        PERMISSION_DENIED("PermissionDenied"),
        SETUP("Setup"),
        TEAMMATE_IDLE("TeammateIdle"),
        TASK_CREATED("TaskCreated"),
        TASK_COMPLETED("TaskCompleted"),
        ELICITATION("Elicitation"),
        ELICITATION_RESULT("ElicitationResult"),
        CONFIG_CHANGE("ConfigChange"),
        WORKTREE_CREATE("WorktreeCreate"),
        WORKTREE_REMOVE("WorktreeRemove"),
        INSTRUCTIONS_LOADED("InstructionsLoaded"),
        CWD_CHANGED("CwdChanged"),
        FILE_CHANGED("FileChanged");

        private final String value;
        HookEvent(String value) { this.value = value; }
    }

    /**
     * Base hook input shared by all hook event types.
     * Translated from BaseHookInputSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BaseHookInput {
        @JsonProperty("session_id")
        private String sessionId;
        @JsonProperty("transcript_path")
        private String transcriptPath;
        private String cwd;
        @JsonProperty("permission_mode")
        private String permissionMode;
        @JsonProperty("agent_id")
        private String agentId;
        @JsonProperty("agent_type")
        private String agentType;

        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getTranscriptPath() { return transcriptPath; }
        public void setTranscriptPath(String v) { transcriptPath = v; }
        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String v) { permissionMode = v; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
    }

    // =========================================================================
    // SDK Message Types
    // =========================================================================

    /**
     * SDK user message.
     * Translated from SDKUserMessageSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKUserMessage {
        private String type = "user";
        private String uuid;
        private Object message;
        private String timestamp;
        @JsonProperty("isSynthetic")
        private Boolean synthetic;

        public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        public Object getMessage() { return message; }
        public void setMessage(Object v) { message = v; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String v) { timestamp = v; }
        public boolean isSynthetic() { return synthetic; }
        public void setSynthetic(Boolean v) { synthetic = v; }
    }

    /**
     * SDK assistant message.
     * Translated from SDKAssistantMessage in coreTypes.generated.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKAssistantMessage {
        private String type = "assistant";
        private String uuid;
        private Object message;
        @JsonProperty("requestId")
        private String requestId;
        private ModelUsage usage;

        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public ModelUsage getUsage() { return usage; }
        public void setUsage(ModelUsage v) { usage = v; }
    }

    /**
     * SDK result message (turn completion).
     * Translated from SDKResultMessage in coreTypes.generated.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKResultMessage {
        private String type = "result";
        private String subtype;
        private boolean isError;
        private String result;
        @JsonProperty("session_id")
        private String sessionId;
        private ModelUsage usage;
        @JsonProperty("total_cost_usd")
        private double totalCostUsd;
        @JsonProperty("num_turns")
        private int numTurns;

        public String getSubtype() { return subtype; }
        public void setSubtype(String v) { subtype = v; }
        public boolean isIsError() { return isError; }
        public void setIsError(boolean v) { isError = v; }
        public String getResult() { return result; }
        public void setResult(String v) { result = v; }
        public double getTotalCostUsd() { return totalCostUsd; }
        public void setTotalCostUsd(double v) { totalCostUsd = v; }
        public int getNumTurns() { return numTurns; }
        public void setNumTurns(int v) { numTurns = v; }
    }

    /**
     * SDK session info.
     * Translated from SDKSessionInfo in coreTypes.generated.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKSessionInfo {
        @JsonProperty("session_id")
        private String sessionId;
        @JsonProperty("created_at")
        private String createdAt;
        private ModelUsage usage;
        private String summary;
        private String customTitle;
        private String tag;

        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String v) { createdAt = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
        public String getCustomTitle() { return customTitle; }
        public void setCustomTitle(String v) { customTitle = v; }
        public String getTag() { return tag; }
        public void setTag(String v) { tag = v; }
    }

    /**
     * SDK compact boundary message (system message).
     * Translated from SDKCompactBoundaryMessage in coreTypes.generated.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKCompactBoundaryMessage {
        private String type = "system";
        private String subtype = "compact_boundary";
        private String uuid;
        @JsonProperty("compact_metadata")
        private Map<String, Object> compactMetadata;

        public Map<String, Object> getCompactMetadata() { return compactMetadata; }
        public void setCompactMetadata(Map<String, Object> v) { compactMetadata = v; }
    }

    /**
     * SDK streamlined text message (assistant summary).
     * Translated from SDKStreamlinedTextMessageSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKStreamlinedTextMessage {
        private String type = "streamlined_text";
        private String uuid;
        private String text;

        public String getText() { return text; }
        public void setText(String v) { text = v; }
    }

    /**
     * SDK post-turn summary message.
     * Translated from SDKPostTurnSummaryMessageSchema in coreSchemas.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SDKPostTurnSummaryMessage {
        private String type = "post_turn_summary";
        private String summary;
        private ModelUsage usage;

    }

    // =========================================================================
    // Session Management Types (agentSdkTypes.ts)
    // =========================================================================

    /**
     * Cron task from .claude/scheduled_tasks.json.
     * Translated from CronTask type in agentSdkTypes.ts
     * @internal
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CronTask {
        private String id;
        private String cron;
        private String prompt;
        private long createdAt;
        private Boolean recurring;

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getCron() { return cron; }
        public void setCron(String v) { cron = v; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public boolean isRecurring() { return recurring; }
        public void setRecurring(Boolean v) { recurring = v; }
    }

    /**
     * Cron scheduler jitter configuration.
     * Translated from CronJitterConfig in agentSdkTypes.ts
     * @internal
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CronJitterConfig {
        private double recurringFrac;
        private long recurringCapMs;
        private long oneShotMaxMs;
        private long oneShotFloorMs;
        private int oneShotMinuteMod;
        private long recurringMaxAgeMs;

        public double getRecurringFrac() { return recurringFrac; }
        public void setRecurringFrac(double v) { recurringFrac = v; }
        public long getRecurringCapMs() { return recurringCapMs; }
        public void setRecurringCapMs(long v) { recurringCapMs = v; }
        public long getOneShotMaxMs() { return oneShotMaxMs; }
        public void setOneShotMaxMs(long v) { oneShotMaxMs = v; }
        public long getOneShotFloorMs() { return oneShotFloorMs; }
        public void setOneShotFloorMs(long v) { oneShotFloorMs = v; }
        public int getOneShotMinuteMod() { return oneShotMinuteMod; }
        public void setOneShotMinuteMod(int v) { oneShotMinuteMod = v; }
        public long getRecurringMaxAgeMs() { return recurringMaxAgeMs; }
        public void setRecurringMaxAgeMs(long v) { recurringMaxAgeMs = v; }
    }

    /**
     * Inbound prompt from claude.ai bridge WebSocket.
     * Translated from InboundPrompt in agentSdkTypes.ts
     * @internal
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InboundPrompt {
        private Object content; // String or List<Object>
        private String uuid;

        public Object getContent() { return content; }
        public void setContent(Object v) { content = v; }
    }

    /**
     * Options for connectRemoteControl.
     * Translated from ConnectRemoteControlOptions in agentSdkTypes.ts
     * @internal
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConnectRemoteControlOptions {
        private String dir;
        private String name;
        private String workerType;
        private String branch;
        private String gitRepoUrl;
        private String baseUrl;
        private String orgUUID;
        private String model;

        public String getDir() { return dir; }
        public void setDir(String v) { dir = v; }
        public String getWorkerType() { return workerType; }
        public void setWorkerType(String v) { workerType = v; }
        public String getBranch() { return branch; }
        public void setBranch(String v) { branch = v; }
        public String getGitRepoUrl() { return gitRepoUrl; }
        public void setGitRepoUrl(String v) { gitRepoUrl = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { baseUrl = v; }
        public String getOrgUUID() { return orgUUID; }
        public void setOrgUUID(String v) { orgUUID = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
    }

    // =========================================================================
    // Exit Reasons
    // =========================================================================

    /**
     * Exit reasons for sessions.
     * Translated from EXIT_REASONS constant in coreTypes.ts / coreSchemas.ts
     */
    public enum ExitReason {
        CLEAR("clear"),
        RESUME("resume"),
        LOGOUT("logout"),
        PROMPT_INPUT_EXIT("prompt_input_exit"),
        OTHER("other"),
        BYPASS_PERMISSIONS_DISABLED("bypass_permissions_disabled");

        private final String value;
        ExitReason(String value) { this.value = value; }
    }
}
