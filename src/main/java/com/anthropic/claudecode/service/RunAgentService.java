package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Run agent service.
 * Translated from src/tools/AgentTool/runAgent.ts
 *
 * Core agent execution engine: builds context, system prompt, runs the query loop,
 * records transcripts, and handles cleanup.
 */
@Slf4j
@Service
public class RunAgentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunAgentService.class);


    private final QueryEngine queryEngine;
    private final SessionStorageService sessionStorageService;
    private final AgentToolUtilsService agentToolUtilsService;

    @Autowired
    public RunAgentService(QueryEngine queryEngine,
                           SessionStorageService sessionStorageService,
                           AgentToolUtilsService agentToolUtilsService) {
        this.queryEngine = queryEngine;
        this.sessionStorageService = sessionStorageService;
        this.agentToolUtilsService = agentToolUtilsService;
    }

    // -------------------------------------------------------------------------
    // Parameters record — mirrors the TypeScript parameter object
    // -------------------------------------------------------------------------

    /**
     * All parameters for a single agent run.
     * Mirrors the parameter destructuring of runAgent() in TypeScript.
     */
    public record RunAgentParams(
        AgentDefinition agentDefinition,
        List<Message> promptMessages,
        ToolUseContext toolUseContext,
        boolean isAsync,
        Boolean canShowPermissionPrompts,
        List<Message> forkContextMessages,
        String querySource,
        RunAgentOverride override,
        String model,
        Integer maxTurns,
        Boolean preserveToolUseResults,
        List<Tool<?, ?>> availableTools,
        List<String> allowedTools,
        Consumer<CacheSafeParams> onCacheSafeParams,
        Object contentReplacementState,
        Boolean useExactTools,
        String worktreePath,
        String description,
        String transcriptSubdir,
        Runnable onQueryProgress
    ) {}

    /**
     * Optional overrides that can be supplied to runAgent().
     * Mirrors the {@code override} parameter union type.
     */
    public record RunAgentOverride(
        Map<String, String> userContext,
        Map<String, String> systemContext,
        Object systemPrompt,
        AbortController abortController,
        String agentId
    ) {}

    /**
     * Cache-safe params passed to onCacheSafeParams callback.
     * Mirrors the CacheSafeParams type from utils/forkedAgent.ts.
     */
    public record CacheSafeParams(
        Object systemPrompt,
        Map<String, String> userContext,
        Map<String, String> systemContext,
        ToolUseContext toolUseContext,
        List<Message> forkContextMessages
    ) {}

    /**
     * Abort controller shim (Java has no built-in equivalent).
     */
    public static class AbortController {
        private volatile boolean aborted = false;

        public void abort() {
            this.aborted = true;
        }

        public boolean isAborted() {
            return aborted;
        }
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Run an agent and return a stream of messages.
     * Translated from the async generator runAgent() in runAgent.ts.
     *
     * Returns a CompletableFuture that resolves to the ordered list of messages
     * produced during the agent run. Callers that need streaming behaviour should
     * supply {@code params.onQueryProgress()} to receive liveness notifications.
     */
    public CompletableFuture<List<Message>> runAgent(RunAgentParams params) {
        return CompletableFuture.supplyAsync(() -> {
            AgentDefinition agentDefinition = params.agentDefinition();
            ToolUseContext toolUseContext = params.toolUseContext();

            // Derive agent ID
            String agentId = (params.override() != null && params.override().agentId() != null)
                ? params.override().agentId()
                : createAgentId();

            log.debug("[Agent: {}] Starting run, agentId={}, isAsync={}",
                agentDefinition.getAgentType(), agentId, params.isAsync());

            // Build context messages (filter incomplete tool calls from fork parent)
            List<Message> contextMessages = params.forkContextMessages() != null
                ? filterIncompleteToolCalls(params.forkContextMessages())
                : new ArrayList<>();

            List<Message> initialMessages = new ArrayList<>(contextMessages);
            initialMessages.addAll(params.promptMessages());

            // Resolve tools
            List<Tool<?, ?>> resolvedTools = Boolean.TRUE.equals(params.useExactTools())
                ? params.availableTools()
                : agentToolUtilsService.resolveAgentTools(agentDefinition, params.availableTools(), params.isAsync());

            // Build agent-specific options
            ToolUseContext agentContext = buildAgentContext(
                params, agentId, agentDefinition, resolvedTools, initialMessages);

            // Expose cache-safe params for background summarization
            if (params.onCacheSafeParams() != null) {
                params.onCacheSafeParams().accept(new CacheSafeParams(
                    null, // systemPrompt resolved below
                    null,
                    null,
                    agentContext,
                    initialMessages
                ));
            }

            // Fire-and-forget: record initial transcript
            sessionStorageService.recordSidechainTranscript(initialMessages, agentId)
                .exceptionally(err -> {
                    log.debug("Failed to record sidechain transcript: {}", err.getMessage());
                    return null;
                });

            // Fire-and-forget: persist agent metadata
            sessionStorageService.writeAgentMetadata(agentId, agentDefinition.getAgentType(),
                params.worktreePath(), params.description())
                .exceptionally(err -> {
                    log.debug("Failed to write agent metadata: {}", err.getMessage());
                    return null;
                });

            List<Message> results = new ArrayList<>();
            String lastRecordedUuid = initialMessages.isEmpty()
                ? null
                : initialMessages.get(initialMessages.size() - 1).getUuid();

            try {
                // Run the query loop
                List<Message> queryMessages = queryEngine.query(
                    initialMessages,
                    agentContext,
                    event -> {
                        if (params.onQueryProgress() != null) params.onQueryProgress().run();
                    }
                ).get();

                for (Message message : queryMessages) {
                    if (isRecordableMessage(message)) {
                        final String prevUuid = lastRecordedUuid;
                        sessionStorageService.recordSidechainTranscript(
                            List.of(message), agentId, prevUuid)
                            .exceptionally(err -> {
                                log.debug("Failed to record sidechain transcript: {}", err.getMessage());
                                return null;
                            });
                        if (!Message.TYPE_PROGRESS.equals(message.getType())) {
                            lastRecordedUuid = message.getUuid();
                        }
                        results.add(message);
                    }
                }

                // Run callback for built-in agents
                if (agentDefinition.isBuiltIn() && agentDefinition.getCallback() != null) {
                    agentDefinition.getCallback().run();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Agent: {}] Run interrupted", agentDefinition.getAgentType());
            } catch (Exception e) {
                log.error("[Agent: {}] Run failed: {}", agentDefinition.getAgentType(), e.getMessage(), e);
                throw new RuntimeException("Agent run failed: " + e.getMessage(), e);
            } finally {
                // Cleanup: release cloned resources
                log.debug("[Agent: {}] Cleaning up, agentId={}", agentDefinition.getAgentType(), agentId);
                initialMessages.clear();
            }

            return results;
        });
    }

    // -------------------------------------------------------------------------
    // filterIncompleteToolCalls
    // -------------------------------------------------------------------------

    /**
     * Filters out assistant messages with incomplete tool calls (tool uses without results).
     * Prevents API errors when sending messages with orphaned tool calls.
     * Translated from filterIncompleteToolCalls().
     */
    public List<Message> filterIncompleteToolCalls(List<Message> messages) {
        // Build a set of tool-use IDs that have results
        Set<String> toolUseIdsWithResults = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof Message.UserMessage userMsg) {
                Object content = userMsg.getContent();
                if (content instanceof List<?> blocks) {
                    for (Object block : blocks) {
                        if (block instanceof ContentBlock.ToolResultBlock tr
                                && tr.getToolUseId() != null) {
                            toolUseIdsWithResults.add(tr.getToolUseId());
                        }
                    }
                }
            }
        }

        // Filter out assistant messages with incomplete tool calls
        List<Message> filtered = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof Message.AssistantMessage assistantMsg) {
                Object content = assistantMsg.getContent();
                if (content instanceof List<?> blocks) {
                    boolean hasIncomplete = blocks.stream().anyMatch(block ->
                        block instanceof ContentBlock.ToolUseBlock tu
                            && tu.getId() != null
                            && !toolUseIdsWithResults.contains(tu.getId())
                    );
                    if (hasIncomplete) continue; // drop this message
                }
            }
            filtered.add(message);
        }
        return filtered;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Determine whether a message should be persisted to the sidechain transcript.
     * Translated from isRecordableMessage() type guard.
     */
    private boolean isRecordableMessage(Message message) {
        if (message == null) return false;
        return switch (message.getType()) {
            case Message.TYPE_ASSISTANT, Message.TYPE_USER, Message.TYPE_PROGRESS -> true;
            case Message.TYPE_SYSTEM -> {
                String subtype = message.getSubtype();
                yield "compact_boundary".equals(subtype);
            }
            default -> false;
        };
    }

    private ToolUseContext buildAgentContext(
            RunAgentParams params,
            String agentId,
            AgentDefinition agentDefinition,
            List<Tool<?, ?>> resolvedTools,
            List<Message> initialMessages) {

        ToolUseContext parent = params.toolUseContext();
        ToolUseContext.Options parentOptions = parent != null ? parent.getOptions() : null;

        boolean isNonInteractive = Boolean.TRUE.equals(params.useExactTools())
            ? (parentOptions != null && Boolean.TRUE.equals(parentOptions.isNonInteractiveSession()))
            : params.isAsync();

        ToolUseContext.Options agentOptions = ToolUseContext.Options.builder()
            .isNonInteractiveSession(isNonInteractive)
            .appendSystemPrompt(parentOptions != null ? parentOptions.getAppendSystemPrompt() : null)
            .tools(resolvedTools)
            .commands(List.of())
            .debug(parentOptions != null && parentOptions.isDebug())
            .verbose(parentOptions != null && parentOptions.isVerbose())
            .mainLoopModel(
                params.model() != null ? params.model()
                    : (parentOptions != null ? parentOptions.getMainLoopModel() : null))
            .thinkingConfig(Boolean.TRUE.equals(params.useExactTools())
                ? (parentOptions != null ? parentOptions.getThinkingConfig() : null)
                : new ToolUseContext.ThinkingConfig())
            .mcpClients(parentOptions != null ? parentOptions.getMcpClients() : List.of())
            .mcpResources(parentOptions != null ? parentOptions.getMcpResources() : Map.of())
            .agentDefinitions(parentOptions != null ? parentOptions.getAgentDefinitions() : null)
            .build();

        return ToolUseContext.builder()
            .options(agentOptions)
            .messages(new ArrayList<>(initialMessages))
            .readFileState(new HashMap<>())
            .inProgressToolUseIds(new HashSet<>())
            .agentId(agentId)
            .agentType(agentDefinition.getAgentType())
            .preserveToolUseResults(Boolean.TRUE.equals(params.preserveToolUseResults()))
            .build();
    }

    private String createAgentId() {
        return "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
