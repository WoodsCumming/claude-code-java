package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Queue processor service.
 *
 * Translated from:
 *   - src/utils/queueProcessor.ts        — core dequeue + batch logic
 *   - src/hooks/useQueueProcessor.ts     — React hook that drives the processor
 *
 * <h3>TypeScript → Java mapping for useQueueProcessor</h3>
 * <pre>
 * useQueueProcessor({ executeQueuedInput, hasActiveLocalJsxUI, queryGuard })
 *     → processIfReady(executeQueuedInput, hasActiveLocalJsxUI, isQueryActive)
 *
 * useSyncExternalStore(queryGuard.subscribe, queryGuard.getSnapshot)
 *     → isQueryActive boolean parameter (caller polls QueryGuard)
 *
 * useSyncExternalStore(subscribeToCommandQueue, getCommandQueueSnapshot)
 *     → queueSnapshot parameter / hasQueuedCommands()
 *
 * useEffect → processIfReady() is called by the REPL event loop when
 *             the relevant conditions change (not auto-triggered here)
 * </pre>
 *
 * The TypeScript hook uses two {@code useSyncExternalStore} subscriptions and a
 * {@code useEffect} to re-run processing whenever the queue or query-active state
 * changes.  In Java the REPL event loop must call
 * {@link #processIfReady(java.util.function.Function, boolean, boolean)} explicitly
 * whenever conditions change — matching the same guard logic.
 *
 * Processing rules (mirrors queueProcessor.ts exactly):
 *
 *   1. Slash commands (value starts with '/') are processed one at a time.
 *   2. Bash-mode commands are processed one at a time to preserve per-command
 *      error isolation, exit codes, and progress UI.
 *   3. All other non-slash commands with the SAME mode as the highest-priority
 *      item are drained at once and passed as a single array to executeInput.
 *      Different modes (e.g. "prompt" vs "task-notification") are never mixed.
 *
 * This processor runs on the REPL main thread between turns. It only looks at
 * commands addressed to the main thread (agentId == null). Subagent notifications
 * are not dequeued here so they never accidentally stall the queue.
 *
 * The caller is responsible for:
 *   - ensuring no query is currently running before calling processQueueIfReady()
 *   - calling again after each execution completes until the queue is empty
 */
@Slf4j
@Service
public class QueueProcessorService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueueProcessorService.class);


    private final MessageQueueService messageQueueService;

    @Autowired
    public QueueProcessorService(MessageQueueService messageQueueService) {
        this.messageQueueService = messageQueueService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Process the next batch of commands from the queue if any are ready.
     *
     * Translated from processQueueIfReady() in queueProcessor.ts
     *
     * @param executeInput callback that receives the batch and returns a future
     * @return ProcessQueueResult indicating whether anything was dequeued
     */
    public ProcessQueueResult processQueueIfReady(
            Function<List<MessageQueueService.QueuedCommand>, CompletableFuture<Void>> executeInput) {

        // Only look at main-thread commands (agentId == null).
        MessageQueueService.QueuedCommand next = messageQueueService.peek(this::isMainThread).orElse(null);
        if (next == null) {
            return new ProcessQueueResult(false);
        }

        // Slash commands and bash-mode commands are processed individually.
        if (isSlashCommand(next) || "bash".equals(next.getMode())) {
            MessageQueueService.QueuedCommand cmd =
                messageQueueService.dequeue(this::isMainThread).orElse(null);
            if (cmd != null) {
                executeInput.apply(List.of(cmd));
            }
            return new ProcessQueueResult(true);
        }

        // Drain all non-slash-command items with the same mode.
        String targetMode = next.getMode();
        List<MessageQueueService.QueuedCommand> batch = messageQueueService.dequeueAllMatching(
            cmd -> isMainThread(cmd)
                && !isSlashCommand(cmd)
                && targetMode.equals(cmd.getMode())
        );

        if (batch.isEmpty()) {
            return new ProcessQueueResult(false);
        }

        executeInput.apply(batch);
        return new ProcessQueueResult(true);
    }

    /**
     * useQueueProcessor-equivalent guard method.
     *
     * Mirrors the {@code useEffect} inside {@code useQueueProcessor}:
     * <pre>
     * if (isQueryActive) return
     * if (hasActiveLocalJsxUI) return
     * if (queueSnapshot.length === 0) return
     * processQueueIfReady({ executeInput: executeQueuedInput })
     * </pre>
     *
     * Call this from the REPL event loop whenever:
     * <ul>
     *   <li>a query finishes (isQueryActive transitions from true to false)</li>
     *   <li>a new command is enqueued (queueSnapshot changes)</li>
     *   <li>a blocking local JSX UI closes (hasActiveLocalJsxUI transitions to false)</li>
     * </ul>
     *
     * @param executeQueuedInput callback that receives the batch to execute
     * @param hasActiveLocalJsxUI true while a blocking local UI is visible
     * @param isQueryActive       true while an API query is in progress
     * @return {@link ProcessQueueResult} indicating whether commands were dequeued
     */
    public ProcessQueueResult processIfReady(
            Function<List<MessageQueueService.QueuedCommand>, CompletableFuture<Void>> executeQueuedInput,
            boolean hasActiveLocalJsxUI,
            boolean isQueryActive) {

        if (isQueryActive)        return new ProcessQueueResult(false);
        if (hasActiveLocalJsxUI)  return new ProcessQueueResult(false);
        if (!hasQueuedCommands()) return new ProcessQueueResult(false);

        return processQueueIfReady(executeQueuedInput);
    }

    /**
     * Check if the queue has any pending main-thread commands.
     * Translated from hasQueuedCommands() in queueProcessor.ts
     */
    public boolean hasQueuedCommands() {
        return messageQueueService.hasCommandsInQueue();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * A command is on the main thread when it has no agentId.
     * Mirrors the isMainThread lambda in queueProcessor.ts.
     */
    private boolean isMainThread(MessageQueueService.QueuedCommand cmd) {
        return cmd.getAgentId() == null;
    }

    /**
     * A queued command is a slash command when its value starts with '/'.
     * Translated from isSlashCommand() in queueProcessor.ts
     *
     * For string values: trim + check prefix.
     * For content-block arrays: inspect the first text block.
     */
    private boolean isSlashCommand(MessageQueueService.QueuedCommand cmd) {
        if (cmd == null) return false;
        Object value = cmd.getValue();

        if (value instanceof String s) {
            return s.trim().startsWith("/");
        }

        if (value instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> cb
                        && "text".equals(cb.get("type"))) {
                    Object text = cb.get("text");
                    return text instanceof String t && t.trim().startsWith("/");
                }
            }
        }

        return false;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Result of a processQueueIfReady() call.
     * Mirrors: type ProcessQueueResult = { processed: boolean }
     */
    public record ProcessQueueResult(boolean processed) {}
}
