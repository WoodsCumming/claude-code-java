package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Query token budget service.
 * Translated from src/query/tokenBudget.ts
 *
 * Manages token budget tracking for multi-turn auto-continuation.
 * When a large token budget is set (e.g. 500k), Claude may have more to say
 * than fits in a single response. This service tracks how much has been used
 * across continuations and decides whether to keep going or stop.
 */
@Slf4j
@Service
public class QueryTokenBudgetService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryTokenBudgetService.class);


    /** Token usage threshold at which the turn is considered "complete enough" to stop. */
    private static final double COMPLETION_THRESHOLD = 0.9;

    /**
     * Minimum token delta between continuations. If the model produces fewer
     * tokens than this, it's considered diminishing returns and we stop early.
     */
    private static final int DIMINISHING_THRESHOLD = 500;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Create a fresh budget tracker for a new query turn.
     * Translated from createBudgetTracker() in tokenBudget.ts
     */
    public BudgetTracker createBudgetTracker() {
        return new BudgetTracker(0, 0, 0, System.currentTimeMillis());
    }

    /**
     * Decide whether to continue or stop based on the current token usage.
     * Mutates the tracker's state when the decision is "continue".
     * Translated from checkTokenBudget() in tokenBudget.ts
     *
     * @param tracker            mutable budget tracker for the current turn
     * @param agentId            if non-null, budget logic is skipped (subagents don't use it)
     * @param budget             total budget in tokens, or null/≤0 to disable
     * @param globalTurnTokens   cumulative tokens consumed in this turn so far
     * @return TokenBudgetDecision — either a ContinueDecision or a StopDecision
     */
    public TokenBudgetDecision checkTokenBudget(
            BudgetTracker tracker,
            String agentId,
            Integer budget,
            int globalTurnTokens) {

        // Subagents bypass the budget check; null/zero budget is equivalent to disabled.
        if (agentId != null || budget == null || budget <= 0) {
            return new StopDecision(null);
        }

        final int turnTokens = globalTurnTokens;
        final int pct = (int) Math.round(((double) turnTokens / budget) * 100);
        final int deltaSinceLastCheck = globalTurnTokens - tracker.getLastGlobalTurnTokens();

        // Diminishing returns: after ≥3 continuations, if this iteration and the
        // previous one both produced < DIMINISHING_THRESHOLD tokens, stop.
        final boolean isDiminishing =
            tracker.getContinuationCount() >= 3
                && deltaSinceLastCheck < DIMINISHING_THRESHOLD
                && tracker.getLastDeltaTokens() < DIMINISHING_THRESHOLD;

        if (!isDiminishing && turnTokens < budget * COMPLETION_THRESHOLD) {
            // Still within budget — keep going. Mutate tracker.
            tracker.setContinuationCount(tracker.getContinuationCount() + 1);
            tracker.setLastDeltaTokens(deltaSinceLastCheck);
            tracker.setLastGlobalTurnTokens(globalTurnTokens);

            // Nudge message to inject into next continuation (mirrors getBudgetContinuationMessage)
            String nudgeMessage = buildNudgeMessage(pct, turnTokens, budget);
            return new ContinueDecision(nudgeMessage, tracker.getContinuationCount(), pct, turnTokens, budget);
        }

        // Stop: either budget exhausted or diminishing returns.
        if (isDiminishing || tracker.getContinuationCount() > 0) {
            CompletionEvent completionEvent = new CompletionEvent(
                tracker.getContinuationCount(),
                pct,
                turnTokens,
                budget,
                isDiminishing,
                System.currentTimeMillis() - tracker.getStartedAt()
            );
            return new StopDecision(completionEvent);
        }

        // Budget not yet reached but no continuations have occurred yet → plain stop.
        return new StopDecision(null);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String buildNudgeMessage(int pct, int turnTokens, int budget) {
        return String.format(
            "You have used %d%% of your token budget (%d/%d tokens). " +
            "Please continue your task, but be mindful of remaining budget.",
            pct, turnTokens, budget
        );
    }

    // =========================================================================
    // Inner types — sealed interface mirrors the TS union ContinueDecision | StopDecision
    // =========================================================================

    /**
     * Sealed union of budget decisions.
     * Mirrors: export type TokenBudgetDecision = ContinueDecision | StopDecision
     */
    public sealed interface TokenBudgetDecision
            permits QueryTokenBudgetService.ContinueDecision,
                    QueryTokenBudgetService.StopDecision {
        String getAction();
    }

    /**
     * Mirrors: type ContinueDecision = { action: 'continue'; nudgeMessage: string; ... }
     */
    public record ContinueDecision(
            String nudgeMessage,
            int continuationCount,
            int pct,
            int turnTokens,
            int budget
    ) implements TokenBudgetDecision {
        @Override public String getAction() { return "continue"; }
    }

    /**
     * Mirrors: type StopDecision = { action: 'stop'; completionEvent: ... | null }
     */
    public record StopDecision(CompletionEvent completionEvent) implements TokenBudgetDecision {
        @Override public String getAction() { return "stop"; }
    }

    /**
     * Analytics payload emitted when a budget-driven turn completes.
     * Mirrors the completionEvent shape inside StopDecision.
     */
    public record CompletionEvent(
            int continuationCount,
            int pct,
            int turnTokens,
            int budget,
            boolean diminishingReturns,
            long durationMs
    ) {}

    /**
     * Mutable budget tracker carried across continuations within a single turn.
     * Mirrors: type BudgetTracker = { continuationCount, lastDeltaTokens, ... }
     */
    public static class BudgetTracker {
        private int continuationCount;
        private int lastDeltaTokens;
        private int lastGlobalTurnTokens;
        private int budget;
        private long startedAt;

        public BudgetTracker() {}
        public BudgetTracker(int continuationCount, int lastDeltaTokens, int budget, long startedAt) {
            this.continuationCount = continuationCount; this.lastDeltaTokens = lastDeltaTokens;
            this.budget = budget; this.startedAt = startedAt;
        }
        public int getContinuationCount() { return continuationCount; }
        public void setContinuationCount(int v) { continuationCount = v; }
        public int getLastDeltaTokens() { return lastDeltaTokens; }
        public void setLastDeltaTokens(int v) { lastDeltaTokens = v; }
        public int getLastGlobalTurnTokens() { return lastGlobalTurnTokens; }
        public void setLastGlobalTurnTokens(int v) { lastGlobalTurnTokens = v; }
        public int getBudget() { return budget; }
        public void setBudget(int v) { budget = v; }
        public long getStartedAt() { return startedAt; }
        public void setStartedAt(long v) { startedAt = v; }
    }
}
