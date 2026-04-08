package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * GitHub PR status utilities.
 * Translated from src/utils/ghPrStatus.ts
 *
 * Provides PR status for the current branch using the `gh` CLI.
 */
@Slf4j
public class GhAuthStatus {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GhAuthStatus.class);


    private static final long GH_TIMEOUT_MS = 5000;

    // =========================================================================
    // Types
    // =========================================================================

    /**
     * PR review state values.
     * Translated from PrReviewState in ghPrStatus.ts
     */
    public enum PrReviewState {
        APPROVED("approved"),
        PENDING("pending"),
        CHANGES_REQUESTED("changes_requested"),
        DRAFT("draft"),
        MERGED("merged"),
        CLOSED("closed");

        private final String value;

        PrReviewState(String value) { this.value = value; }

        public String getValue() { return value; }
    }

    /**
     * PR status result.
     * Translated from PrStatus in ghPrStatus.ts
     */
    public record PrStatus(int number, String url, PrReviewState reviewState) {}

    /**
     * Raw JSON structure from `gh pr view`.
     */
    private record GhPrViewData(
        int number,
        String url,
        String reviewDecision,
        boolean isDraft,
        String headRefName,
        String state
    ) {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Derive review state from GitHub API values.
     * Draft PRs always show as 'draft' regardless of reviewDecision.
     * Translated from deriveReviewState() in ghPrStatus.ts
     */
    public static PrReviewState deriveReviewState(boolean isDraft, String reviewDecision) {
        if (isDraft) return PrReviewState.DRAFT;
        return switch (reviewDecision != null ? reviewDecision : "") {
            case "APPROVED" -> PrReviewState.APPROVED;
            case "CHANGES_REQUESTED" -> PrReviewState.CHANGES_REQUESTED;
            default -> PrReviewState.PENDING;
        };
    }

    /**
     * Fetch PR status for the current branch using `gh pr view`.
     * Returns empty on any failure (gh not installed, no PR, not in git repo, etc).
     * Also returns empty if the PR's head branch is the default branch.
     * Translated from fetchPrStatus() in ghPrStatus.ts
     */
    public static CompletableFuture<java.util.Optional<PrStatus>> fetchPrStatus(String repoPath) {
        return CompletableFuture.supplyAsync(() -> {
            if (!GitUtils.getIsGit(repoPath)) return java.util.Optional.empty();

            java.util.Optional<String> branch = GitUtils.getBranch(repoPath);
            java.util.Optional<String> defaultBranch = GitUtils.getDefaultBranch(repoPath);

            // Skip on the default branch — gh pr view returns most recently merged PR
            if (branch.isPresent() && defaultBranch.isPresent()
                && branch.get().equals(defaultBranch.get())) {
                return java.util.Optional.empty();
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "gh", "pr", "view",
                    "--json", "number,url,reviewDecision,isDraft,headRefName,state"
                );
                pb.directory(new java.io.File(repoPath));
                Process p = pb.start();
                String stdout = new String(p.getInputStream().readAllBytes()).trim();
                boolean finished = p.waitFor(GH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!finished) { p.destroyForcibly(); return java.util.Optional.empty(); }
                if (p.exitValue() != 0 || stdout.isBlank()) return java.util.Optional.empty();

                GhPrViewData data = parseGhPrViewJson(stdout);
                if (data == null) return java.util.Optional.empty();

                // Skip PRs from the default branch
                String headRef = data.headRefName();
                if ("main".equals(headRef) || "master".equals(headRef)
                    || (defaultBranch.isPresent() && defaultBranch.get().equals(headRef))) {
                    return java.util.Optional.empty();
                }

                // Skip merged/closed PRs
                if ("MERGED".equals(data.state()) || "CLOSED".equals(data.state())) {
                    return java.util.Optional.empty();
                }

                PrReviewState reviewState = deriveReviewState(data.isDraft(), data.reviewDecision());
                return java.util.Optional.of(new PrStatus(data.number(), data.url(), reviewState));

            } catch (Exception e) {
                log.debug("Could not fetch PR status: {}", e.getMessage());
                return java.util.Optional.empty();
            }
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Minimal JSON parser for gh pr view output.
     * Avoids pulling in Jackson for a simple well-known structure.
     */
    private static GhPrViewData parseGhPrViewJson(String json) {
        try {
            int number = extractJsonInt(json, "number");
            String url = extractJsonString(json, "url");
            String reviewDecision = extractJsonString(json, "reviewDecision");
            boolean isDraft = extractJsonBoolean(json, "isDraft");
            String headRefName = extractJsonString(json, "headRefName");
            String state = extractJsonString(json, "state");
            return new GhPrViewData(number, url, reviewDecision, isDraft, headRefName, state);
        } catch (Exception e) {
            log.debug("Failed to parse gh pr view output: {}", e.getMessage());
            return null;
        }
    }

    private static int extractJsonInt(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\"\\s*:\\s*(\\d+)")
            .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String extractJsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static boolean extractJsonBoolean(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\"\\s*:\\s*(true|false)")
            .matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private GhAuthStatus() {}
}
