package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Hook execution service.
 * Translated from src/utils/hooks.ts
 *
 * Hooks are user-defined shell commands that execute at various points
 * in Claude Code's lifecycle (PreToolUse, PostToolUse, etc.)
 */
@Slf4j
@Service
public class HookService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HookService.class);

    public enum HookEvent {
        PRE_TOOL_USE("PreToolUse"),
        POST_TOOL_USE("PostToolUse"),
        POST_TOOL_USE_FAILURE("PostToolUseFailure"),
        USER_PROMPT_SUBMIT("UserPromptSubmit"),
        SESSION_START("SessionStart"),
        STOP("Stop"),
        NOTIFICATION("Notification"),
        PERMISSION_DENIED("PermissionDenied"),
        PERMISSION_REQUEST("PermissionRequest"),
        SUBAGENT_START("SubagentStart"),
        CWD_CHANGED("CwdChanged"),
        FILE_CHANGED("FileChanged"),
        WORKTREE_CREATE("WorktreeCreate"),
        SETUP("Setup");

        private final String name;
        HookEvent(String name) { this.name = name; }
        public String getName() { return name; }
    }

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    // Registered hooks: event -> list of hook configs
    private final Map<String, List<HookConfig>> registeredHooks = new ConcurrentHashMap<>();

    @Autowired
    public HookService(ObjectMapper objectMapper, SettingsService settingsService) {
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    /**
     * Load hooks from settings.
     */
    public void loadHooks(String projectPath) {
        Map<String, Object> settings = settingsService.getMergedSettings(projectPath);
        Object hooksObj = settings.get("hooks");

        if (hooksObj instanceof Map) {
            Map<String, Object> hooksMap = (Map<String, Object>) hooksObj;
            for (Map.Entry<String, Object> entry : hooksMap.entrySet()) {
                String event = entry.getKey();
                if (entry.getValue() instanceof List) {
                    List<Object> hookList = (List<Object>) entry.getValue();
                    List<HookConfig> configs = new ArrayList<>();
                    for (Object hookObj : hookList) {
                        if (hookObj instanceof Map) {
                            Map<String, Object> hookMap = (Map<String, Object>) hookObj;
                            HookConfig config = new HookConfig(
                                (String) hookMap.get("type"),
                                (String) hookMap.get("command"),
                                (String) hookMap.get("matcher"),
                                (Integer) hookMap.getOrDefault("timeout", 30)
                            );
                            configs.add(config);
                        }
                    }
                    registeredHooks.put(event, configs);
                }
            }
        }
    }

    /**
     * Register a session-scoped hook.
     */
    public void registerSessionHook(String sessionId, String eventName, String matcher, Map<String, Object> hookConfig) {
        String command = (String) hookConfig.get("command");
        if (command == null) return;

        HookConfig config = new HookConfig("shell", command, matcher, null);
        registeredHooks.computeIfAbsent(eventName, k -> new java.util.ArrayList<>()).add(config);
        log.debug("Registered session hook for event '{}' in session '{}'", eventName, sessionId);
    }

    /**
     * Register an in-process post-tool-use hook callback.
     * The callback receives the hook input map and toolUseId.
     */
    public void registerPostToolUseHook(String toolName,
            java.util.function.BiConsumer<Map<String, Object>, String> callback) {
        // Store as an in-process hook (not a shell command)
        inProcessPostToolHooks.computeIfAbsent(toolName, k -> new java.util.ArrayList<>()).add(callback);
    }

    private final Map<String, List<java.util.function.BiConsumer<Map<String, Object>, String>>>
            inProcessPostToolHooks = new ConcurrentHashMap<>();

    /**
     * Execute hooks for an event, returning HookExecutionResult.
     * Used by StopHookService.
     */
    public CompletableFuture<HookExecutionResult> executeHooksForStop(
            HookEvent event,
            Map<String, Object> input,
            Object context) {
        return executeHooks(event, input, (String) null)
            .thenApply(HookExecutionResult::from);
    }

    /**
     * Execute hooks for an event.
     * Translated from executeHooks() in hooks.ts
     */
    public CompletableFuture<HookResult> executeHooks(
            HookEvent event,
            Map<String, Object> input,
            String toolUseId) {

        List<HookConfig> hooks = registeredHooks.getOrDefault(event.getName(), List.of());

        if (hooks.isEmpty()) {
            return CompletableFuture.completedFuture(HookResult.success());
        }

        return CompletableFuture.supplyAsync(() -> {
            for (HookConfig hook : hooks) {
                try {
                    HookResult result = executeHook(hook, event, input, toolUseId);
                    if (!result.isContinue()) {
                        return result;
                    }
                } catch (Exception e) {
                    log.error("Hook execution failed: {}", e.getMessage(), e);
                }
            }
            return HookResult.success();
        });
    }

    private HookResult executeHook(
            HookConfig hook,
            HookEvent event,
            Map<String, Object> input,
            String toolUseId) throws Exception {

        if (hook.getCommand() == null) return HookResult.success();

        // Build hook input JSON
        Map<String, Object> hookInput = new LinkedHashMap<>();
        hookInput.put("session_id", "current-session");
        hookInput.put("hook_event_name", event.getName());
        hookInput.put("tool_name", input.get("tool_name"));
        hookInput.put("tool_input", input);
        if (toolUseId != null) hookInput.put("tool_use_id", toolUseId);

        String inputJson = objectMapper.writeValueAsString(hookInput);

        // Execute the hook command
        ProcessBuilder pb = new ProcessBuilder(getShell(), "-c", hook.getCommand());
        pb.environment().put("CLAUDE_HOOK_INPUT", inputJson);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Write input to stdin
        process.getOutputStream().write(inputJson.getBytes());
        process.getOutputStream().close();

        // Read output
        String output = new String(process.getInputStream().readAllBytes());

        int timeout = hook.getTimeout() != null ? hook.getTimeout() : 30;
        boolean completed = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            log.warn("Hook timed out: {}", hook.getCommand());
            return HookResult.success(); // Non-blocking failure
        }

        // Parse hook output
        if (output != null && !output.isBlank()) {
            try {
                Map<String, Object> hookOutput = objectMapper.readValue(output, Map.class);
                Boolean continueExecution = (Boolean) hookOutput.get("continue");
                String stopReason = (String) hookOutput.get("stopReason");

                if (Boolean.FALSE.equals(continueExecution)) {
                    return HookResult.block(stopReason);
                }
            } catch (Exception e) {
                // Not valid JSON, ignore
            }
        }

        return HookResult.success();
    }

    private String getShell() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isEmpty()) return shell;
        return "/bin/bash";
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public static class HookConfig {
        private String type;
        private String command;
        private String matcher;
        private Integer timeout;
        public HookConfig() {}
        public HookConfig(String type, String command, String matcher, Integer timeout) {
            this.type = type; this.command = command; this.matcher = matcher; this.timeout = timeout;
        }
        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { this.command = v; }
        public String getMatcher() { return matcher; }
        public void setMatcher(String v) { this.matcher = v; }
        public Integer getTimeout() { return timeout; }
        public void setTimeout(Integer v) { this.timeout = v; }
    }

    public static class HookResult {
        private boolean continueExecution;
        private String stopReason;
        private String systemMessage;
        private Map<String, Object> updatedInput;
        private String permissionBehavior;
        private Object updatedOutput;
        private java.util.List<com.anthropic.claudecode.model.Message> attachmentMessages;
        private boolean preventContinuation;
        private java.util.List<String> blockingErrors;

        public boolean isContinueExecution() { return continueExecution; }
        public void setContinueExecution(boolean v) { this.continueExecution = v; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String v) { this.stopReason = v; }
        public String getSystemMessage() { return systemMessage; }
        public void setSystemMessage(String v) { this.systemMessage = v; }
        public Map<String, Object> getUpdatedInput() { return updatedInput; }
        public void setUpdatedInput(Map<String, Object> v) { this.updatedInput = v; }
        public String getPermissionBehavior() { return permissionBehavior; }
        public void setPermissionBehavior(String v) { this.permissionBehavior = v; }
        public Object getUpdatedOutput() { return updatedOutput; }
        public void setUpdatedOutput(Object v) { this.updatedOutput = v; }
        public java.util.List<com.anthropic.claudecode.model.Message> getAttachmentMessages() { return attachmentMessages; }
        public void setAttachmentMessages(java.util.List<com.anthropic.claudecode.model.Message> v) { this.attachmentMessages = v; }
        public boolean isPreventContinuation() { return preventContinuation; }
        public void setPreventContinuation(boolean v) { this.preventContinuation = v; }
        public java.util.List<String> getBlockingErrors() { return blockingErrors; }
        public void setBlockingErrors(java.util.List<String> v) { this.blockingErrors = v; }

        public HookResult() {}
        public HookResult(boolean continueExecution, String stopReason, String systemMessage,
                Map<String, Object> updatedInput, String permissionBehavior, Object updatedOutput,
                java.util.List<com.anthropic.claudecode.model.Message> attachmentMessages,
                boolean preventContinuation, java.util.List<String> blockingErrors) {
            this.continueExecution = continueExecution;
            this.stopReason = stopReason;
            this.systemMessage = systemMessage;
            this.updatedInput = updatedInput;
            this.permissionBehavior = permissionBehavior;
            this.updatedOutput = updatedOutput;
            this.attachmentMessages = attachmentMessages;
            this.preventContinuation = preventContinuation;
            this.blockingErrors = blockingErrors;
        }

        public static HookResult success() {
            return new HookResult(true, null, null, null, null, null,
                java.util.List.of(), false, java.util.List.of());
        }

        public static HookResult block(String reason) {
            return new HookResult(false, reason, null, null, null, null,
                java.util.List.of(), false, java.util.List.of());
        }

        public boolean isContinue() { return continueExecution; }

        /** Initial user message string (from SESSION_START hook). */
        private String initialUserMessageStr;
        public String getInitialUserMessageStr() { return initialUserMessageStr; }
        public void setInitialUserMessageStr(String v) { this.initialUserMessageStr = v; }

        /** Additional context strings (from SESSION_START / SETUP hooks). */
        private java.util.List<String> additionalContextsList;
        public java.util.List<String> getAdditionalContextsList() { return additionalContextsList; }
        public void setAdditionalContextsList(java.util.List<String> v) { this.additionalContextsList = v; }
    }

    /** Type alias for HookResult — used by StopHookService. */
    public static class HookExecutionResult extends HookResult {
        public HookExecutionResult(HookResult base) {
            super(base.isContinueExecution(), base.getStopReason(),
                base.getSystemMessage(), base.getUpdatedInput(),
                base.getPermissionBehavior(), base.getUpdatedOutput(),
                base.getAttachmentMessages(), base.isPreventContinuation(),
                base.getBlockingErrors());
        }

        public static HookExecutionResult from(HookResult r) {
            return new HookExecutionResult(r);
        }
    }

    /** Get session end hook timeout in ms. */
    public long getSessionEndHookTimeoutMs() {
        return 30_000L; // 30 seconds default
    }

    /** Execute session end hooks. */
    public void executeSessionEndHooks(String reason, long timeoutMs) {
        log.debug("executeSessionEndHooks: reason={} timeout={}ms", reason, timeoutMs);
        // In a full implementation, fire STOP hooks with "session_end" reason.
    }
}
