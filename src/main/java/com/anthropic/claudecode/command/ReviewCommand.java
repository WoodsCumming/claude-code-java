package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

/**
 * Review command ("ultrareview") — launch a deep automated code review.
 *
 * Translated from src/commands/review/reviewRemote.ts and src/commands/review/ultrareviewCommand.tsx
 *
 * TypeScript original behaviour (ultrareview):
 *   - No arg / branch mode:
 *       1. Check overage gate (free-review quota; Extra Usage balance if exhausted)
 *       2. Find merge-base SHA against the default branch (fails fast on empty diff)
 *       3. Bundle the working tree and teleport to a remote CCR session
 *       4. Register a RemoteAgentTask; results arrive via task-notification (~10-20 min)
 *   - PR number arg:
 *       1. Same overage gate
 *       2. Detect current GitHub repo
 *       3. Teleport via refs/pull/&lt;N&gt;/head (no bundling needed)
 *       4. Same RemoteAgentTask registration
 *   - Team/Enterprise subscribers bypass the overage dialog entirely
 *   - The command name in the TypeScript source is "ultrareview"; the parent command
 *     file (src/commands/review/index.ts) does not exist but the pattern follows
 *     /ultrareview in the CLI
 *
 * Java translation:
 *   - /review [PR#] triggers ReviewService which encapsulates all remote-agent logic
 *   - Overage gate, repo detection, and remote-session registration are delegated to
 *     the service layer
 */
@Slf4j
@Component
@Command(
    name = "review",
    description = "Launch a deep automated code review (ultrareview) for the current branch or a PR",
    mixinStandardHelpOptions = true
)
public class ReviewCommand implements Callable<Integer> {



    /**
     * Optional PR number.
     * - Omit → review the current branch changes vs. the default branch merge-base
     * - Provide a PR number → review the specified pull request on GitHub
     */
    @Parameters(index = "0",
                description = "PR number to review (omit to review current branch changes)",
                arity = "0..1")
    private String prNumber;

    private final ReviewService reviewService;

    @Autowired
    public ReviewCommand(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public Integer call() {
        // 1. Overage / billing gate
        ReviewService.OverageGate gate = reviewService.checkOverageGate();
        switch (gate.kind()) {
            case "not-enabled" -> {
                System.out.println(
                    "Ultrareview requires Extra Usage to be enabled on your account. " +
                    "Free reviews are exhausted. " +
                    "Visit https://claude.ai/settings/billing to enable Extra Usage."
                );
                return 1;
            }
            case "low-balance" -> {
                System.out.printf(
                    "Insufficient Extra Usage balance (available: $%.2f). " +
                    "Please add credits at https://claude.ai/settings/billing.%n",
                    gate.available()
                );
                return 1;
            }
            case "needs-confirm" -> {
                System.out.println(
                    "This review will bill as Extra Usage. " +
                    "Re-run with --confirm to proceed."
                );
                return 1;
            }
            // "proceed" → continue below
        }

        // 2. Launch the review
        try {
            ReviewService.ReviewLaunchResult result = (prNumber != null && !prNumber.isBlank())
                ? reviewService.launchPrReview(prNumber.trim(), gate.billingNote())
                : reviewService.launchBranchReview(gate.billingNote());

            if (!result.success()) {
                System.out.println(result.message());
                return 1;
            }

            System.out.println(result.message());
            if (result.sessionUrl() != null) {
                System.out.println("Track progress: " + result.sessionUrl());
            }
            System.out.println("Ultrareview is running in the cloud (~10-20 min). " +
                               "Results arrive via task notification.");
            return 0;

        } catch (Exception e) {
            log.error("Failed to launch ultrareview", e);
            System.out.println("Failed to launch review: " + e.getMessage());
            return 1;
        }
    }
}
