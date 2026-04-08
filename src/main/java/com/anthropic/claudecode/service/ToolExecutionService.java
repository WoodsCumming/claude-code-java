package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.ToolUseContext;
import com.anthropic.claudecode.service.QueryEngine.QueryEngineConfig;
import com.anthropic.claudecode.tool.Tool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Core tool execution service.
 * Translated from src/services/tools/toolExecution.ts
 *
 * Orchestrates the full lifecycle of a single tool call:
 * <ol>
 *   <li>Schema / input validation</li>
 *   <li>Pre-tool-use hook execution</li>
 *   <li>Permission decision (canUseTool)</li>
 *   <li>Actual tool.call()</li>
 *   <li>Post-tool-use hook execution</li>
 *   <li>Format result as tool_result message block</li>
 * </ol>
 *
 * Streaming model: each step may yield zero or more {@link MessageUpdateLazy} values
 * (progress, attachment, user-visible error, or final result) before the generator
 * completes.
 */
@Slf4j
@Service
public class ToolExecutionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolExecutionService.class);


    // -------------------------------------------------------------------------
    // Constants — translated from toolExecution.ts
    // -------------------------------------------------------------------------

    /** Minimum total hook duration (ms) to show inline timing summary. */
    public static final int HOOK_TIMING_DISPLAY_THRESHOLD_MS = 500;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final ToolHookService toolHookService;
    private final PermissionService permissionService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private List<Tool> allTools;

    @Autowired
    public ToolExecutionService(ToolHookService toolHookService,
                                PermissionService permissionService,
                                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.toolHookService = toolHookService;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Inject the registered tool list lazily to break the circular dependency:
     * QueryEngine → ToolExecutionService → List&lt;Tool&gt; → AgentTool → QueryEngine.
     * Using {@code @Lazy} causes Spring to inject a proxy that resolves on first use.
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    @SuppressWarnings({"rawtypes","unchecked"})
    public void setAllTools(List<Tool<?, ?>> tools) {
        this.allTools = (List) tools;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Execute all tool-use blocks sequentially and return the resulting messages.
     * Called by QueryEngine for the non-streaming tool execution path.
     *
     * @param toolUseBlocks    the tool-use blocks to execute
     * @param assistantMessages the assistant messages containing the tool calls
     * @param engineConfig     the query engine config
     * @return CompletableFuture resolving to list of result Messages
     */
    public CompletableFuture<List<Message>> runTools(
            List<ContentBlock.ToolUseBlock> toolUseBlocks,
            List<Message> assistantMessages,
            QueryEngineConfig engineConfig) {

        return CompletableFuture.supplyAsync(() -> {
            List<Message> results = new ArrayList<>();
            // Build a minimal ToolUseContext from engineConfig
            ToolUseContext context = buildContextFromEngineConfig(engineConfig);

            for (ContentBlock.ToolUseBlock toolBlock : toolUseBlocks) {
                // Find the parent assistant message for this tool block
                Message.AssistantMessage parentMsg = assistantMessages.stream()
                        .filter(m -> m instanceof Message.AssistantMessage)
                        .map(m -> (Message.AssistantMessage) m)
                        .filter(m -> m.getContent() != null && m.getContent().stream()
                                .anyMatch(c -> c instanceof ContentBlock.ToolUseBlock tb
                                        && toolBlock.getId() != null
                                        && toolBlock.getId().equals(tb.getId())))
                        .findFirst()
                        .orElse(assistantMessages.isEmpty() ? null
                                : (Message.AssistantMessage) assistantMessages.stream()
                                        .filter(m -> m instanceof Message.AssistantMessage)
                                        .findFirst().orElse(null));

                if (parentMsg == null) {
                    log.warn("No parent assistant message found for tool block: {}", toolBlock.getId());
                    continue;
                }

                try {
                    List<MessageUpdateLazy> updates = runToolUse(toolBlock, parentMsg, context).get();
                    for (MessageUpdateLazy update : updates) {
                        results.add(update.getMessage());
                        if (update.getContextModifier() != null) {
                            context = update.getContextModifier().apply(context);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error executing tool {}: {}", toolBlock.getName(), e.getMessage());
                }
            }
            return results;
        });
    }

    private ToolUseContext buildContextFromEngineConfig(QueryEngineConfig engineConfig) {
        ToolUseContext.Options options = ToolUseContext.Options.builder()
                .mainLoopModel(engineConfig.getMainLoopModel())
                .querySource(engineConfig.getQuerySource())
                .build();
        return ToolUseContext.builder()
                .options(options)
                .build();
    }

    /**
     * Execute a single tool-use block and return an ordered stream of message
     * updates (progress, results, errors).
     *
     * Translated from runToolUse() in toolExecution.ts
     *
     * @param toolUse          the tool-use block from the assistant message
     * @param assistantMessage the parent assistant message
     * @param context          current tool-use context
     * @return CompletableFuture resolving to ordered list of MessageUpdateLazy
     */
    public CompletableFuture<List<MessageUpdateLazy>> runToolUse(
            ContentBlock.ToolUseBlock toolUse,
            Message.AssistantMessage assistantMessage,
            ToolUseContext context) {

        return CompletableFuture.supplyAsync(() -> {
            String toolName = toolUse.getName();
            List<MessageUpdateLazy> updates = new ArrayList<>();

            // --- Abort check before doing anything ---
            if (context.isAborted()) {
                Message cancelMsg = createToolResultUserMessage(
                        toolUse.getId(),
                        "<tool_use_error>[Cancelled] Tool use was cancelled</tool_use_error>",
                        true,
                        assistantMessage.getUuid());
                updates.add(new MessageUpdateLazy(cancelMsg, null));
                return updates;
            }

            // --- Tool not found ---
            if (!toolExists(toolName, context)) {
                log.debug("Unknown tool {}: {}", toolName, toolUse.getId());
                Message errorMsg = createToolResultUserMessage(
                        toolUse.getId(),
                        "<tool_use_error>Error: No such tool available: " + toolName + "</tool_use_error>",
                        true,
                        assistantMessage.getUuid());
                updates.add(new MessageUpdateLazy(errorMsg, null));
                return updates;
            }

            Map<String, Object> toolInput = toolUse.getInput() != null
                    ? toolUse.getInput() : Map.of();
            String messageId = assistantMessage.getUuid();
            String requestId = null; // not exposed in AssistantMessage
            McpServerType mcpServerType = getMcpServerType(toolName, context);
            String mcpServerBaseUrl = getMcpServerBaseUrl(toolName, context);

            try {
                // --- Pre-tool hooks ---
                ToolHookService.PreToolHookResult preHookResult =
                        toolHookService.runPreToolHooks(
                                toolName, toolUse.getId(), toolInput, context)
                                .get();

                if (preHookResult instanceof ToolHookService.PreToolHookResult.Block blocked) {
                    Message blockedMsg = createToolResultUserMessage(
                            toolUse.getId(),
                            "<tool_use_error>Hook blocked tool: " + blocked.reason() + "</tool_use_error>",
                            true,
                            assistantMessage.getUuid());
                    updates.add(new MessageUpdateLazy(blockedMsg, null));
                    return updates;
                }

                // Hook may have updated the input
                Map<String, Object> effectiveInput = toolInput;
                if (preHookResult instanceof ToolHookService.PreToolHookResult.Allow allowed) {
                    effectiveInput = allowed.updatedInput();
                }

                // --- Permission check ---
                var toolForPermission = context.getOptions() != null && context.getOptions().getTools() != null
                        ? context.getOptions().getTools().stream()
                                .filter(t -> toolName.equals(t.getName()))
                                .findFirst().orElse(null)
                        : null;
                boolean permitted = toolForPermission == null || permissionService.canUseTool(
                        toolForPermission, effectiveInput, context).get()
                        instanceof com.anthropic.claudecode.model.PermissionResult.AllowDecision;
                if (!permitted) {
                    Message deniedMsg = createToolResultUserMessage(
                            toolUse.getId(),
                            "<tool_use_error>Permission denied for tool: " + toolName + "</tool_use_error>",
                            true,
                            assistantMessage.getUuid());
                    updates.add(new MessageUpdateLazy(deniedMsg, null));
                    return updates;
                }

                // --- Tool execution ---
                long startTime = System.currentTimeMillis();
                log.debug("Executing tool: {} (id={})", toolName, toolUse.getId());

                Object result = executeToolCall(toolName, effectiveInput, context);
                long durationMs = System.currentTimeMillis() - startTime;
                log.debug("Tool {} completed in {}ms", toolName, durationMs);

                // --- Post-tool hooks ---
                ToolHookService.PostToolHookResult postHookResult =
                        toolHookService.runPostToolHooks(
                                toolName, toolUse.getId(), effectiveInput, result, context)
                                .get();

                Object finalOutput = postHookResult != null ? postHookResult.toolOutput() : result;

                // --- Format result message ---
                String resultContent = finalOutput != null ? finalOutput.toString() : "";
                Message resultMsg = createToolResultUserMessage(
                        toolUse.getId(), resultContent, false, assistantMessage.getUuid());
                updates.add(new MessageUpdateLazy(resultMsg, null));

            } catch (Exception e) {
                log.error("Tool execution failed for {}: {}", toolName, e.getMessage(), e);
                String errorContent = "<tool_use_error>Error calling tool " + toolName
                        + ": " + e.getMessage() + "</tool_use_error>";
                Message errorMsg = createToolResultUserMessage(
                        toolUse.getId(), errorContent, true, assistantMessage.getUuid());
                updates.add(new MessageUpdateLazy(errorMsg, null));

                // Run post-failure hooks (best-effort)
                try {
                    toolHookService.runPostToolFailureHooks(
                            toolName, toolUse.getId(), toolUse.getInput() != null ? toolUse.getInput() : Map.of(),
                            e.getMessage(), context).get();
                } catch (Exception hookEx) {
                    log.debug("Post-failure hook error: {}", hookEx.getMessage());
                }
            }

            return updates;
        });
    }

    /**
     * Convenience method: check if a tool is concurrency-safe for a given input.
     * Used by StreamingToolExecutor and ToolOrchestrationService.
     * Translated from isConcurrencySafe() logic in toolOrchestration.ts
     */
    public boolean isConcurrencySafe(
            String toolName,
            Map<String, Object> input,
            ToolUseContext context) {
        // Delegate to tool registry / context
        if (context == null || context.getOptions() == null) return false;
        return context.getOptions().getTools() == null
                ? false
                : context.getOptions().getTools().stream()
                        .filter(t -> toolName.equals(t.getName())
                                || (t.getAliases() != null && t.getAliases().contains(toolName)))
                        .findFirst()
                        .map(t -> {
                            try {
                                @SuppressWarnings({"unchecked","rawtypes"})
                                Tool rawT = (Tool) t;
                                return Boolean.TRUE.equals(rawT.isConcurrencySafe(input));
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .orElse(false);
    }

    // =========================================================================
    // Error classification
    // =========================================================================

    /**
     * Classify a tool execution error into a telemetry-safe string.
     * Translated from classifyToolError() in toolExecution.ts
     */
    public static String classifyToolError(Throwable error) {
        if (error == null) return "UnknownError";
        String name = error.getClass().getSimpleName();
        if (name.length() > 3) return name;
        return "Error";
    }

    // =========================================================================
    // McpServerType
    // =========================================================================

    /**
     * Transport type of the MCP server for a given tool (null for built-in tools).
     * Translated from McpServerType union type in toolExecution.ts
     */
    public enum McpServerType {
        STDIO("stdio"),
        SSE("sse"),
        HTTP("http"),
        WS("ws"),
        SDK("sdk"),
        SSE_IDE("sse-ide"),
        WS_IDE("ws-ide"),
        CLAUDEAI_PROXY("claudeai-proxy");

        private final String value;
        McpServerType(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    // =========================================================================
    // MessageUpdateLazy
    // =========================================================================

    /**
     * Lazy message update: a message to emit plus an optional context modifier.
     * Translated from MessageUpdateLazy in toolExecution.ts
     */
    public static class MessageUpdateLazy {
        private final Message message;
        private final Function<ToolUseContext, ToolUseContext> contextModifier;

        public MessageUpdateLazy(Message message, Function<ToolUseContext, ToolUseContext> contextModifier) {
            this.message = message; this.contextModifier = contextModifier;
        }
        public Message getMessage() { return message; }
        public Function<ToolUseContext, ToolUseContext> getContextModifier() { return contextModifier; }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private boolean toolExists(String toolName, ToolUseContext context) {
        if (context.getOptions() == null || context.getOptions().getTools() == null) return false;
        return context.getOptions().getTools().stream()
                .anyMatch(t -> toolName.equals(t.getName())
                        || (t.getAliases() != null && t.getAliases().contains(toolName)));
    }

    @SuppressWarnings("unchecked")
    private Object executeToolCall(
            String toolName,
            Map<String, Object> input,
            ToolUseContext context) throws Exception {

        if (context.getOptions() == null || context.getOptions().getTools() == null) {
            throw new IllegalStateException("No tools available in context");
        }
        var tool = context.getOptions().getTools().stream()
                .filter(t -> toolName.equals(t.getName())
                        || (t.getAliases() != null && t.getAliases().contains(toolName)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolName));

        // tool.call() returns CompletableFuture; block here since we're already async
        @SuppressWarnings({"unchecked","rawtypes"})
        Tool rawTool = (Tool) tool;
        var future = rawTool.call(input, context, null, null, null);
        var result = future.get(60, TimeUnit.SECONDS);
        return result;
    }

    private McpServerType getMcpServerType(String toolName, ToolUseContext context) {
        if (!toolName.startsWith("mcp__")) return null;
        if (context.getOptions() == null || context.getOptions().getMcpClients() == null) return null;
        return context.getOptions().getMcpClients().stream()
                .filter(c -> toolName.contains(normalizeForComparison(c.getName())))
                .findFirst()
                .map(c -> {
                    String typeStr = c.getConfig() != null
                        ? (String) c.getConfig().getOrDefault("type", c.getTransport())
                        : c.getTransport();
                    try {
                        return McpServerType.valueOf((typeStr != null ? typeStr : "stdio")
                                .replace("-", "_").toUpperCase());
                    } catch (Exception e) {
                        return McpServerType.STDIO;
                    }
                })
                .orElse(null);
    }

    private String getMcpServerBaseUrl(String toolName, ToolUseContext context) {
        if (!toolName.startsWith("mcp__")) return null;
        if (context.getOptions() == null || context.getOptions().getMcpClients() == null) return null;
        return context.getOptions().getMcpClients().stream()
                .filter(c -> toolName.contains(normalizeForComparison(c.getName())))
                .findFirst()
                .map(c -> {
                    if (c.getConfig() == null) return null;
                    String url = (String) c.getConfig().get("url");
                    if (url == null) return null;
                    try {
                        java.net.URL u = new java.net.URL(url);
                        return u.getProtocol() + "://" + u.getHost();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private String normalizeForComparison(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }

    private Message createToolResultUserMessage(
            String toolUseId,
            String content,
            boolean isError,
            String sourceAssistantUuid) {

        ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock();
        resultBlock.setType("tool_result");
        resultBlock.setToolUseId(toolUseId);
        resultBlock.setContent(content);
        resultBlock.setIsError(isError);

        return Message.UserMessage.builder()
                .type("user")
                .uuid(UUID.randomUUID().toString())
                .content(List.of(resultBlock))
                .toolUseResult(content)
                .sourceToolAssistantUUID(sourceAssistantUuid)
                .build();
    }

    /**
     * Execute a tool by name synchronously. Used by MCP entrypoint.
     * Looks up the tool from the registered tool list and calls it with a
     * minimal non-interactive ToolUseContext.
     *
     * Input is converted from Map to the tool's typed Input class via Jackson.
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public String executeToolByName(String toolName, java.util.Map<String, Object> input) {
        log.debug("[ToolExecution] executeToolByName: {}", toolName);

        if (allTools == null || allTools.isEmpty()) {
            return "<tool_use_error>No tools registered</tool_use_error>";
        }

        // Build a minimal non-interactive context
        com.anthropic.claudecode.model.ToolUseContext.Options options =
                com.anthropic.claudecode.model.ToolUseContext.Options.builder()
                        .isNonInteractiveSession(true)
                        .tools((List<Tool<?, ?>>) (List<?>) allTools)
                        .build();
        com.anthropic.claudecode.model.ToolUseContext ctx =
                com.anthropic.claudecode.model.ToolUseContext.builder()
                        .options(options)
                        .build();

        try {
            // Delegate to the existing executeToolCall which handles raw Map → tool dispatch
            Object result = executeToolCall(toolName, input, ctx);
            if (result instanceof com.anthropic.claudecode.model.ToolResult<?> tr) {
                Object data = tr.getData();
                return data != null ? data.toString() : "";
            }
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log.error("[ToolExecution] executeToolByName {} failed: {}", toolName, e.getMessage());
            return "<tool_use_error>Error calling " + toolName + ": " + e.getMessage() + "</tool_use_error>";
        }
    }
}
