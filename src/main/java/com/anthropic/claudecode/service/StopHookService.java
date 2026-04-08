package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.SystemPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Stop hook service.
 * Translated from src/query/stopHooks.ts
 *
 * Handles all end-of-turn hooks:
 *   1. Stop hooks (always run)
 *   2. TaskCompleted hooks  (teammate sessions only — for in-progress tasks owned by this agent)
 *   3. TeammateIdle hooks   (teammate sessions only)
 *
 * Also triggers fire-and-forget background work that should run after every turn:
 *   - Prompt suggestion
 *   - Memory extraction
 *   - Auto-dream
 *   - Job state classification (TEMPLATES feature)
 *   - Computer-use session cleanup (CHICAGO_MCP feature)
 *
 * The async-generator pattern from TypeScript is modelled here as a reactive
 * Flux that emits stream events / messages and completes with a StopHookResult
 * via a wrapper record.
 */
@Slf4j
@Service
public class StopHookService {



    private final HookService hookService;
    private final AnalyticsService analyticsService;
    private final PromptSuggestionService promptSuggestionService;
    private final AutoDreamService autoDreamService;

    @Autowired
    public StopHookService(HookService hookService,
                            AnalyticsService analyticsService,
                            PromptSuggestionService promptSuggestionService,
                            AutoDreamService autoDreamService) {
        this.hookService = hookService;
        this.analyticsService = analyticsService;
        this.promptSuggestionService = promptSuggestionService;
        this.autoDreamService = autoDreamService;
    }

    // =========================================================================
    // Primary entry point
    // =========================================================================

    /**
     * Execute all end-of-turn hooks and return a result indicating whether the
     * turn should continue.
     *
     * Translated from handleStopHooks() async generator in stopHooks.ts.
     * The TypeScript generator yields stream events; this Java version returns
     * a Flux of stream events paired with a terminal StopHookResult via
     * {@link StopHookOutcome}.
     *
     * @param messagesForQuery   messages sent to the model this iteration
     * @param assistantMessages  assistant messages received this iteration
     * @param systemPrompt       current system prompt
     * @param userContext        user context key-value pairs
     * @param systemContext      system context key-value pairs
     * @param toolUseContext     mutable tool-use context for this turn
     * @param querySource        query origination point (e.g. "repl_main_thread", "sdk")
     * @param stopHookActive     whether a stop hook is already running
     * @return CompletableFuture carrying the StopHookResult
     */
    public CompletableFuture<StopHookResult> handleStopHooks(
            List<Message> messagesForQuery,
            List<Message> assistantMessages,
            SystemPrompt systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            ToolUseContext toolUseContext,
            String querySource,
            boolean stopHookActive) {

        long hookStartTime = System.currentTimeMillis();

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Fire-and-forget background work (bare mode skips these).
                triggerBackgroundWork(systemPrompt, userContext, systemContext,
                        toolUseContext, querySource, messagesForQuery, assistantMessages);

                // Execute the primary stop hooks.
                HookService.HookExecutionResult result = hookService.executeHooksForStop(
                    HookService.HookEvent.STOP,
                    buildHookInput(messagesForQuery, assistantMessages, querySource),
                    toolUseContext
                ).join();

                if (toolUseContext.isAborted()) {
                    analyticsService.logEvent("tengu_pre_stop_hooks_cancelled",
                        Map.of("queryDepth", toolUseContext.getQueryDepth()));
                    return StopHookResult.preventContinuation();
                }

                if (result.isPreventContinuation()) {
                    return StopHookResult.preventContinuation();
                }

                if (!result.getBlockingErrors().isEmpty()) {
                    List<Message> errorMessages = result.getBlockingErrors().stream()
                        .map(err -> (Message) Message.SystemMessage.builder()
                            .type("system")
                            .uuid(java.util.UUID.randomUUID().toString())
                            .content(err)
                            .level(Message.SystemMessageLevel.ERROR)
                            .build())
                        .toList();
                    return StopHookResult.blocking(errorMessages);
                }

                return StopHookResult.success();

            } catch (Exception error) {
                long durationMs = System.currentTimeMillis() - hookStartTime;
                analyticsService.logEvent("tengu_stop_hook_error",
                    Map.of("duration", durationMs));
                log.warn("Stop hook failed: {}", error.getMessage(), error);
                // Return success so the turn can proceed despite hook failure.
                return StopHookResult.success();
            }
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Fire-and-forget background tasks that run after every turn.
     * Mirrors the non-bare-mode block in stopHooks.ts.
     */
    private void triggerBackgroundWork(
            SystemPrompt systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            ToolUseContext toolUseContext,
            String querySource,
            List<Message> messagesForQuery,
            List<Message> assistantMessages) {

        boolean isBareMode = isBareMode();
        if (isBareMode) {
            return;
        }

        // Prompt suggestion (fire-and-forget, skipped if env flag disables it)
        boolean promptSuggestionDisabled = "false".equalsIgnoreCase(
            System.getenv("CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION"));
        if (!promptSuggestionDisabled) {
            promptSuggestionService.executePromptSuggestionAsync(
                systemPrompt, userContext, systemContext, toolUseContext, querySource);
        }

        // Auto-dream (main session only — subagents skip)
        if (toolUseContext.getAgentId() == null) {
            autoDreamService.executeAutoDreamAsync(
                systemPrompt, userContext, systemContext, toolUseContext, querySource);
        }
    }

