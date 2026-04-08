package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * /cost slash-command service.
 * Translated from src/commands/cost/cost.ts
 *
 * Returns a human-readable string describing the current session's API cost.
 * For Claude.ai subscribers the response explains which rate-limit pool is
 * being drawn from; for API-key users it returns a formatted cost total.
 * ANT-internal builds show the cost total in addition to the subscription
 * message.
 */
@Slf4j
@Service
public class CostCommandService {



    // ---------------------------------------------------------------------------
    // Constants (overages message — mirrors the TypeScript source verbatim)
    // ---------------------------------------------------------------------------

    static final String OVERAGES_MESSAGE =
        "You are currently using your overages to power your Claude Code usage. " +
        "We will automatically switch you back to your subscription rate limits when they reset";

    static final String SUBSCRIPTION_MESSAGE =
        "You are currently using your subscription to power your Claude Code usage";

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final CostTrackerService costTrackerService;
    private final ClaudeAiLimitsService claudeAiLimitsService;
    private final AuthService authService;

    @Autowired
    public CostCommandService(CostTrackerService costTrackerService,
                               ClaudeAiLimitsService claudeAiLimitsService,
                               AuthService authService) {
        this.costTrackerService = costTrackerService;
        this.claudeAiLimitsService = claudeAiLimitsService;
        this.authService = authService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Execute the /cost command.
     * Mirrors the {@code call()} export in cost.ts.
     *
     * @param isAntUser {@code true} when the {@code USER_TYPE} env var equals
     *                  {@code "ant"}, exposing the cost total even for
     *                  subscribers.
     * @return A {@link CostCommandResult} containing the display text.
     */
    public CompletableFuture<CostCommandResult> call(boolean isAntUser) {
        return CompletableFuture.supplyAsync(() -> {

            if (authService.isClaudeAISubscriber()) {
                String value;

                if (claudeAiLimitsService.isUsingOverage()) {
                    value = OVERAGES_MESSAGE;
                } else {
                    value = SUBSCRIPTION_MESSAGE;
                }

                // ANT-only builds append the cost total for debugging purposes
                if (isAntUser) {
                    value += "\n\n[ANT-ONLY] Showing cost anyway:\n " +
                             costTrackerService.formatTotalCost();
                }

                return new CostCommandResult(value);
            }

            // Non-subscriber: show the raw cost total
            return new CostCommandResult(costTrackerService.formatTotalCost());
        });
    }

    // ---------------------------------------------------------------------------
    // Result type
    // ---------------------------------------------------------------------------

    /**
     * Holds the text output of a /cost invocation.
     *
     * @param value The formatted string to display to the user.
     */
    public record CostCommandResult(String value) {
        /** Convenience accessor matching the TypeScript {@code { type, value }} shape. */
        public String type() {
            return "text";
        }
    }
}
