package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Command descriptor for the /privacy-settings command.
 * Translated from src/commands/privacy-settings/index.ts
 *
 * <p>Allows consumer subscribers to view and update their privacy settings.
 * The command is only enabled when the current user is a consumer subscriber.
 */
@Slf4j
@Service
public class PrivacySettingsCommandService {



    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "privacy-settings";
    public static final String DESCRIPTION = "View and update your privacy settings";
    public static final String TYPE = "local-jsx";

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
    public PrivacySettingsCommandService(AuthService authService) {
        this.authService = authService;
    }

    // -------------------------------------------------------------------------
    // Dynamic command properties
    // -------------------------------------------------------------------------

    /**
     * Returns whether this command is currently enabled.
     * Translated from the {@code isEnabled()} function in privacy-settings/index.ts
     *
     * <p>The command is enabled only for consumer subscribers.
     */
    public boolean isEnabled() {
        return authService.isConsumerSubscriber();
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute the /privacy-settings command.
     * In the TypeScript source the actual rendering is deferred to privacy-settings.js (JSX).
     * Here we return a text result indicating the privacy settings view should be launched.
     */
    public CommandResult call() {
        if (!isEnabled()) {
            log.warn("[privacy-settings] Command invoked but user is not a consumer subscriber");
            return new CommandResult.TextResult(
                    "The /privacy-settings command is only available for consumer subscribers.");
        }
        log.info("[privacy-settings] Launching privacy settings view");
        return new CommandResult.TextResult(
                "Opening privacy settings. You can view and update your privacy settings here.");
    }
}
