package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Command descriptor for the /rate-limit-options command.
 * Translated from src/commands/rate-limit-options/index.ts
 *
 * <p>Shows options when the rate limit is reached. This command is hidden from
 * the help menu — it is only used internally by the application when a rate-limit
 * event occurs. It is enabled exclusively for Claude.ai subscribers.
 */
@Slf4j
@Service
public class RateLimitOptionsCommandService {



    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "rate-limit-options";
    public static final String DESCRIPTION = "Show options when rate limit is reached";
    public static final String TYPE = "local-jsx";

    /**
     * This command is always hidden from the help menu.
     * Translated from {@code isHidden: true} in rate-limit-options/index.ts
     */
    public static final boolean IS_HIDDEN = true;

    // -------------------------------------------------------------------------
    // Command result type
    // -------------------------------------------------------------------------

    /** Mirrors the TypeScript {@code LocalCommandResult} union. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final AuthService authService;

    @Autowired
    public RateLimitOptionsCommandService(AuthService authService) {
        this.authService = authService;
    }

    // -------------------------------------------------------------------------
    // Dynamic command properties
    // -------------------------------------------------------------------------

    /**
     * Returns whether this command is currently enabled.
     * Translated from the {@code isEnabled()} function in rate-limit-options/index.ts
     *
     * <p>The command is enabled only for Claude.ai subscribers.
     */
    public boolean isEnabled() {
        return authService.isClaudeAISubscriber();
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute the /rate-limit-options command.
     * In the TypeScript source the actual rendering is deferred to rate-limit-options.js (JSX).
     * Here we return a text result indicating the rate-limit options view should be launched.
     */
    public CommandResult call() {
        if (!isEnabled()) {
            log.warn("[rate-limit-options] Command invoked but user is not a Claude.ai subscriber");
            return new CommandResult.TextResult(
                    "The /rate-limit-options command is only available for Claude.ai subscribers.");
        }
        log.info("[rate-limit-options] Launching rate limit options view");
        return new CommandResult.TextResult(
                "You have reached your rate limit. Opening options to manage your usage.");
    }
}