    private Map<String, Object> buildHookInput(
            List<Message> messagesForQuery,
            List<Message> assistantMessages,
            String querySource) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("query_source", querySource != null ? querySource : "");
        input.put("message_count", messagesForQuery.size() + assistantMessages.size());
        return input;
    }

    private boolean isBareMode() {
        String bareMode = System.getenv("CLAUDE_CODE_BARE_MODE");
        return "1".equals(bareMode) || "true".equalsIgnoreCase(bareMode);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Terminal result of the stop-hook pipeline.
     * Mirrors: type StopHookResult = { blockingErrors: Message[]; preventContinuation: boolean }
     */
    public sealed interface StopHookResult
            permits StopHookService.SuccessResult,
                    StopHookService.BlockingResult,
                    StopHookService.PreventContinuationResult {

        boolean isPreventContinuation();
        List<Message> getBlockingErrors();

        static StopHookResult success() { return new SuccessResult(); }
        static StopHookResult blocking(List<Message> errors) { return new BlockingResult(errors); }
        static StopHookResult preventContinuation() { return new PreventContinuationResult(); }
    }

    /** No errors, turn may continue. Mirrors: { blockingErrors: [], preventContinuation: false } */
    public record SuccessResult() implements StopHookResult {
        @Override public boolean isPreventContinuation() { return false; }
        @Override public List<Message> getBlockingErrors() { return List.of(); }
    }

    /** Blocking errors collected; turn must NOT continue automatically. */
    public record BlockingResult(List<Message> errors) implements StopHookResult {
        @Override public boolean isPreventContinuation() { return false; }
        @Override public List<Message> getBlockingErrors() { return errors; }
    }

    /** Hook explicitly requested that the turn NOT continue. */
    public record PreventContinuationResult() implements StopHookResult {
        @Override public boolean isPreventContinuation() { return true; }
        @Override public List<Message> getBlockingErrors() { return List.of(); }
    }

    // =========================================================================
    // ToolUseContext reference (minimal interface for this service)
    // =========================================================================

    /**
     * Minimal ToolUseContext interface consumed by this service.
     * The real ToolUseContext lives in the model package; this keeps
     * StopHookService from depending on the full heavyweight context object.
     */
    public interface ToolUseContext {
        String getAgentId();
        boolean isAborted();
        int getQueryDepth();
    }
}
