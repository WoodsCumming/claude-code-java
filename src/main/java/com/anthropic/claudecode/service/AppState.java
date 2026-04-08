package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.Data;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application state management.
 * Translated from src/state/AppStateStore.ts
 *
 * Holds the full runtime state of the Claude Code REPL session.
 * TypeScript uses a reactive store with DeepImmutable; here we use a
 * plain mutable POJO managed by Spring as a session-scoped component.
 */
@Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
@Component
public class AppState {

    // -------------------------------------------------------------------------
    // Settings & display
    // -------------------------------------------------------------------------
    private SettingsJson settings = new SettingsJson();
    private boolean verbose = false;
    private String mainLoopModel = null;          // null = use default
    private String mainLoopModelForSession = null;
    private String statusLineText = null;
    private ExpandedView expandedView = ExpandedView.NONE;
    private boolean briefOnly = false;
    private boolean showTeammateMessagePreview = false;
    private int selectedIPAgentIndex = -1;
    /** CoordinatorTaskPanel selection: -1 = pill, 0 = main, 1..N = agent rows. */
    private int coordinatorTaskIndex = -1;
    private ViewSelectionMode viewSelectionMode = ViewSelectionMode.NONE;
    /** Which footer pill is focused (arrow-key navigation). */
    private FooterItem footerSelection = null;
    private ToolPermissionContext toolPermissionContext = ToolPermissionContext.empty();
    private String spinnerTip = null;
    /** Agent name from --agent CLI flag or settings. */
    private String agent = null;
    /** Assistant mode fully enabled (settings + gate + trust). */
    private boolean kairosEnabled = false;

    // -------------------------------------------------------------------------
    // Remote / bridge session state
    // -------------------------------------------------------------------------
    private String remoteSessionUrl = null;
    private RemoteConnectionStatus remoteConnectionStatus = RemoteConnectionStatus.CONNECTING;
    private int remoteBackgroundTaskCount = 0;

    /** Always-on bridge state */
    private boolean replBridgeEnabled = false;
    private boolean replBridgeExplicit = false;
    private boolean replBridgeOutboundOnly = false;
    private boolean replBridgeConnected = false;
    private boolean replBridgeSessionActive = false;
    private boolean replBridgeReconnecting = false;
    private String replBridgeConnectUrl = null;
    private String replBridgeSessionUrl = null;
    private String replBridgeEnvironmentId = null;
    private String replBridgeSessionId = null;
    private String replBridgeError = null;
    private String replBridgeInitialName = null;
    private boolean showRemoteCallout = false;

    // -------------------------------------------------------------------------
    // Tasks — excluded from DeepImmutable because TaskState contains function types
    // -------------------------------------------------------------------------
    /** taskId → task state, keyed by task ID string. */
    private Map<String, Object> tasks = new ConcurrentHashMap<>();
    /** Name → AgentId registry populated by Agent tool. Latest-wins on collision. */
    private Map<String, String> agentNameRegistry = new ConcurrentHashMap<>();
    /** Task ID that has been foregrounded — its messages are shown in the main view. */
    private String foregroundedTaskId = null;
    /** Task ID of in-process teammate whose transcript is being viewed. */
    private String viewingAgentTaskId = null;

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------
    private String companionReaction = null;
    private Long companionPetAt = null;

    // -------------------------------------------------------------------------
    // MCP
    // -------------------------------------------------------------------------
    private McpState mcp = new McpState();

    // -------------------------------------------------------------------------
    // Plugins
    // -------------------------------------------------------------------------
    private PluginsState plugins = new PluginsState();
    private AgentDefinitionsResult agentDefinitions = new AgentDefinitionsResult();

    // -------------------------------------------------------------------------
    // History & attribution
    // -------------------------------------------------------------------------
    private FileHistoryState fileHistory = new FileHistoryState();
    private AttributionState attribution = new AttributionState();

