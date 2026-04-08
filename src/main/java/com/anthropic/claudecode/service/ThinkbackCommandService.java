package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Command descriptor for the /think-back command.
 * Translated from src/commands/thinkback/index.ts
 *
 * <p>Shows the "Your 2025 Claude Code Year in Review" experience. The command is
 * guarded behind a Statsig (GrowthBook) feature gate so it is only enabled for
 * users who are enrolled in the {@code tengu_thinkback} experiment.
 */
@Slf4j
@Service
public class ThinkbackCommandService {



    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "think-back";
    public static final String DESCRIPTION = "Your 2025 Claude Code Year in Review";
    public static final String TYPE = "local-jsx";

    /** Feature gate key used to control access to the thinkback experience. */
    private static final String FEATURE_GATE = "tengu_thinkback";

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

    private final FeatureGateService featureGateService;

    @Autowired
    public ThinkbackCommandService(FeatureGateService featureGateService) {
        this.featureGateService = featureGateService;
    }

    // -------------------------------------------------------------------------
    // Dynamic command properties
    // -------------------------------------------------------------------------

    /**
     * Returns whether this command is currently enabled.
     * Translated from the {@code isEnabled()} arrow function in thinkback/index.ts
     *
     * <p>Uses a cached (potentially stale) Statsig/GrowthBook feature gate check.
     */
    public boolean isEnabled() {
        return featureGateService.checkFeatureGateCachedMayBeStale(FEATURE_GATE);
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute the /think-back command.
     * In the TypeScript source the actual rendering is deferred to thinkback.js (JSX).
     * Here we return a text result indicating the thinkback view should be launched.
     */
    public CommandResult call() {
        if (!isEnabled()) {
            log.warn("[think-back] Command invoked but feature gate '{}' is disabled", FEATURE_GATE);
            return new CommandResult.TextResult(
                    "The /think-back experience is not available for your account.");
        }
        log.info("[think-back] Launching Thinkback Year in Review view");
        return new CommandResult.TextResult(
                "Opening your 2025 Claude Code Year in Review.");
    }
}
