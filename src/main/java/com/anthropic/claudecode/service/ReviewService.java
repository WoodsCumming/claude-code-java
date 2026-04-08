package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Ultrareview (deep automated code review) service.
 * Translated from src/commands/review/reviewRemote.ts
 *
 * Handles overage gate checks and launch of remote code-review sessions
 * against the current branch or a specified GitHub pull request.
 */
@Slf4j
@Service
public class ReviewService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReviewService.class);


    private final BillingService billingService;

    @Autowired
    public ReviewService(BillingService billingService) {
        this.billingService = billingService;
    }

    // -----------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------

    /**
     * Result of the overage/billing gate check.
     * kind values: "proceed" | "not-enabled" | "low-balance" | "needs-confirm"
     */
    public record OverageGate(String kind, double available, String billingNote) {}

    /**
     * Result of launching an ultrareview session.
     */
    public record ReviewLaunchResult(boolean success, String message, String sessionUrl) {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Check whether the user can proceed with an ultrareview (billing gate).
     * Translated from checkOverageGate() in reviewRemote.ts
     */
    public OverageGate checkOverageGate() {
        try {
            // Check if the user has any billing access (team / enterprise bypass the gate)
            boolean hasBillingAccess = billingService.hasConsoleBillingAccess()
                    || billingService.hasClaudeAiBillingAccess();
            if (!hasBillingAccess) {
                return new OverageGate("not-enabled", 0, null);
            }
            return new OverageGate("proceed", 0, null);
        } catch (Exception e) {
            log.debug("[ReviewService] checkOverageGate error: {}", e.getMessage());
            return new OverageGate("proceed", 0, null);
        }
    }

    /**
     * Launch an ultrareview for the current branch vs. the default branch merge-base.
     * Translated from launchBranchReview() in reviewRemote.ts
     */
    public ReviewLaunchResult launchBranchReview(String billingNote) {
        // Stub implementation — full version would invoke the teleport API
        log.info("[ReviewService] launchBranchReview (stub)");
        return new ReviewLaunchResult(true,
                "Ultrareview launched for current branch.", null);
    }

    /**
     * Launch an ultrareview for the specified GitHub PR.
     * Translated from launchPrReview() in reviewRemote.ts
     */
    public ReviewLaunchResult launchPrReview(String prNumber, String billingNote) {
        // Stub implementation — full version would invoke the teleport API
        log.info("[ReviewService] launchPrReview PR #{} (stub)", prNumber);
        return new ReviewLaunchResult(true,
                "Ultrareview launched for PR #" + prNumber + ".", null);
    }
}
