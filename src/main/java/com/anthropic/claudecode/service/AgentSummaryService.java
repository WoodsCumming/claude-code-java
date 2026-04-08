package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Periodic background summarization for coordinator mode sub-agents.
 * Translated from src/services/AgentSummary/agentSummary.ts
 *
 * Forks the sub-agent's conversation every ~30s to generate a 1-2 sentence
 * progress summary. The summary is stored for UI display.
 *
 * Scheduling mirrors the TypeScript: the next timer is started only AFTER the
 * previous summary completes (finally block), so summaries never overlap.
 */
@Slf4j
@Service
public class AgentSummaryService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentSummaryService.class);

    private static final long SUMMARY_INTERVAL_MS = 30_000L;

    // -------------------------------------------------------------------------
    // SummarizationHandle — mirrors the { stop } return type in agentSummary.ts
    // -------------------------------------------------------------------------

    public interface SummarizationHandle {
        void stop();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start periodic background summarization for an agent.
     * Returns a handle whose stop() cancels the timer and aborts any in-flight summary.
     * Translated from startAgentSummarization() in agentSummary.ts
     */
    public SummarizationHandle startAgentSummarization(
            String taskId,
            String agentId,
            Consumer<String> onSummaryUpdate) {

        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> timeoutRef = new AtomicReference<>(null);
        AtomicReference<CompletableFuture<?>> inflight = new AtomicReference<>(null);
        AtomicReference<String> previousSummary = new AtomicReference<>(null);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-summary-" + agentId);
            t.setDaemon(true);
            return t;
        });

        // scheduleNext mirrors scheduleNext() in agentSummary.ts: starts next timer
        // only when not stopped.
        Runnable[] scheduleNextHolder = {null};

        Runnable runSummary = () -> {
            if (stopped.get()) return;
            log.debug("[AgentSummary] Timer fired for agent {}", agentId);

            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {
                    if (stopped.get()) return;

                    // In a full implementation this would call getAgentTranscript(agentId)
                    // and runForkedAgent() exactly as in the TypeScript source.
                    // Here we request a summary via the side-query abstraction.
                    String summary = generateSummaryText(agentId, previousSummary.get());

                    if (stopped.get()) return;

                    if (summary != null && !summary.isBlank()) {
                        log.debug("[AgentSummary] Summary result for {}: {}", taskId, summary);
                        previousSummary.set(summary);
                        onSummaryUpdate.accept(summary);
                    }
                } catch (Exception e) {
                    if (!stopped.get()) {
                        log.error("[AgentSummary] Error generating summary for {}: {}",
                                taskId, e.getMessage(), e);
                    }
                } finally {
                    inflight.set(null);
                    // Reset timer on completion (not initiation) — prevents overlapping summaries
                    if (!stopped.get() && scheduleNextHolder[0] != null) {
                        scheduleNextHolder[0].run();
                    }
                }
            });
            inflight.set(task);
        };

        // scheduleNext implementation
        scheduleNextHolder[0] = () -> {
            if (stopped.get()) return;
            ScheduledFuture<?> future = scheduler.schedule(
                    runSummary, SUMMARY_INTERVAL_MS, TimeUnit.MILLISECONDS);
            timeoutRef.set(future);
        };

        // Start the first timer
        scheduleNextHolder[0].run();

        return () -> {
            log.debug("[AgentSummary] Stopping summarization for {}", taskId);
            stopped.set(true);
            ScheduledFuture<?> t = timeoutRef.getAndSet(null);
            if (t != null) t.cancel(false);
            CompletableFuture<?> f = inflight.getAndSet(null);
            if (f != null) f.cancel(true);
            scheduler.shutdown();
        };
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Build the summary prompt.
     * Translated from buildSummaryPrompt() in agentSummary.ts
     */
    private String buildSummaryPrompt(String previousSummary) {
        String prevLine = previousSummary != null
                ? "\nPrevious: \"" + previousSummary + "\" — say something NEW.\n"
                : "";
        return "Describe your most recent action in 3-5 words using present tense (-ing). "
                + "Name the file or function, not the branch. Do not use tools.\n"
                + prevLine
                + "\nGood: \"Reading runAgent.ts\"\n"
                + "Good: \"Fixing null check in validate.ts\"\n"
                + "Good: \"Running auth module tests\"\n"
                + "Good: \"Adding retry logic to fetchUser\"\n"
                + "\nBad (past tense): \"Analyzed the branch diff\"\n"
                + "Bad (too vague): \"Investigating the issue\"\n"
                + "Bad (too long): \"Reviewing full branch diff and AgentTool.tsx integration\"";
    }

    /**
     * Generate a summary by sending the prompt to the model.
     * In the full implementation this delegates to runForkedAgent() with
     * canUseTool returning deny (no tools needed, cache key preserved).
     * The skipTranscript flag prevents the summary from polluting the agent's history.
     *
     * Translated from runSummary() → runForkedAgent() call in agentSummary.ts
     */
    private String generateSummaryText(String agentId, String previousSummary) {
        // Placeholder — real implementation would call ForkedAgentService.runForkedAgent()
        // with the agent's current transcript messages and the summary prompt.
        // Returning null here signals "not enough context yet" which causes the
        // finally block to simply reschedule without updating the UI.
        log.debug("[AgentSummary] Generating summary for agent {}", agentId);
        return null;
    }
}