    // -------------------------------------------------------------------------
    // Todos & notifications
    // -------------------------------------------------------------------------
    /** agentId → todo list */
    private Map<String, List<Object>> todos = new ConcurrentHashMap<>();
    private List<Map<String, String>> remoteAgentTaskSuggestions = new ArrayList<>();
    private NotificationsState notifications = new NotificationsState();
    private ElicitationState elicitation = new ElicitationState();

    // -------------------------------------------------------------------------
    // Thinking / prompt suggestion
    // -------------------------------------------------------------------------
    private Boolean thinkingEnabled = null;         // null = use default
    private boolean promptSuggestionEnabled = false;
    private Map<String, Object> sessionHooks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Tmux (tungsten) state
    // -------------------------------------------------------------------------
    private TungstenActiveSession tungstenActiveSession = null;
    private Long tungstenLastCapturedTime = null;
    private TungstenLastCommand tungstenLastCommand = null;
    private Boolean tungstenPanelVisible = null;
    private Boolean tungstenPanelAutoHidden = null;

    // -------------------------------------------------------------------------
    // WebBrowser (bagel) state
    // -------------------------------------------------------------------------
    private Boolean bagelActive = null;
    private String bagelUrl = null;
    private Boolean bagelPanelVisible = null;

    // -------------------------------------------------------------------------
    // Inbox / worker sandbox
    // -------------------------------------------------------------------------
    private InboxState inbox = new InboxState();
    private WorkerSandboxPermissions workerSandboxPermissions = new WorkerSandboxPermissions();
    private PendingWorkerRequest pendingWorkerRequest = null;
    private PendingSandboxRequest pendingSandboxRequest = null;

    // -------------------------------------------------------------------------
    // Prompt suggestion
    // -------------------------------------------------------------------------
    private PromptSuggestionState promptSuggestion = new PromptSuggestionState();

    // -------------------------------------------------------------------------
    // Speculation
    // -------------------------------------------------------------------------
    /** Idle by default — no active speculation. */
    private SpeculationStatus speculationStatus = SpeculationStatus.IDLE;
    private long speculationSessionTimeSavedMs = 0L;

    // -------------------------------------------------------------------------
    // Skill improvement
    // -------------------------------------------------------------------------
    private SkillImprovementState skillImprovement = new SkillImprovementState();

    // -------------------------------------------------------------------------
    // Auth / initial message
    // -------------------------------------------------------------------------
    private int authVersion = 0;
    private InitialMessage initialMessage = null;

    // -------------------------------------------------------------------------
    // Fast mode, advisor, effort
    // -------------------------------------------------------------------------
    private boolean fastMode = false;
    private String advisorModel = null;
    private String effortValue = null;

    // -------------------------------------------------------------------------
    // Ultraplan
    // -------------------------------------------------------------------------
    private Boolean ultraplanLaunching = null;
    private String ultraplanSessionUrl = null;
    private UltraplanPendingChoice ultraplanPendingChoice = null;
    private UltraplanLaunchPending ultraplanLaunchPending = null;
    private Boolean isUltraplanMode = null;

    // -------------------------------------------------------------------------
    // Overlays
    // -------------------------------------------------------------------------
    private Set<String> activeOverlays = ConcurrentHashMap.newKeySet();

    // =========================================================================
    // Enums (correspond to TS union string literals)
    // =========================================================================

    public enum ExpandedView { NONE, TASKS, TEAMMATES }

    public enum ViewSelectionMode { NONE, SELECTING_AGENT, VIEWING_AGENT }

    public enum FooterItem { TASKS, TMUX, BAGEL, TEAMS, BRIDGE, COMPANION }

    public enum RemoteConnectionStatus { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

    public enum SpeculationStatus { IDLE, ACTIVE }

