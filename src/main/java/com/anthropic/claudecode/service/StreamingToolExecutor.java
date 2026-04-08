package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.model.ToolUseContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Executes tools as they stream in with concurrency control.
 * Translated from src/services/tools/StreamingToolExecutor.ts
 *
 * Rules:
 * - Concurrent-safe tools can execute in parallel with other concurrent-safe tools.
 * - Non-concurrent tools must execute alone (exclusive access).
 * - Results are buffered and emitted in the order tools were received.
 */
@Slf4j
public class StreamingToolExecutor implements QueryEngine.StreamingToolExecutor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StreamingToolExecutor.class);


    // =========================================================================
    // Fields
    // =========================================================================

    private final ToolExecutionService toolExecutionService;

    private final List<TrackedTool> tools = new ArrayList<>();
    private ToolUseContext toolUseContext;

    private final AtomicBoolean hasErrored = new AtomicBoolean(false);
    private volatile String erroredToolDescription = "";

    /**
     * Set to true when a streaming fallback occurs so pending tools are discarded.
     * Translated from this.discarded in StreamingToolExecutor.ts
     */
    private final AtomicBoolean discarded = new AtomicBoolean(false);

    /**
     * Resolves when any progress message becomes available, allowing
     * getRemainingResults to wake up and yield it immediately.
     * Translated from progressAvailableResolve in StreamingToolExecutor.ts
     */
    private volatile CompletableFuture<Void> progressAvailableFuture = new CompletableFuture<>();

    // =========================================================================
    // Constructor
    // =========================================================================

    public StreamingToolExecutor(
            ToolUseContext toolUseContext,
            ToolExecutionService toolExecutionService) {
        this.toolUseContext = toolUseContext;
        this.toolExecutionService = toolExecutionService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Discards all pending and in-progress tools. Called when streaming fallback
     * occurs and results from the failed attempt should be abandoned.
     * Translated from discard() in StreamingToolExecutor.ts
     */
    public void discard() {
        discarded.set(true);
    }

    /**
     * Add a tool to the execution queue. Will start executing immediately if
     * concurrency conditions allow.
     * Translated from addTool() in StreamingToolExecutor.ts
     */
    public synchronized void addTool(
            ContentBlock.ToolUseBlock block,
            Message.AssistantMessage assistantMessage) {

        boolean isConcurrencySafe = toolExecutionService.isConcurrencySafe(
                block.getName(), block.getInput(), toolUseContext);

        TrackedTool trackedTool = new TrackedTool();
        trackedTool.setId(block.getId());
        trackedTool.setBlock(block);
        trackedTool.setAssistantMessage(assistantMessage);
        trackedTool.setStatus(ToolStatus.QUEUED);
        trackedTool.setConcurrencySafe(isConcurrencySafe);
        trackedTool.setPendingProgress(new ArrayList<>());

        tools.add(trackedTool);
        processQueue();
    }

    /**
     * Get any completed results that haven't been yielded yet (non-blocking).
     * Also yields any pending progress messages immediately.
     * Maintains ordering constraints.
     * Translated from getCompletedResults() in StreamingToolExecutor.ts
     *
     * @return iterable of MessageUpdates ready to emit
     */
    public synchronized List<MessageUpdate> getCompletedResultsWithContext() {
        if (discarded.get()) return List.of();

        List<MessageUpdate> results = new ArrayList<>();

        for (TrackedTool tool : tools) {
            // Always drain pending progress messages immediately
            while (!tool.getPendingProgress().isEmpty()) {
                Message progressMsg = tool.getPendingProgress().remove(0);
                results.add(new MessageUpdate(progressMsg, toolUseContext));
            }

            if (tool.getStatus() == ToolStatus.YIELDED) {
                continue;
            }

            if (tool.getStatus() == ToolStatus.COMPLETED && tool.getResults() != null) {
                tool.setStatus(ToolStatus.YIELDED);
                for (Message msg : tool.getResults()) {
                    results.add(new MessageUpdate(msg, toolUseContext));
                }
                markToolUseAsComplete(tool.getId());
            } else if (tool.getStatus() == ToolStatus.EXECUTING && !tool.isConcurrencySafe()) {
                // Non-concurrent tool is still executing — stop here to preserve ordering.
                break;
            }
        }
        return results;
    }

    /**
     * Wait for remaining tools and collect their results as they complete.
     * Also includes progress messages as they become available.
     * Translated from getRemainingResults() in StreamingToolExecutor.ts
     *
     * @return CompletableFuture that resolves to the ordered list of all remaining MessageUpdates
     */
    public CompletableFuture<List<MessageUpdate>> getRemainingResults() {
        if (discarded.get()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return CompletableFuture.supplyAsync(() -> {
            List<MessageUpdate> allResults = new ArrayList<>();

            while (hasUnfinishedTools()) {
                // Drain completed results
                allResults.addAll(getCompletedResultsWithContext());

                if (!hasExecutingTools() && !hasCompletedResults() && !hasPendingProgress()) {
                    break;
                }

                if (hasExecutingTools() && !hasCompletedResults() && !hasPendingProgress()) {
                    // Wait for a tool to complete or progress to arrive
                    List<CompletableFuture<Void>> executingPromises = getExecutingPromises();
                    CompletableFuture<Void> progressFuture = getCurrentProgressFuture();

                    if (!executingPromises.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        CompletableFuture<Void>[] allFutures = Stream.concat(
                                executingPromises.stream(), java.util.stream.Stream.of(progressFuture))
                                .toArray(CompletableFuture[]::new);
                        CompletableFuture<Void> anyOf = CompletableFuture.anyOf(allFutures)
                                .thenAccept(__ -> {});
                        try {
                            anyOf.get(30, TimeUnit.SECONDS);
                        } catch (TimeoutException | InterruptedException | ExecutionException e) {
                            log.debug("Wait interrupted while collecting tool results: {}", e.getMessage());
                            break;
                        }
                    }
                }
            }

            // Final drain
            allResults.addAll(getCompletedResultsWithContext());
            return allResults;
        });
    }

    /**
     * Get the current tool use context (may have been modified by context modifiers).
     * Translated from getUpdatedContext() in StreamingToolExecutor.ts
     */
    public ToolUseContext getUpdatedContext() {
        return toolUseContext;
    }

    // =========================================================================
    // Internal — queue processing
    // =========================================================================

    /**
     * Process the queue, starting tools when concurrency conditions allow.
     * Translated from processQueue() in StreamingToolExecutor.ts
     */
    private synchronized void processQueue() {
        for (TrackedTool tool : tools) {
            if (tool.getStatus() != ToolStatus.QUEUED) continue;

            if (canExecuteTool(tool.isConcurrencySafe())) {
                executeTool(tool);
            } else {
                // Can't execute yet; if this is non-concurrent stop to preserve ordering.
                if (!tool.isConcurrencySafe()) break;
            }
        }
    }

    /**
     * Check if a tool can execute given current concurrency state.
     * Translated from canExecuteTool() in StreamingToolExecutor.ts
     */
    private boolean canExecuteTool(boolean isConcurrencySafe) {
        long executingCount = tools.stream()
                .filter(t -> t.getStatus() == ToolStatus.EXECUTING)
                .count();
        if (executingCount == 0) return true;
        return isConcurrencySafe
                && tools.stream()
                .filter(t -> t.getStatus() == ToolStatus.EXECUTING)
                .allMatch(TrackedTool::isConcurrencySafe);
    }

    /**
     * Execute a tracked tool and collect its results.
     * Translated from executeTool() in StreamingToolExecutor.ts
     */
    private void executeTool(TrackedTool tool) {
        tool.setStatus(ToolStatus.EXECUTING);

        // Check if already aborted before starting
        AbortReason initialAbort = getAbortReason(tool);
        if (initialAbort != null) {
            List<Message> msgs = new ArrayList<>();
            msgs.add(createSyntheticErrorMessage(tool.getId(), initialAbort, tool.getAssistantMessage()));
            tool.setResults(msgs);
            tool.setStatus(ToolStatus.COMPLETED);
            processQueue();
            return;
        }

        CompletableFuture<Void> promise = toolExecutionService
                .runToolUse(tool.getBlock(), tool.getAssistantMessage(), toolUseContext)
                .thenAccept(updates -> {
                    List<Message> messages = new ArrayList<>();
                    List<Function<ToolUseContext, ToolUseContext>> contextModifiers = new ArrayList<>();
                    boolean thisToolErrored = false;

                    for (ToolExecutionService.MessageUpdateLazy update : updates) {
                        AbortReason abortReason = getAbortReason(tool);
                        if (abortReason != null && !thisToolErrored) {
                            messages.add(createSyntheticErrorMessage(
                                    tool.getId(), abortReason, tool.getAssistantMessage()));
                            break;
                        }

                        if (update.getMessage() != null) {
                            Message msg = update.getMessage();
                            boolean isErrorResult = isToolErrorResult(msg);

                            if (isErrorResult) {
                                thisToolErrored = true;
                                // Only Bash errors cancel siblings
                                if ("Bash".equals(tool.getBlock().getName())) {
                                    hasErrored.set(true);
                                    erroredToolDescription = getToolDescription(tool);
                                }
                            }

                            if (msg.getType() != null && "progress".equals(msg.getType())) {
                                // Progress messages go to pendingProgress for immediate yielding
                                synchronized (this) {
                                    tool.getPendingProgress().add(msg);
                                }
                                // Signal that progress is available
                                signalProgressAvailable();
                            } else {
                                messages.add(msg);
                            }
                        }

                        if (update.getContextModifier() != null) {
                            contextModifiers.add(update.getContextModifier());
                        }
                    }

                    synchronized (this) {
                        tool.setResults(messages);
                        tool.setContextModifiers(contextModifiers);
                        tool.setStatus(ToolStatus.COMPLETED);

                        // Apply context modifiers for non-concurrent tools
                        if (!tool.isConcurrencySafe() && !contextModifiers.isEmpty()) {
                            for (Function<ToolUseContext, ToolUseContext> modifier : contextModifiers) {
                                toolUseContext = modifier.apply(toolUseContext);
                            }
                        }

                        processQueue();
                    }
                })
                .exceptionally(ex -> {
                    log.error("Tool execution failed for {}: {}", tool.getBlock().getName(), ex.getMessage(), ex);
                    synchronized (this) {
                        Message errMsg = createSyntheticErrorMessage(
                                tool.getId(), AbortReason.SIBLING_ERROR, tool.getAssistantMessage());
                        tool.setResults(List.of(errMsg));
                        tool.setStatus(ToolStatus.COMPLETED);
                        processQueue();
                    }
                    return null;
                });

        tool.setPromise(promise);
    }

    // =========================================================================
    // Internal — abort / error helpers
    // =========================================================================

    /**
     * Determine why a tool should be cancelled.
     * Translated from getAbortReason() in StreamingToolExecutor.ts
     */
    private AbortReason getAbortReason(TrackedTool tool) {
        if (discarded.get()) return AbortReason.STREAMING_FALLBACK;
        if (hasErrored.get()) return AbortReason.SIBLING_ERROR;
        if (toolUseContext.isAborted()) return AbortReason.USER_INTERRUPTED;
        return null;
    }

    /**
     * Create a synthetic error message for a cancelled/aborted tool.
     * Translated from createSyntheticErrorMessage() in StreamingToolExecutor.ts
     */
    private Message createSyntheticErrorMessage(
            String toolUseId,
            AbortReason reason,
            Message.AssistantMessage assistantMessage) {

        String content;
        String toolUseResult;

        switch (reason) {
            case USER_INTERRUPTED -> {
                content = "[Rejected] Tool use was rejected";
                toolUseResult = "User rejected tool use";
            }
            case STREAMING_FALLBACK -> {
                content = "<tool_use_error>Error: Streaming fallback - tool execution discarded</tool_use_error>";
                toolUseResult = "Streaming fallback - tool execution discarded";
            }
            default -> {
                String desc = erroredToolDescription;
                String msg = (desc != null && !desc.isEmpty())
                        ? "Cancelled: parallel tool call " + desc + " errored"
                        : "Cancelled: parallel tool call errored";
                content = "<tool_use_error>" + msg + "</tool_use_error>";
                toolUseResult = msg;
            }
        }

        ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock();
        resultBlock.setType("tool_result");
        resultBlock.setToolUseId(toolUseId);
        resultBlock.setContent(content);
        resultBlock.setIsError(true);

        return Message.UserMessage.builder()
                .type("user")
                .uuid(UUID.randomUUID().toString())
                .content(List.of(resultBlock))
                .toolUseResult(toolUseResult)
                .sourceToolAssistantUUID(assistantMessage.getUuid())
                .build();
    }

    /**
     * Build a human-readable description of a tool for error messages.
     * Translated from getToolDescription() in StreamingToolExecutor.ts
     */
    private String getToolDescription(TrackedTool tool) {
        Map<String, Object> input = tool.getBlock().getInput();
        if (input == null) return tool.getBlock().getName();

        Object summary = input.getOrDefault("command",
                input.getOrDefault("file_path",
                        input.get("pattern")));

        if (summary instanceof String str && !str.isEmpty()) {
            String truncated = str.length() > 40 ? str.substring(0, 40) + "\u2026" : str;
            return tool.getBlock().getName() + "(" + truncated + ")";
        }
        return tool.getBlock().getName();
    }

    private boolean isToolErrorResult(Message msg) {
        if (msg == null || !"user".equals(msg.getType())) return false;
        if (!(msg instanceof Message.UserMessage userMsg)) return false;
        if (userMsg.getContent() == null) return false;
        return userMsg.getContent().stream().anyMatch(block ->
                block instanceof ContentBlock.ToolResultBlock r && Boolean.TRUE.equals(r.getIsError()));
    }

    // =========================================================================
    // Internal — state queries
    // =========================================================================

    private synchronized boolean hasUnfinishedTools() {
        return tools.stream().anyMatch(t -> t.getStatus() != ToolStatus.YIELDED);
    }

    private synchronized boolean hasExecutingTools() {
        return tools.stream().anyMatch(t -> t.getStatus() == ToolStatus.EXECUTING);
    }

    private synchronized boolean hasCompletedResults() {
        return tools.stream().anyMatch(t -> t.getStatus() == ToolStatus.COMPLETED);
    }

    private synchronized boolean hasPendingProgress() {
        return tools.stream().anyMatch(t -> !t.getPendingProgress().isEmpty());
    }

    private synchronized List<CompletableFuture<Void>> getExecutingPromises() {
        List<CompletableFuture<Void>> promises = new ArrayList<>();
        for (TrackedTool t : tools) {
            if (t.getStatus() == ToolStatus.EXECUTING && t.getPromise() != null) {
                promises.add(t.getPromise());
            }
        }
        return promises;
    }

    private synchronized CompletableFuture<Void> getCurrentProgressFuture() {
        return progressAvailableFuture;
    }

    private void signalProgressAvailable() {
        CompletableFuture<Void> future = progressAvailableFuture;
        progressAvailableFuture = new CompletableFuture<>();
        future.complete(null);
    }

    private void markToolUseAsComplete(String toolUseId) {
        toolUseContext.removeInProgressToolUseId(toolUseId);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /** Tool execution lifecycle states. */
    public enum ToolStatus {
        QUEUED, EXECUTING, COMPLETED, YIELDED
    }

    /** Reasons a tool execution was cancelled before or during execution. */
    public enum AbortReason {
        SIBLING_ERROR,
        USER_INTERRUPTED,
        STREAMING_FALLBACK
    }

    /** A tool being tracked through the execution pipeline. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TrackedTool {
        private String id;
        private ContentBlock.ToolUseBlock block;
        private Message.AssistantMessage assistantMessage;
        private volatile ToolStatus status;
        private boolean concurrencySafe;
        private volatile CompletableFuture<Void> promise;
        private volatile List<Message> results;
        private List<Message> pendingProgress;
        private List<Function<ToolUseContext, ToolUseContext>> contextModifiers;

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public ToolStatus getStatus() { return status; }
        public void setStatus(ToolStatus v) { status = v; }
        public boolean isConcurrencySafe() { return concurrencySafe; }
        public void setConcurrencySafe(boolean v) { concurrencySafe = v; }
        public CompletableFuture<Void> getPromise() { return promise; }
        public void setPromise(CompletableFuture<Void> v) { promise = v; }
        public List<Message> getResults() { return results; }
        public void setResults(List<Message> v) { results = v; }
        public List<Message> getPendingProgress() { return pendingProgress; }
        public void setPendingProgress(List<Message> v) { pendingProgress = v; }
        public List<Function<ToolUseContext, ToolUseContext>> getContextModifiers() { return contextModifiers; }
        public void setContextModifiers(List<Function<ToolUseContext, ToolUseContext>> v) { contextModifiers = v; }
    }

    /**
     * A message plus its associated context snapshot, emitted by getCompletedResults
     * and getRemainingResults.
     * Translated from MessageUpdate in StreamingToolExecutor.ts
     */
    public record MessageUpdate(Message message, ToolUseContext newContext) {}

    // Helper import alias for java.util.stream.Stream inside lambdas
    private static <T> java.util.stream.Stream<T> Stream(java.util.Collection<T> c) {
        return c.stream();
    }

    // =========================================================================
    // QueryEngine.StreamingToolExecutor interface adapter methods
    // =========================================================================

    @Override
    public List<Message> getCompletedResults() {
        return getCompletedResultsWithContext().stream()
                .map(MessageUpdate::message)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public CompletableFuture<List<Message>> getAllResults() {
        return getRemainingResults().thenApply(updates ->
                updates.stream()
                        .map(MessageUpdate::message)
                        .collect(java.util.stream.Collectors.toList()));
    }
}
