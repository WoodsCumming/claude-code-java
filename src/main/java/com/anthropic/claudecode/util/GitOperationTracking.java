package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell-agnostic git operation tracking for usage metrics.
 * Translated from src/tools/shared/gitOperationTracking.ts
 *
 * <p>Detects {@code git commit}, {@code git push}, {@code gh pr create},
 * {@code glab mr create}, and curl-based PR creation in command strings, then
 * fires analytics events. The regexes operate on raw command text so they work
 * identically for Bash and PowerShell.</p>
 */
@Slf4j
public class GitOperationTracking {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GitOperationTracking.class);


    // -------------------------------------------------------------------------
    // Regex helpers
    // -------------------------------------------------------------------------

    /**
     * Build a regex that matches {@code git <subcmd>} while tolerating git's global
     * options between {@code git} and the subcommand (e.g. {@code -c key=val},
     * {@code -C path}, {@code --git-dir=path}).
     * Translated from gitCmdRe() in gitOperationTracking.ts
     */
    private static Pattern gitCmdRe(String subcmd, String suffix) {
        return Pattern.compile(
                "\\bgit(?:\\s+-[cC]\\s+\\S+|\\s+--\\S+=\\S+)*\\s+" + subcmd + "\\b" + suffix
        );
    }

    private static final Pattern GIT_COMMIT_RE     = gitCmdRe("commit", "");
    private static final Pattern GIT_PUSH_RE       = gitCmdRe("push", "");
    private static final Pattern GIT_CHERRY_PICK_RE = gitCmdRe("cherry-pick", "");
    private static final Pattern GIT_MERGE_RE      = gitCmdRe("merge", "(?!-)");
    private static final Pattern GIT_REBASE_RE     = gitCmdRe("rebase", "");

    // -------------------------------------------------------------------------
    // Enums / domain types
    // -------------------------------------------------------------------------

    /** The kind of commit operation detected. */
    public enum CommitKind { COMMITTED, AMENDED, CHERRY_PICKED }

    /** The kind of branch operation detected. */
    public enum BranchAction { MERGED, REBASED }

    /** The kind of PR operation detected. */
    public enum PrAction { CREATED, EDITED, MERGED, COMMENTED, CLOSED, READY }

    /**
     * A single GitHub PR action pattern.
     */
    private record GhPrActionEntry(Pattern re, PrAction action, String op) {}

    private static final List<GhPrActionEntry> GH_PR_ACTIONS = List.of(
            new GhPrActionEntry(Pattern.compile("\\bgh\\s+pr\\s+create\\b"),  PrAction.CREATED,   "pr_create"),
            new GhPrActionEntry(Pattern.compile("\\bgh\\s+pr\\s+edit\\b"),    PrAction.EDITED,    "pr_edit"),
            new GhPrActionEntry(Pattern.compile("\\bgh\\s+pr\\s+merge\\b"),   PrAction.MERGED,    "pr_merge"),
            new GhPrActionEntry(Pattern.compile("\\bgh\\s+pr\\s+comment\\b"), PrAction.COMMENTED, "pr_comment"),
            new GhPrActionEntry(Pattern.compile("\\bgh\\s+pr\\s+close\\b"),   PrAction.CLOSED,    "pr_close"),
            new GhPrActionEntry(Pattern.compile("\\bgh\\s+pr\\s+ready\\b"),   PrAction.READY,     "pr_ready")
    );

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /** Detected commit: short SHA and kind. */
    public record CommitInfo(String sha, CommitKind kind) {}

    /** Detected push: target branch name. */
    public record PushInfo(String branch) {}

    /** Detected branch operation: target ref and action. */
    public record BranchInfo(String ref, BranchAction action) {}

    /** Detected PR operation: number, optional URL, and action. */
    public record PrInfo(int number, String url, PrAction action) {}

    /** Aggregated result of detectGitOperation(). All fields are Optional. */
    public record GitOperationResult(
            Optional<CommitInfo> commit,
            Optional<PushInfo>   push,
            Optional<BranchInfo> branch,
            Optional<PrInfo>     pr
    ) {
        public static GitOperationResult empty() {
            return new GitOperationResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** Full git-PR info parsed from a GitHub URL. */
    public record PrUrlInfo(int prNumber, String prUrl, String prRepository) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parse a git commit short-SHA from stdout.
     * git commit prints: [branch abc1234] message
     * Translated from parseGitCommitId() in gitOperationTracking.ts
     */
    public static Optional<String> parseGitCommitId(String stdout) {
        if (stdout == null) return Optional.empty();
        Matcher m = Pattern.compile("\\[[\\w./-]+(?:\\s+\\(root-commit\\))?\\s+([0-9a-f]+)]")
                .matcher(stdout);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Parse the pushed branch name from git push output.
     * Translated from parseGitPushBranch() in gitOperationTracking.ts
     */
    private static Optional<String> parseGitPushBranch(String output) {
        if (output == null) return Optional.empty();
        Matcher m = Pattern.compile(
                "(?m)^\\s*[+\\-*!= ]?\\s*(?:\\[new branch]|\\S+\\.\\++\\S+)\\s+\\S+\\s*->\\s*(\\S+)"
        ).matcher(output);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Parse a GitHub PR URL into its components.
     * Translated from parsePrUrl() in gitOperationTracking.ts
     */
    private static Optional<PrUrlInfo> parsePrUrl(String url) {
        if (url == null) return Optional.empty();
        Matcher m = Pattern.compile("https://github\\.com/([^/]+/[^/]+)/pull/(\\d+)")
                .matcher(url);
        if (!m.find()) return Optional.empty();
        return Optional.of(new PrUrlInfo(
                Integer.parseInt(m.group(2)),
                url,
                m.group(1)
        ));
    }

    /**
     * Find a GitHub PR URL embedded anywhere in stdout.
     * Translated from findPrInStdout() in gitOperationTracking.ts
     */
    private static Optional<PrUrlInfo> findPrInStdout(String stdout) {
        if (stdout == null) return Optional.empty();
        Matcher m = Pattern.compile("https://github\\.com/[^/\\s]+/[^/\\s]+/pull/\\d+")
                .matcher(stdout);
        return m.find() ? parsePrUrl(m.group()) : Optional.empty();
    }

    /**
     * Extract a PR number from human-readable text like "✓ Merged pull request owner/repo#42".
     * Translated from parsePrNumberFromText() in gitOperationTracking.ts
     */
    private static Optional<Integer> parsePrNumberFromText(String stdout) {
        if (stdout == null) return Optional.empty();
        Matcher m = Pattern.compile("[Pp]ull request (?:\\S+#)?#?(\\d+)").matcher(stdout);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    /**
     * Extract the target ref from a {@code git merge <ref>} or {@code git rebase <ref>} command.
     * Translated from parseRefFromCommand() in gitOperationTracking.ts
     */
    private static Optional<String> parseRefFromCommand(String command, String verb) {
        String[] parts = gitCmdRe(verb, "").split(command);
        if (parts.length < 2) return Optional.empty();
        for (String token : parts[1].trim().split("\\s+")) {
            if (token.matches("[&|;><].*")) break;
            if (token.startsWith("-")) continue;
            return Optional.of(token);
        }
        return Optional.empty();
    }

    /**
     * Scan a bash command + combined stdout/stderr for git operations.
     * Translated from detectGitOperation() in gitOperationTracking.ts
     *
     * @param command the shell command string
     * @param output  stdout + stderr concatenated
     */
    public static GitOperationResult detectGitOperation(String command, String output) {
        if (command == null) return GitOperationResult.empty();

        Optional<CommitInfo> commit = Optional.empty();
        Optional<PushInfo>   push   = Optional.empty();
        Optional<BranchInfo> branch = Optional.empty();
        Optional<PrInfo>     pr     = Optional.empty();

        // commit / cherry-pick
        boolean isCherryPick = GIT_CHERRY_PICK_RE.matcher(command).find();
        if (GIT_COMMIT_RE.matcher(command).find() || isCherryPick) {
            Optional<String> sha = parseGitCommitId(output);
            if (sha.isPresent()) {
                CommitKind kind = isCherryPick ? CommitKind.CHERRY_PICKED
                        : command.contains("--amend") ? CommitKind.AMENDED
                        : CommitKind.COMMITTED;
                commit = Optional.of(new CommitInfo(sha.get().substring(0, Math.min(6, sha.get().length())), kind));
            }
        }

        // push
        if (GIT_PUSH_RE.matcher(command).find()) {
            Optional<String> branchName = parseGitPushBranch(output);
            if (branchName.isPresent()) push = Optional.of(new PushInfo(branchName.get()));
        }

        // merge
        if (GIT_MERGE_RE.matcher(command).find()
                && output != null && output.matches("(?s).*(Fast-forward|Merge made by).*")) {
            Optional<String> ref = parseRefFromCommand(command, "merge");
            if (ref.isPresent()) branch = Optional.of(new BranchInfo(ref.get(), BranchAction.MERGED));
        }

        // rebase
        if (GIT_REBASE_RE.matcher(command).find()
                && output != null && output.contains("Successfully rebased")) {
            Optional<String> ref = parseRefFromCommand(command, "rebase");
            if (ref.isPresent()) branch = Optional.of(new BranchInfo(ref.get(), BranchAction.REBASED));
        }

        // gh pr actions
        Optional<GhPrActionEntry> prHit = GH_PR_ACTIONS.stream()
                .filter(e -> e.re().matcher(command).find())
                .findFirst();
        if (prHit.isPresent()) {
            PrAction action = prHit.get().action();
            Optional<PrUrlInfo> prUrl = findPrInStdout(output);
            if (prUrl.isPresent()) {
                pr = Optional.of(new PrInfo(prUrl.get().prNumber(), prUrl.get().prUrl(), action));
            } else {
                Optional<Integer> num = parsePrNumberFromText(output);
                if (num.isPresent()) pr = Optional.of(new PrInfo(num.get(), null, action));
            }
        }

        return new GitOperationResult(commit, push, branch, pr);
    }

    /**
     * Track git operations and emit analytics events.
     * Translated from trackGitOperations() in gitOperationTracking.ts
     *
     * @param command  the shell command string
     * @param exitCode process exit code; only tracks on 0 (success)
     * @param stdout   optional stdout for extracting PR info
     */
    public static void trackGitOperations(String command, int exitCode, String stdout) {
        if (exitCode != 0 || command == null) return;

        if (GIT_COMMIT_RE.matcher(command).find()) {
            log.debug("[git] operation=commit");
            if (command.contains("--amend")) {
                log.debug("[git] operation=commit_amend");
            }
        }

        if (GIT_PUSH_RE.matcher(command).find()) {
            log.debug("[git] operation=push");
        }

        Optional<GhPrActionEntry> prHit = GH_PR_ACTIONS.stream()
                .filter(e -> e.re().matcher(command).find())
                .findFirst();
        if (prHit.isPresent()) {
            log.debug("[git] operation={}", prHit.get().op());
            if (prHit.get().action() == PrAction.CREATED && stdout != null) {
                findPrInStdout(stdout).ifPresent(info ->
                        log.debug("[git] PR created: #{} {} ({})", info.prNumber(), info.prUrl(), info.prRepository()));
            }
        }

        // glab mr create
        if (command.matches("(?s).*\\bglab\\s+mr\\s+create\\b.*")) {
            log.debug("[git] operation=pr_create (glab)");
        }

        // curl-based PR creation
        boolean isCurlPost = command.contains("curl")
                && (command.matches("(?s).*-X\\s*POST\\b.*")
                    || command.matches("(?s).*--request\\s*=?\\s*POST\\b.*")
                    || command.contains(" -d "));
        boolean isPrEndpoint = command.matches(
                "(?si).*https?://[^\\s'\"]*/(pulls|pull-requests|merge[-_]requests)(?!/\\d).*");
        if (isCurlPost && isPrEndpoint) {
            log.debug("[git] operation=pr_create (curl)");
        }
    }

    private GitOperationTracking() {}
}
