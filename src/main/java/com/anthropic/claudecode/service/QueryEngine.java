package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.model.ContentBlock.ToolUseBlock;
import com.anthropic.claudecode.service.QueryConfigService.QueryConfig;
import com.anthropic.claudecode.service.QueryDepsService.QueryDeps;
import com.anthropic.claudecode.service.QueryTokenBudgetService.BudgetTracker;
import com.anthropic.claudecode.service.QueryTokenBudgetService.TokenBudgetDecision;
import com.anthropic.claudecode.service.QueryTokenBudgetService.ContinueDecision;
import com.anthropic.claudecode.service.QueryTokenBudgetService.StopDecision;
import com.anthropic.claudecode.util.QueryProfiler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Core query engine that manages the full conversation loop with Claude.
 *
 * Merged from two TypeScript sources:
 *   - src/query.ts          : low-level agentic loop (query / queryLoop)
 *   - src/QueryEngine.ts    : high-level session lifecycle (QueryEngine class)
 *
 * Responsibilities:
 *   - Session-level state: mutable message list, abort controller, usage totals
 *   - Turn entry (submitMessage): process user input, build system prompt, invoke query loop
 *   - Query loop (queryLoop): microcompact → autocompact → stream model → run tools → stop hooks
 *   - Token-budget auto-continuation (TOKEN_BUDGET feature)
 *   - Reactive / auto-compact on prompt-too-long (delegates to AutoCompactService)
 *   - Streaming tool execution (StreamingToolExecutor)
 *   - Fallback model on FallbackTriggeredError
 *
 * Java design decisions:
 *   - TypeScript async generators → CompletableFuture pipeline with Consumer<QueryEvent> callbacks
 *   - Per-iteration mutable State struct → inner {@link LoopState} class
 *   - Sealed interface QueryEvent mirrors the TS StreamEvent | Message union
 *   - Java 21 records for immutable inner types (QueryParams, Terminal, etc.)
 *   - @Slf4j for all debug / error logging
 */
