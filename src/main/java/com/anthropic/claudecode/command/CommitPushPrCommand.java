package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.AttributionService;
import com.anthropic.claudecode.service.PromptShellExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Commit, push, and open a pull-request command.
 * Translated from src/commands/commit-push-pr.ts
 *
 * Stages all changes, commits them with an AI-generated message, pushes the
 * branch to origin, and creates (or updates) a GitHub pull request.
 */
@Slf4j
@Component
@Command(
    name = "commit-push-pr",
    description = "Commit, push, and open a PR"
)
public class CommitPushPrCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CommitPushPrCommand.class);


    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    /** Tools that the underlying prompt agent is allowed to invoke. */
    public static final List<String> ALLOWED_TOOLS = List.of(
        "Bash(git checkout --branch:*)",
        "Bash(git checkout -b:*)",
        "Bash(git add:*)",
        "Bash(git status:*)",
        "Bash(git push:*)",
        "Bash(git commit:*)",
        "Bash(gh pr create:*)",
        "Bash(gh pr edit:*)",
        "Bash(gh pr view:*)",
        "Bash(gh pr merge:*)",
        "ToolSearch",
        "mcp__slack__send_message",
        "mcp__claude_ai_Slack__slack_send_message"
    );

    private static final String PROGRESS_MESSAGE = "creating commit and PR";

    // ---------------------------------------------------------------------------
    // CLI parameters
    // ---------------------------------------------------------------------------

    @Parameters(index = "0", arity = "0..1", description = "Additional instructions for the agent")
    private String additionalInstructions;

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final AttributionService attributionService;
    private final PromptShellExecutionService promptShellExecutionService;

    @Autowired
    public CommitPushPrCommand(AttributionService attributionService,
                                PromptShellExecutionService promptShellExecutionService) {
        this.attributionService = attributionService;
        this.promptShellExecutionService = promptShellExecutionService;
    }

    // ---------------------------------------------------------------------------
    // Callable
    // ---------------------------------------------------------------------------

    @Override
    public Integer call() {
        try {
            log.info("{}", PROGRESS_MESSAGE);

            String prompt = buildPrompt(additionalInstructions)
                .get();

            log.debug("Built commit-push-pr prompt ({} chars)", prompt.length());
            System.out.println(prompt);
            return 0;
        } catch (Exception e) {
            log.error("commit-push-pr command failed: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // ---------------------------------------------------------------------------
    // Prompt construction
    // ---------------------------------------------------------------------------

    /**
     * Asynchronously builds the full prompt string that drives the commit/push/PR
     * agent.  Mirrors getPromptForCommand() in commit-push-pr.ts.
     *
     * @param args Optional extra instructions from the user.
     * @return CompletableFuture resolving to the completed prompt text.
     */
    public CompletableFuture<String> buildPrompt(String args) {
        String defaultBranchFuture = resolveDefaultBranch();
        AttributionService.AttributionTexts attribution = attributionService.getAttributionTexts();
        String prAttribution = attributionService.getEnhancedPRAttribution();

        String promptContent = getPromptContent(defaultBranchFuture, attribution, prAttribution);

        String trimmedArgs = (args != null) ? args.trim() : "";
        if (!trimmedArgs.isEmpty()) {
            promptContent += "\n\n## Additional instructions from user\n\n" + trimmedArgs;
        }

        return promptShellExecutionService.executeShellCommandsInPrompt(
            promptContent,
            ALLOWED_TOOLS,
            "/commit-push-pr"
        );
    }

    /**
     * Produces the static portions of the prompt, injecting the default branch
     * name and attribution texts.  Mirrors getPromptContent() in the TS source.
     */
    private String getPromptContent(String defaultBranch,
                                     AttributionService.AttributionTexts attribution,
                                     String prAttribution) {

        String commitAttribution = attribution.commit();
        String effectivePrAttribution = (prAttribution != null && !prAttribution.isBlank())
            ? prAttribution
            : attribution.generatedWith();

        String safeUser = System.getenv().getOrDefault("SAFEUSER", "");
        String username = System.getenv().getOrDefault("USER", "");

        String reviewerArg = " and `--reviewer anthropics/claude-code`";
        String addReviewerArg = " (and add `--add-reviewer anthropics/claude-code`)";
        String changelogSection = """

## Changelog
<!-- CHANGELOG:START -->
[If this PR contains user-facing changes, add a changelog entry here. Otherwise, remove this section.]
<!-- CHANGELOG:END -->""";

        String slackStep = """

5. After creating/updating the PR, check if the user's CLAUDE.md mentions posting to Slack channels.\
 If it does, use ToolSearch to search for "slack send message" tools. If ToolSearch finds a Slack tool,\
 ask the user if they'd like you to post the PR URL to the relevant Slack channel. Only post if the user confirms.\
 If ToolSearch returns no results or errors, skip this step silently—do not mention the failure,\
 do not attempt workarounds, and do not try alternative approaches.""";

        String commitSuffix = (commitAttribution != null && !commitAttribution.isBlank())
            ? "\n\n" + commitAttribution
            : "";
        String commitAttributionNote = (commitAttribution != null && !commitAttribution.isBlank())
            ? ", ending with the attribution text shown in the example below"
            : "";
        String prAttributionBody = (effectivePrAttribution != null && !effectivePrAttribution.isBlank())
            ? "\n\n" + effectivePrAttribution
            : "";

        return """
## Context

- `SAFEUSER`: %s
- `whoami`: %s
- `git status`: !`git status`
- `git diff HEAD`: !`git diff HEAD`
- `git branch --show-current`: !`git branch --show-current`
- `git diff %s...HEAD`: !`git diff %s...HEAD`
- `gh pr view --json number 2>/dev/null || true`: !`gh pr view --json number 2>/dev/null || true`

## Git Safety Protocol

- NEVER update the git config
- NEVER run destructive/irreversible git commands (like push --force, hard reset, etc) unless the user explicitly requests them
- NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly requests it
- NEVER run force push to main/master, warn the user if they request it
- Do not commit files that likely contain secrets (.env, credentials.json, etc)
- Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported

## Your task

Analyze all changes that will be included in the pull request, making sure to look at all relevant commits \
(NOT just the latest commit, but ALL commits that will be included in the pull request from the git diff %s...HEAD output above).

Based on the above changes:
1. Create a new branch if on %s (use SAFEUSER from context above for the branch name prefix, falling back to whoami if SAFEUSER is empty, e.g., `username/feature-name`)
2. Create a single commit with an appropriate message using heredoc syntax%s:
```
git commit -m "$(cat <<'EOF'
Commit message here.%s
EOF
)"
```
3. Push the branch to origin
4. If a PR already exists for this branch (check the gh pr view output above), update the PR title and body using `gh pr edit` to reflect the current diff%s. Otherwise, create a pull request using `gh pr create` with heredoc syntax for the body%s.
   - IMPORTANT: Keep PR titles short (under 70 characters). Use the body for details.
```
gh pr create --title "Short, descriptive title" --body "$(cat <<'EOF'
## Summary
<1-3 bullet points>

## Test plan
[Bulleted markdown checklist of TODOs for testing the pull request...]%s%s
EOF
)"
```

You have the capability to call multiple tools in a single response. You MUST do all of the above in a single message.%s

Return the PR URL when you're done, so the user can see it."""
            .formatted(
                safeUser,
                username,
                defaultBranch, defaultBranch,
                defaultBranch,
                defaultBranch,
                commitAttributionNote,
                commitSuffix,
                addReviewerArg,
                reviewerArg,
                changelogSection,
                prAttributionBody,
                slackStep
            );
    }

    /**
     * Resolves the default branch name for the current repository.
     * Falls back to "main" when the git command is unavailable.
     */
    private String resolveDefaultBranch() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref",
                "origin/HEAD");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (output.startsWith("origin/")) {
                return output.substring("origin/".length());
            }
            return output.isEmpty() ? "main" : output;
        } catch (Exception e) {
            log.debug("Could not determine default branch, using 'main': {}", e.getMessage());
            return "main";
        }
    }
}
