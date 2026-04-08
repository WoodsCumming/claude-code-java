package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Command descriptor for the /remote-env command.
 * Translated from src/commands/remote-env/index.ts
 *
 * <p>Configures the default remote environment for teleport sessions. The command
 * is only available when the current user is a Claude.ai subscriber <em>and</em>
 * the {@code allow_remote_sessions} policy is permitted. It is also hidden from
 * the help menu when those conditions are not met.
 */
@Slf4j
@Service
public class RemoteEnvCommandService {



    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "remote-env";
    public static final String DESCRIPTION =
            "Configure the default remote environment for teleport sessions";
    public static final String TYPE = "local-jsx";

    // Policy key checked against the policy-limits service
    private static final String POLICY_KEY = "allow_remote_sessions";

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
    private final PolicyLimitsService policyLimitsService;

    @Autowired
    public RemoteEnvCommandService(
            AuthService authService,
            PolicyLimitsService policyLimitsService) {
        this.authService = authService;
        this.policyLimitsService = policyLimitsService;
    }

    // -------------------------------------------------------------------------
    // Dynamic command properties
    // -------------------------------------------------------------------------

    /**
     * Returns whether this command is currently enabled.
     * Translated from the {@code isEnabled()} arrow function in remote-env/index.ts
     *
     * <p>Requires both a Claude.ai subscription and the {@code allow_remote_sessions} policy.
     */
    public boolean isEnabled() {
        return authService.isClaudeAISubscriber()
                && policyLimitsService.isPolicyAllowed(POLICY_KEY);
    }

    /**
     * Returns whether this command should be hidden from the help menu.
     * Translated from the {@code get isHidden()} getter in remote-env/index.ts
     *
     * <p>Hidden when either subscription or policy requirement is not satisfied.
     */
    public boolean isHidden() {
        return !authService.isClaudeAISubscriber()
                || !policyLimitsService.isPolicyAllowed(POLICY_KEY);
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute the /remote-env command.
     * In the TypeScript source the actual rendering is deferred to remote-env.js (JSX).
     * Here we return a text result indicating the remote-env configuration view should be launched.
     */
    public CommandResult call() {
        if (!isEnabled()) {
            log.warn("[remote-env] Command invoked but prerequisites not satisfied "
                    + "(subscriber={}, policy={})",
                    authService.isClaudeAISubscriber(),
                    policyLimitsService.isPolicyAllowed(POLICY_KEY));
            return new CommandResult.TextResult(
                    "The /remote-env command requires a Claude.ai subscription "
                    + "and the remote sessions policy to be enabled.");
        }
        log.info("[remote-env] Launching remote environment configuration view");
        return new CommandResult.TextResult(
                "Opening remote environment configuration for teleport sessions.");
    }
}