    // =========================================================================
    // Nested state records / classes
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class McpState {
        private List<Object> clients = new ArrayList<>();
        private List<Object> tools = new ArrayList<>();
        private List<Object> commands = new ArrayList<>();
        private Map<String, List<Object>> resources = new ConcurrentHashMap<>();
        private int pluginReconnectKey = 0;

        public List<Object> getClients() { return clients; }
        public void setClients(List<Object> v) { clients = v; }
        public List<Object> getTools() { return tools; }
        public void setTools(List<Object> v) { tools = v; }
        public List<Object> getCommands() { return commands; }
        public void setCommands(List<Object> v) { commands = v; }
        public Map<String, List<Object>> getResources() { return resources; }
        public void setResources(Map<String, List<Object>> v) { resources = v; }
        public int getPluginReconnectKey() { return pluginReconnectKey; }
        public void setPluginReconnectKey(int v) { pluginReconnectKey = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PluginsState {
        private List<Object> enabled = new ArrayList<>();
        private List<Object> disabled = new ArrayList<>();
        private List<Object> commands = new ArrayList<>();
        private List<Object> errors = new ArrayList<>();
        private PluginInstallationStatus installationStatus = new PluginInstallationStatus();
        private boolean needsRefresh = false;

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class PluginInstallationStatus {
            private List<MarketplaceEntry> marketplaces = new ArrayList<>();
            private List<PluginEntry> plugins = new ArrayList<>();

            @Data
            @lombok.NoArgsConstructor
            @lombok.AllArgsConstructor
            public static class MarketplaceEntry {
                private String name;
                private String status; // pending | installing | installed | failed
                private String error;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
            }

            @Data
            @lombok.NoArgsConstructor
            @lombok.AllArgsConstructor
            public static class PluginEntry {
                private String id;
                private String name;
                private String status; // pending | installing | installed | failed
                private String error;

        public String getId() { return id; }
        public void setId(String v) { id = v; }
            }

        public List<MarketplaceEntry> getMarketplaces() { return marketplaces; }
        public void setMarketplaces(List<MarketplaceEntry> v) { marketplaces = v; }
        public List<PluginEntry> getPlugins() { return plugins; }
        public void setPlugins(List<PluginEntry> v) { plugins = v; }
        

    }

        public List<Object> getEnabled() { return enabled; }
        public void setEnabled(List<Object> v) { enabled = v; }
        public List<Object> getDisabled() { return disabled; }
        public void setDisabled(List<Object> v) { disabled = v; }
        public List<Object> getErrors() { return errors; }
        public void setErrors(List<Object> v) { errors = v; }
        public PluginInstallationStatus getInstallationStatus() { return installationStatus; }
        public void setInstallationStatus(PluginInstallationStatus v) { installationStatus = v; }
        public boolean isNeedsRefresh() { return needsRefresh; }
        public void setNeedsRefresh(boolean v) { needsRefresh = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AgentDefinitionsResult {
        private List<Object> activeAgents = new ArrayList<>();
        private List<Object> allAgents = new ArrayList<>();

        public List<Object> getActiveAgents() { return activeAgents; }
        public void setActiveAgents(List<Object> v) { activeAgents = v; }
        public List<Object> getAllAgents() { return allAgents; }
        public void setAllAgents(List<Object> v) { allAgents = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FileHistoryState {
        private List<Object> snapshots = new ArrayList<>();
        private Set<String> trackedFiles = new HashSet<>();
        private int snapshotSequence = 0;

        public List<Object> getSnapshots() { return snapshots; }
        public void setSnapshots(List<Object> v) { snapshots = v; }
        public Set<String> getTrackedFiles() { return trackedFiles; }
        public void setTrackedFiles(Set<String> v) { trackedFiles = v; }
        public int getSnapshotSequence() { return snapshotSequence; }
        public void setSnapshotSequence(int v) { snapshotSequence = v; }
    

    }

    @Data
    public static class AttributionState {
        // Populated by commitAttribution.ts logic
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class NotificationsState {
        private Object current = null;
        private List<Object> queue = new ArrayList<>();

        public Object getCurrent() { return current; }
        public void setCurrent(Object v) { current = v; }
        public List<Object> getQueue() { return queue; }
        public void setQueue(List<Object> v) { queue = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ElicitationState {
        private List<Object> queue = new ArrayList<>();

    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TungstenActiveSession {
        private String sessionName;
        private String socketName;
        /** The tmux target (e.g. "session:window.pane"). */
        private String target;

        public String getSessionName() { return sessionName; }
        public void setSessionName(String v) { sessionName = v; }
        public String getSocketName() { return socketName; }
        public void setSocketName(String v) { socketName = v; }
        public String getTarget() { return target; }
        public void setTarget(String v) { target = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TungstenLastCommand {
        private String command;
        private long timestamp;

        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class InboxState {
        private List<InboxMessage> messages = new ArrayList<>();

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class InboxMessage {
            private String id;
            private String from;
            private String text;
            private String timestamp;
            private String status; // pending | processing | processed
            private String color;
            private String summary;

        public String getFrom() { return from; }
        public void setFrom(String v) { from = v; }
        public String getText() { return text; }
        public void setText(String v) { text = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
        }

        public List<InboxMessage> getMessages() { return messages; }
        public void setMessages(List<InboxMessage> v) { messages = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class WorkerSandboxPermissions {
        private List<SandboxRequest> queue = new ArrayList<>();
        private int selectedIndex = 0;

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class SandboxRequest {
            private String requestId;
            private String workerId;
            private String workerName;
            private String workerColor;
            private String host;
            private long createdAt;

        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public String getWorkerId() { return workerId; }
        public void setWorkerId(String v) { workerId = v; }
        public String getWorkerName() { return workerName; }
        public void setWorkerName(String v) { workerName = v; }
        public String getWorkerColor() { return workerColor; }
        public void setWorkerColor(String v) { workerColor = v; }
        public String getHost() { return host; }
        public void setHost(String v) { host = v; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long v) { createdAt = v; }
        }

        public int getSelectedIndex() { return selectedIndex; }
        public void setSelectedIndex(int v) { selectedIndex = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PendingWorkerRequest {
        private String toolName;
        private String toolUseId;
        private String description;

        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PendingSandboxRequest {
        private String requestId;
        private String host;

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PromptSuggestionState {
        private String text = null;
        private String promptId = null; // user_intent | stated_intent
        private long shownAt = 0;
        private long acceptedAt = 0;
        private String generationRequestId = null;

        public String getPromptId() { return promptId; }
        public void setPromptId(String v) { promptId = v; }
        public long getShownAt() { return shownAt; }
        public void setShownAt(long v) { shownAt = v; }
        public long getAcceptedAt() { return acceptedAt; }
        public void setAcceptedAt(long v) { acceptedAt = v; }
        public String getGenerationRequestId() { return generationRequestId; }
        public void setGenerationRequestId(String v) { generationRequestId = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SkillImprovementState {
        private SkillSuggestion suggestion = null;

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class SkillSuggestion {
            private String skillName;
            private List<SkillUpdate> updates = new ArrayList<>();

            @Data
            @lombok.NoArgsConstructor
            @lombok.AllArgsConstructor
            public static class SkillUpdate {
                private String section;
                private String change;
                private String reason;

        public String getSection() { return section; }
        public void setSection(String v) { section = v; }
        public String getChange() { return change; }
        public void setChange(String v) { change = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
            }

        public String getSkillName() { return skillName; }
        public void setSkillName(String v) { skillName = v; }
        public List<SkillUpdate> getUpdates() { return updates; }
        public void setUpdates(List<SkillUpdate> v) { updates = v; }
        }

        public SkillSuggestion getSuggestion() { return suggestion; }
        public void setSuggestion(SkillSuggestion v) { suggestion = v; }
    

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class InitialMessage {
        private Object message;       // UserMessage
        private Boolean clearContext;
        private String mode;          // PermissionMode
        private List<Object> allowedPrompts;

        public Object getMessage() { return message; }
        public void setMessage(Object v) { message = v; }
        public boolean isClearContext() { return clearContext; }
        public void setClearContext(Boolean v) { clearContext = v; }
        public String getMode() { return mode; }
        public void setMode(String v) { mode = v; }
        public List<Object> getAllowedPrompts() { return allowedPrompts; }
        public void setAllowedPrompts(List<Object> v) { allowedPrompts = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UltraplanPendingChoice {
        private String plan;
        private String sessionId;
        private String taskId;

        public String getPlan() { return plan; }
        public void setPlan(String v) { plan = v; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getTaskId() { return taskId; }
        public void setTaskId(String v) { taskId = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UltraplanLaunchPending {
        private String blurb;

        public String getBlurb() { return blurb; }
        public void setBlurb(String v) { blurb = v; }
    }

    // =========================================================================
    // Completion boundary — sealed hierarchy via sealed interface
    // =========================================================================

    /**
     * Translated from TypeScript CompletionBoundary union type.
     * Uses a sealed interface with record implementations.
     */
    public sealed interface CompletionBoundary
            permits CompletionBoundary.Complete,
                    CompletionBoundary.Bash,
                    CompletionBoundary.Edit,
                    CompletionBoundary.DeniedTool {

        record Complete(long completedAt, int outputTokens) implements CompletionBoundary {}
        record Bash(String command, long completedAt) implements CompletionBoundary {}
        record Edit(String toolName, String filePath, long completedAt) implements CompletionBoundary {}
        record DeniedTool(String toolName, String detail, long completedAt) implements CompletionBoundary {}
    }

    // =========================================================================
    // SpeculationResult
    // =========================================================================

    public record SpeculationResult(
            List<Object> messages,
            CompletionBoundary boundary,
            long timeSavedMs
    ) {}

    // =========================================================================
    // Factory
    // =========================================================================

    // =========================================================================
    // Plugin/marketplace state
    // =========================================================================
    private boolean needsRefresh = false;
    private java.util.List<Object> marketplaceInstallStatuses = new java.util.ArrayList<>();

    /** Whether plugins need to be reloaded. */

    /** Return a copy with needsRefresh flag set. */
    public AppState withNeedsRefresh(boolean v) {
        this.needsRefresh = v;
        return this;
    }

    /** Return self after setting marketplace install statuses. */
    public AppState withMarketplaceInstallStatuses(java.util.List<?> statuses) {
        this.marketplaceInstallStatuses = new java.util.ArrayList<>(statuses);
        return this;
    }

    /** Update the status for a single marketplace. */
    public AppState withMarketplaceStatus(String name, Object status, String error) {
        return this;
    }

    /**
     * Builds a default AppState.
     * Translated from getDefaultAppState() in AppStateStore.ts
     */
    public static AppState defaultState() {
        return new AppState();
    }

    /**
     * Get the current session ID.
     * Uses the bridge session ID if available.
     */
    public String getSessionId() {
        return replBridgeSessionId;
    }

    // Plan mode
    private boolean planModeRequired = false;
    public boolean isPlanModeRequired() { return planModeRequired; }
    public void setPlanModeRequired(boolean v) { planModeRequired = v; }

    // needsRefresh
    public boolean isNeedsRefresh() { return needsRefresh; }
    public void setNeedsRefresh(boolean v) { needsRefresh = v; }

    // Explicit getter for replBridgeSessionId (may conflict with getSessionId() alias)
    public String getReplBridgeSessionId() { return replBridgeSessionId; }
    public void setReplBridgeSessionId(String v) { replBridgeSessionId = v; }

    // Explicit getters/setters for fields that @Data might not generate in Java 22
    public Map<String, Object> getTasks() { return tasks; }
    public void setTasks(Map<String, Object> v) { tasks = v; }
    public Map<String, String> getAgentNameRegistry() { return agentNameRegistry; }
    public void setAgentNameRegistry(Map<String, String> v) { agentNameRegistry = v; }
    public String getForegroundedTaskId() { return foregroundedTaskId; }
    public void setForegroundedTaskId(String v) { foregroundedTaskId = v; }
    public String getViewingAgentTaskId() { return viewingAgentTaskId; }
    public void setViewingAgentTaskId(String v) { viewingAgentTaskId = v; }
    /** Get the standalone agent name (from --agent CLI flag or settings). */
    public String getStandaloneAgentName() { return agent; }
    public void setStandaloneAgentName(String v) { agent = v; }
}