@Slf4j
@Service
public class QueryEngine {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryEngine.class);


    // =========================================================================
    // Dependencies
    // =========================================================================

    private final ClaudeApiService claudeApiService;
    private final ToolExecutionService toolExecutionService;
    private final StreamingToolExecutorFactory streamingExecutorFactory;
    private final QueryConfigService queryConfigService;
    private final QueryDepsService queryDepsService;
    private final QueryTokenBudgetService tokenBudgetService;
    private final StopHookService stopHookService;
    private final MicroCompactService microCompactService;
    private final AutoCompactService autoCompactService;
    private final SessionStorageService sessionStorageService;
    private final AnalyticsService analyticsService;
    private final QueryContextService queryContextService;

    @Autowired
    public QueryEngine(
            ClaudeApiService claudeApiService,
            ToolExecutionService toolExecutionService,
            StreamingToolExecutorFactory streamingExecutorFactory,
            QueryConfigService queryConfigService,
            QueryDepsService queryDepsService,
            QueryTokenBudgetService tokenBudgetService,
            StopHookService stopHookService,
            MicroCompactService microCompactService,
            AutoCompactService autoCompactService,
            SessionStorageService sessionStorageService,
            AnalyticsService analyticsService,
            QueryContextService queryContextService) {
        this.claudeApiService = claudeApiService;
        this.toolExecutionService = toolExecutionService;
        this.streamingExecutorFactory = streamingExecutorFactory;
        this.queryConfigService = queryConfigService;
        this.queryDepsService = queryDepsService;
        this.tokenBudgetService = tokenBudgetService;
        this.stopHookService = stopHookService;
        this.microCompactService = microCompactService;
        this.autoCompactService = autoCompactService;
        this.sessionStorageService = sessionStorageService;
        this.analyticsService = analyticsService;
        this.queryContextService = queryContextService;
    }

    // =========================================================================
    // Session-level state (mirrors QueryEngine class fields in QueryEngine.ts)
    // =========================================================================

    /** Mutable message list for the entire conversation session. */
    private List<Message> mutableMessages = new ArrayList<>();

    /** Session-wide abort controller. */
    private volatile boolean aborted = false;

    /** Whether the orphaned permission has already been handled. */
    private boolean hasHandledOrphanedPermission = false;

    /** Accumulated API usage across turns. */
    private UsageSummary totalUsage = UsageSummary.empty();

    // =========================================================================
    // Public entry point: submitMessage
    // Mirrors: async *submitMessage() in QueryEngine.ts
    // =========================================================================

    /**
     * High-level query method accepting a message list.
     * Translates the last user message into a prompt and runs a full turn.
     * Used by ReplLauncher and other high-level callers.
     *
     * @param messages   current message history (last must be UserMessage)
     * @param systemPrompt system prompt string
     * @param context    tool use context
     * @param onEvent    event listener
     * @return CompletableFuture resolving to updated message list
     */
    public CompletableFuture<java.util.List<Message>> query(
            java.util.List<Message> messages,
            String systemPrompt,
            com.anthropic.claudecode.model.ToolUseContext context,
            Consumer<QueryEvent> onEvent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (messages == null || messages.isEmpty()) {
                    return java.util.List.of();
                }
                // Extract the last user message as the prompt
                Message lastMsg = messages.get(messages.size() - 1);
                String prompt = "";
                if (lastMsg instanceof Message.UserMessage um && um.getContent() != null) {
                    prompt = um.getContent().stream()
                        .filter(b -> b instanceof ContentBlock.TextBlock)
                        .map(b -> ((ContentBlock.TextBlock) b).getText())
                        .filter(t -> t != null)
                        .collect(java.util.stream.Collectors.joining());
                }
                // Build a minimal config
                QueryEngineConfig config = new QueryEngineConfig();
                if (systemPrompt != null) {
                    config.setCustomSystemPrompt(systemPrompt);
                }
                // Set the messages as the current conversation history
                mutableMessages = new ArrayList<>(messages);
                TurnResult result = runTurn(prompt, null, config, onEvent);
                if (result instanceof SuccessTurnResult str) {
                    return str.messages() != null ? str.messages() : messages;
                }
                return messages;
            } catch (Exception e) {
                log.error("QueryEngine.query failed", e);
                onEvent.accept(QueryEvent.error(e));
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Overload of query() that accepts a ToolUseContext instead of systemPrompt.
     */
    public CompletableFuture<java.util.List<Message>> query(
            java.util.List<Message> messages,
            com.anthropic.claudecode.model.ToolUseContext context,
            Consumer<QueryEvent> onEvent) {
        return query(messages, (String) null, context, onEvent);
    }

    /**
     * Submit a user message and run a full conversation turn.
     *
     * Accepts a prompt (plain text or content-block array), processes any slash
     * commands, assembles the system prompt, then drives the query loop until a
     * terminal condition is reached.
     *
     * @param prompt   user message text or content blocks
     * @param options  optional per-turn overrides (uuid, isMeta flag)
     * @param params   engine-level configuration (tools, model, compaction, etc.)
     * @param onEvent  sink for stream events / messages (SDK output)
     * @return CompletableFuture that resolves when the turn completes
     */
    public CompletableFuture<TurnResult> submitMessage(
            Object prompt,
            TurnOptions options,
            QueryEngineConfig params,
            Consumer<QueryEvent> onEvent) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                return runTurn(prompt, options, params, onEvent);
            } catch (Exception e) {
                log.error("QueryEngine.submitMessage failed", e);
                onEvent.accept(QueryEvent.error(e));
                return TurnResult.error(e.getMessage());
            }
        });
    }

    // =========================================================================
    // Turn execution
    // =========================================================================

    private TurnResult runTurn(
            Object prompt,
            TurnOptions options,
            QueryEngineConfig config,
            Consumer<QueryEvent> onEvent) {

        // Snapshot immutable config once per turn.
        QueryConfig queryConfig = queryConfigService.buildQueryConfig();
        QueryDeps deps = config.getDeps() != null
            ? config.getDeps()
            : queryDepsService.productionDeps();

        // Fetch system-prompt parts (blocking here; async in TS).
        QueryContextService.SystemPromptParts contextParts;
        try {
            contextParts = queryContextService.fetchSystemPromptParts(
                config.getTools(),
                config.getMainLoopModel(),
                config.getAdditionalWorkingDirectories(),
                config.getMcpClients(),
                config.getCustomSystemPrompt()
            ).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch system prompt parts", e);
        }

        // Assemble final system prompt.
        List<String> promptParts = new ArrayList<>();
        if (config.getCustomSystemPrompt() != null) {
            promptParts.add(config.getCustomSystemPrompt());
        } else {
            promptParts.addAll(contextParts.getDefaultSystemPrompt());
        }
        if (config.getAppendSystemPrompt() != null && !config.getAppendSystemPrompt().isBlank()) {
            promptParts.add(config.getAppendSystemPrompt());
        }
        SystemPrompt systemPrompt = SystemPrompt.of(promptParts);

        // Build query parameters and drive the query loop.
        QueryParams queryParams = new QueryParams(
            mutableMessages,
            systemPrompt,
            contextParts.getUserContext(),
            contextParts.getSystemContext(),
            config,
            deps,
            queryConfig
        );

        return queryLoop(queryParams, onEvent);
    }

    // =========================================================================
    // Query loop  (mirrors async function* queryLoop() in query.ts)
    // =========================================================================

    /**
     * Main agentic loop: stream model response → run tools → stop hooks → repeat.
     *
     * Translated from queryLoop() in query.ts. The TS generator yield* chain is
     * flattened here into a while loop with explicit event dispatch via onEvent.
     */
    private TurnResult queryLoop(QueryParams params, Consumer<QueryEvent> onEvent) {

        final QueryConfig config = params.queryConfig();
        final QueryDeps  deps   = params.deps();
        final SystemPrompt systemPrompt = params.systemPrompt();
        final Map<String, String> userContext   = params.userContext();
        final Map<String, String> systemContext = params.systemContext();
        final QueryEngineConfig engineConfig    = params.engineConfig();

        // Mutable cross-iteration state.
        LoopState state = new LoopState(
            new ArrayList<>(params.messages()),
            0,   // maxOutputTokensRecoveryCount
            false // hasAttemptedReactiveCompact
        );

        // Token budget tracker (one per query turn).
        BudgetTracker budgetTracker = tokenBudgetService.createBudgetTracker();

        QueryProfiler.queryCheckpoint("query_fn_entry");

        // ---- main loop -------------------------------------------------------
        while (true) {
            if (aborted) {
                return TurnResult.aborted();
            }

            onEvent.accept(QueryEvent.streamRequestStart());
            QueryProfiler.queryCheckpoint("query_api_loop_start");

            // Unique query-chain tracking ID per turn.
            String chainId = deps.getUuid().get();

            // ---- Microcompact ------------------------------------------------
            QueryProfiler.queryCheckpoint("query_microcompact_start");
            List<Message> messagesForQuery = microCompactService
                .compact(state.messages(), engineConfig)
                .join();
            QueryProfiler.queryCheckpoint("query_microcompact_end");

            // ---- Autocompact -------------------------------------------------
            QueryProfiler.queryCheckpoint("query_autocompact_start");
            AutoCompactService.AutoCompactResult compactionResult = autoCompactService
                .autoCompactIfNeeded(messagesForQuery, engineConfig.getMainLoopModel(),
                        engineConfig.getQuerySource(), null, 0)
                .join();
            QueryProfiler.queryCheckpoint("query_autocompact_end");

            if (compactionResult != null && compactionResult.isWasCompacted() && compactionResult.getCompactedMessages() != null) {
                List<Message> postCompact = compactionResult.getCompactedMessages();
                for (Message m : postCompact) {
                    onEvent.accept(QueryEvent.message(m));
                }
                messagesForQuery = postCompact;
                analyticsService.logEvent("tengu_auto_compact_succeeded", Map.of(
                    "originalMessageCount", state.messages().size(),
                    "compactedMessageCount", postCompact.size()
                ));
            }

            // ---- Streaming model call ----------------------------------------
            QueryProfiler.queryCheckpoint("query_api_streaming_start");

            List<Message> assistantMessages = new ArrayList<>();
            List<Message> toolResults       = new ArrayList<>();
            List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
            boolean needsFollowUp = false;

            // Streaming tool executor for parallel execution.
            StreamingToolExecutor streamingExecutor = config.getGates().isStreamingToolExecution()
                ? streamingExecutorFactory.create(engineConfig)
                : null;

            try {
                // Stream the model response.
                for (StreamEvent event : claudeApiService.streamMessages(
                        messagesForQuery, systemPrompt, userContext, systemContext, engineConfig)) {

                    if (aborted) {
                        handleAbort(streamingExecutor, assistantMessages, onEvent);
                        return TurnResult.aborted();
                    }

                    onEvent.accept(QueryEvent.streamEvent(event));

                    if (event instanceof StreamEvent.AssistantMessageEvent ame) {
                        Message.AssistantMessage msg = ame.message();
                        assistantMessages.add(msg);

                        // Emit TextDelta events for text content blocks
                        if (msg.getContent() != null) {
                            for (ContentBlock block : msg.getContent()) {
                                if (block instanceof ContentBlock.TextBlock tb && tb.getText() != null) {
                                    onEvent.accept(QueryEvent.textDelta(tb.getText()));
                                }
                            }
                        }

                        // Collect tool_use blocks.
                        List<ToolUseBlock> newBlocks = msg.getContent().stream()
                            .filter(b -> b instanceof ToolUseBlock)
                            .map(b -> (ToolUseBlock) b)
                            .collect(Collectors.toList());

                        if (!newBlocks.isEmpty()) {
                            toolUseBlocks.addAll(newBlocks);
                            needsFollowUp = true;
                        }

                        // Feed to streaming executor for concurrent execution.
                        if (streamingExecutor != null) {
                            for (ToolUseBlock toolBlock : newBlocks) {
                                streamingExecutor.addTool(toolBlock, msg);
                            }
                        }

                        // Drain any already-completed streaming results.
                        if (streamingExecutor != null) {
                            for (Message result : streamingExecutor.getCompletedResults()) {
                                onEvent.accept(QueryEvent.message(result));
                                toolResults.add(result);
                            }
                        }
                    }
                }
            } catch (FallbackTriggeredError fte) {
                // Model fallback: switch model and retry the same iteration.
                engineConfig.setMainLoopModel(fte.getFallbackModel());
                log.info("Model fallback triggered: {} → {}",
                    fte.getOriginalModel(), fte.getFallbackModel());
                analyticsService.logEvent("tengu_model_fallback_triggered", Map.of(
                    "original_model", fte.getOriginalModel(),
                    "fallback_model", fte.getFallbackModel()
                ));
                state = state.withMessages(state.messages()); // keep messages, retry
                continue;
            } catch (Exception ex) {
                log.error("Query error during streaming", ex);
                analyticsService.logEvent("tengu_query_error",
                    Map.of("assistantMessages", assistantMessages.size()));
                onEvent.accept(QueryEvent.error(ex));
                return TurnResult.error(ex.getMessage());
            }

            QueryProfiler.queryCheckpoint("query_api_streaming_end");

            // ---- Tool execution (non-streaming path) -------------------------
            if (needsFollowUp) {
                QueryProfiler.queryCheckpoint("query_tool_execution_start");

                if (streamingExecutor != null) {
                    // Drain remaining results from streaming executor.
                    for (Message result : streamingExecutor.getAllResults().join()) {
                        onEvent.accept(QueryEvent.message(result));
                        toolResults.add(result);
                    }
                } else {
                    // Sequential tool execution.
                    List<Message> execResults = toolExecutionService
                        .runTools(toolUseBlocks, assistantMessages, engineConfig)
                        .join();
                    for (Message result : execResults) {
                        onEvent.accept(QueryEvent.message(result));
                        toolResults.add(result);
                    }
                }

                QueryProfiler.queryCheckpoint("query_tool_execution_end");

                // Append all messages to the mutable store and loop.
                List<Message> nextMessages = new ArrayList<>(state.messages());
                nextMessages.addAll(assistantMessages);
                nextMessages.addAll(toolResults);
                state = state.withMessages(nextMessages);

                // Token-budget check before the next iteration.
                if (engineConfig.getTaskBudget() != null) {
                    int turnTokens = countTokens(nextMessages);
                    TokenBudgetDecision decision = tokenBudgetService.checkTokenBudget(
                        budgetTracker, null, engineConfig.getTaskBudget().total(), turnTokens);
                    if (decision instanceof StopDecision sd) {
                        if (sd.completionEvent() != null) {
                            analyticsService.logEvent("tengu_token_budget_completed",
                                Map.of("continuations", sd.completionEvent().continuationCount()));
                        }
                        break; // Hard stop — budget exhausted.
                    }
                }

                continue; // More tool results → loop back to call model again.
            }

            // ---- No tool use: run stop hooks then decide whether to continue --
            List<Message> allMessages = new ArrayList<>(state.messages());
            allMessages.addAll(assistantMessages);
            state = state.withMessages(allMessages);

            StopHookService.StopHookResult hookResult = stopHookService.handleStopHooks(
                messagesForQuery,
                assistantMessages,
                systemPrompt,
                userContext,
                systemContext,
                buildStopHookContext(engineConfig),
                engineConfig.getQuerySource(),
                false
            ).join();

            if (hookResult.isPreventContinuation()) {
                return TurnResult.stopHookPrevented();
            }

            if (!hookResult.getBlockingErrors().isEmpty()) {
                // Blocking errors: add them as user messages and loop back.
                List<Message> withErrors = new ArrayList<>(state.messages());
                withErrors.addAll(hookResult.getBlockingErrors());
                state = state.withMessages(withErrors);
                continue;
            }

            // Normal end of turn.
            mutableMessages = state.messages();
            return TurnResult.success(state.messages());
        }

        // Exited loop via budget exhaustion.
        mutableMessages = state.messages();
        return TurnResult.success(state.messages());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void handleAbort(
            StreamingToolExecutor executor,
            List<Message> assistantMessages,
            Consumer<QueryEvent> onEvent) {
        if (executor != null) {
            try {
                for (Message result : executor.getAllResults().get(10, TimeUnit.SECONDS)) {
                    onEvent.accept(QueryEvent.message(result));
                }
            } catch (Exception e) {
                log.debug("Error draining executor on abort", e);
            }
        }
        onEvent.accept(QueryEvent.userInterruption());
    }

    private int countTokens(List<Message> messages) {
        // Rough estimate: 4 chars ≈ 1 token.
        return messages.stream()
            .mapToInt(m -> m.toString().length() / 4)
            .sum();
    }

    private StopHookService.ToolUseContext buildStopHookContext(QueryEngineConfig config) {
        return new StopHookService.ToolUseContext() {
            @Override public String getAgentId() { return config.getAgentId(); }
            @Override public boolean isAborted()  { return aborted; }
            @Override public int getQueryDepth()  { return 0; }
        };
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Mutable per-iteration loop state.
     * Mirrors the `state` variable in queryLoop() in query.ts.
     */
    private record LoopState(
            List<Message> messages,
            int maxOutputTokensRecoveryCount,
            boolean hasAttemptedReactiveCompact
    ) {
        LoopState withMessages(List<Message> messages) {
            return new LoopState(messages, maxOutputTokensRecoveryCount, hasAttemptedReactiveCompact);
        }
    }

    /**
     * Parameters for a single query invocation.
     * Mirrors: type QueryParams in query.ts
     */
    public record QueryParams(
            List<Message> messages,
            SystemPrompt systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            QueryEngineConfig engineConfig,
            QueryDeps deps,
            QueryConfig queryConfig
    ) {}

    /**
     * High-level per-session config (mirrors QueryEngineConfig in QueryEngine.ts).
     */
    public static class QueryEngineConfig {
        private List<Object> tools;
        private List<Object> mcpClients;
        private List<String> additionalWorkingDirectories;
        private String mainLoopModel;
        private String customSystemPrompt;
        private String appendSystemPrompt;
        private String querySource;
        private String agentId;
        private Integer maxTurns;
        private TaskBudget taskBudget;
        private QueryDeps deps;

        // Getters and setters
        public List<Object> getTools() { return tools; }
        public void setTools(List<Object> tools) { this.tools = tools; }
        public List<Object> getMcpClients() { return mcpClients; }
        public void setMcpClients(List<Object> mcpClients) { this.mcpClients = mcpClients; }
        public List<String> getAdditionalWorkingDirectories() { return additionalWorkingDirectories; }
        public void setAdditionalWorkingDirectories(List<String> d) { this.additionalWorkingDirectories = d; }
        public String getMainLoopModel() { return mainLoopModel; }
        public void setMainLoopModel(String mainLoopModel) { this.mainLoopModel = mainLoopModel; }
        public String getCustomSystemPrompt() { return customSystemPrompt; }
        public void setCustomSystemPrompt(String p) { this.customSystemPrompt = p; }
        public String getAppendSystemPrompt() { return appendSystemPrompt; }
        public void setAppendSystemPrompt(String p) { this.appendSystemPrompt = p; }
        public String getQuerySource() { return querySource; }
        public void setQuerySource(String querySource) { this.querySource = querySource; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public Integer getMaxTurns() { return maxTurns; }
        public void setMaxTurns(Integer maxTurns) { this.maxTurns = maxTurns; }
        public TaskBudget getTaskBudget() { return taskBudget; }
        public void setTaskBudget(TaskBudget taskBudget) { this.taskBudget = taskBudget; }
        public QueryDeps getDeps() { return deps; }
        public void setDeps(QueryDeps deps) { this.deps = deps; }
    }

    /** Mirrors: taskBudget?: { total: number } in QueryParams */
    public record TaskBudget(int total) {}

    /** Per-turn call options. */
    public record TurnOptions(String uuid, boolean isMeta) {
        public static TurnOptions defaults() { return new TurnOptions(null, false); }
    }

    /**
     * Terminal result of a turn.
     * Mirrors: type Terminal in query/transitions.ts
     */
    public sealed interface TurnResult
            permits QueryEngine.SuccessTurnResult,
                    QueryEngine.AbortedTurnResult,
                    QueryEngine.ErrorTurnResult,
                    QueryEngine.StopHookPreventedTurnResult {
        boolean isSuccess();

        static TurnResult success(List<Message> messages) { return new SuccessTurnResult(messages); }
        static TurnResult aborted() { return new AbortedTurnResult(); }
        static TurnResult error(String message) { return new ErrorTurnResult(message); }
        static TurnResult stopHookPrevented() { return new StopHookPreventedTurnResult(); }
    }

    public record SuccessTurnResult(List<Message> messages) implements TurnResult {
        @Override public boolean isSuccess() { return true; }
    }
    public record AbortedTurnResult() implements TurnResult {
        @Override public boolean isSuccess() { return false; }
    }
    public record ErrorTurnResult(String message) implements TurnResult {
        @Override public boolean isSuccess() { return false; }
    }
    public record StopHookPreventedTurnResult() implements TurnResult {
        @Override public boolean isSuccess() { return false; }
    }

    /**
     * Stream event dispatched to the SDK consumer.
     * Mirrors the StreamEvent | Message | RequestStartEvent union in query.ts.
     */
    public sealed interface QueryEvent
            permits QueryEngine.StreamRequestStartEvent,
                    QueryEngine.StreamEventWrapper,
                    QueryEngine.MessageEvent,
                    QueryEngine.UserInterruptionEvent,
                    QueryEngine.ErrorEvent,
                    QueryEngine.TextDelta,
                    QueryEngine.ToolUseStartEvent {

        String getType();

        static QueryEvent streamRequestStart() { return new StreamRequestStartEvent(); }
        static QueryEvent streamEvent(StreamEvent e) { return new StreamEventWrapper(e); }
        static QueryEvent message(Message m) { return new MessageEvent(m); }
        static QueryEvent userInterruption() { return new UserInterruptionEvent(); }
        static QueryEvent error(Throwable t) { return new ErrorEvent(t); }
        static QueryEvent textDelta(String text) { return new TextDelta(text); }
    }

    public record StreamRequestStartEvent() implements QueryEvent {
        @Override public String getType() { return "stream_request_start"; }
    }
    public record StreamEventWrapper(StreamEvent event) implements QueryEvent {
        @Override public String getType() { return "stream_event"; }
    }
    public record MessageEvent(Message message) implements QueryEvent {
        @Override public String getType() { return "message"; }
    }
    public record UserInterruptionEvent() implements QueryEvent {
        @Override public String getType() { return "user_interruption"; }
    }
    public record ErrorEvent(Throwable error) implements QueryEvent {
        @Override public String getType() { return "error"; }
    }
    public record TextDelta(String text) implements QueryEvent {
        @Override public String getType() { return "text_delta"; }
    }
    public record ToolUseStartEvent(String toolName, String toolUseId) implements QueryEvent {
        @Override public String getType() { return "tool_use_start"; }
    }

    // =========================================================================
    // Error types
    // =========================================================================

    /** Thrown by the streaming API service when a model fallback is triggered. */
    public static class FallbackTriggeredError extends RuntimeException {
        private final String originalModel;
        private final String fallbackModel;

        public FallbackTriggeredError(String originalModel, String fallbackModel) {
            super("Fallback triggered from " + originalModel + " to " + fallbackModel);
            this.originalModel = originalModel;
            this.fallbackModel = fallbackModel;
        }

        public String getOriginalModel() { return originalModel; }
        public String getFallbackModel() { return fallbackModel; }
    }

    // =========================================================================
    // Placeholder types (resolved by other service layers)
    // =========================================================================

    /**
     * A single event emitted by the streaming Claude API.
     * Mirrors the StreamEvent union type from query.ts.
     */
    public sealed interface StreamEvent
            permits QueryEngine.StreamEvent.AssistantMessageEvent,
                    QueryEngine.StreamEvent.RawStreamEvent {

        /** An assistant message was fully assembled from the stream. */
        record AssistantMessageEvent(Message.AssistantMessage message) implements StreamEvent {}

        /** A raw streaming event (e.g. delta, ping) from the API. */
        record RawStreamEvent(String type, Object data) implements StreamEvent {}
    }

    /** Minimal streaming-tool-executor contract. */
    public interface StreamingToolExecutor {
        void addTool(ToolUseBlock toolBlock, Message.AssistantMessage message);
        List<Message> getCompletedResults();
        CompletableFuture<List<Message>> getAllResults();
        void discard();
    }

    /** Factory for StreamingToolExecutor — injected to keep QueryEngine testable. */
    public interface StreamingToolExecutorFactory {
        StreamingToolExecutor create(QueryEngineConfig config);
    }

    /** Accumulated API usage totals. */
    public record UsageSummary(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {
        public static UsageSummary empty() { return new UsageSummary(0, 0, 0, 0); }
    }
}
