package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Command descriptor for the /passes command.
 * Translated from src/commands/passes/index.ts
 *
 * Allows users to share a free week of Claude Code with friends (referral passes).
 * The description is dynamic: if the current user has a cached referrer reward,
 * it advertises earning extra usage; otherwise it uses the plain share message.
 * The command is hidden unless the user is eligible and the eligibility has been
 * cached locally.
 */
@Slf4j
@Service
public class PassesCommandService {



    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "passes";
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

    private final ReferralService referralService;

    @Autowired
    public PassesCommandService(ReferralService referralService) {
        this.referralService = referralService;
    }

    // -------------------------------------------------------------------------
    // Dynamic command properties
    // -------------------------------------------------------------------------

    /**
     * Returns the dynamic description for the command.
     * Translated from the {@code get description()} getter in passes/index.ts
     *
     * <p>When the user has a cached referrer reward the description mentions earning
     * extra usage; otherwise it uses the plain share message.
     */
    public String getDescription() {
        ReferralService.ReferrerRewardInfo reward = referralService.getCachedReferrerReward();
        if (reward != null) {
            return "Share a free week of Claude Code with friends and earn extra usage";
        }
        return "Share a free week of Claude Code with friends";
    }

    /**
     * Returns whether the command should be hidden from the help menu.
     * Translated from the {@code get isHidden()} getter in passes/index.ts
     *
     * <p>The command is hidden if the user is not eligible, or if no cached
     * eligibility data exists yet.
     */
    public boolean isHidden() {
        ReferralService.CachedEligibilityResult eligibility = referralService.checkCachedPassesEligibility();
        return !eligibility.eligible() || !eligibility.hasCache();
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute the /passes command.
     * In the TypeScript source the actual rendering is deferred to passes.js (JSX).
     * Here we return a text result indicating the passes view should be launched.
     */
    public CommandResult call() {
        if (isHidden()) {
            log.warn("[passes] Command invoked but user is not eligible or cache is missing");
            return new CommandResult.TextResult(
                    "The /passes command is not available for your account.");
        }
        log.info("[passes] Launching passes view");
        return new CommandResult.TextResult(
                "Opening passes view. Share a free week of Claude Code with your friends.");
    }
}
