package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GitHub Actions setup service for the /install-github-app command.
 * Translated from src/commands/install-github-app/setupGitHubActions.ts
 *
 * Orchestrates the full GitHub Actions CI setup flow:
 * <ol>
 *   <li>Validates that the repository is accessible via the {@code gh} CLI.</li>
 *   <li>Determines the default branch and its HEAD SHA.</li>
 *   <li>Creates a new branch and pushes the selected workflow files via the
 *       GitHub Contents API.</li>
 *   <li>Optionally stores the API key / OAuth token as a repository secret.</li>
 *   <li>Opens a browser-based PR creation URL so the user can review and merge
 *       the changes.</li>
 * </ol>
 */
@Slf4j
@Service
public class SetupGitHubActionsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SetupGitHubActionsService.class);


    // ---------------------------------------------------------------------------
    // Workflow type
    // ---------------------------------------------------------------------------

    /** Represents a selectable GitHub Actions workflow variant. */
    public enum Workflow {
        /** Main Claude PR-assistant workflow ({@code claude.yml}). */
        CLAUDE,
        /** Claude Code Review workflow ({@code claude-code-review.yml}). */
        CLAUDE_REVIEW
    }

    /** Authentication strategy used when storing the secret. */
    public enum AuthType {
        API_KEY,
        OAUTH_TOKEN
    }

    // ---------------------------------------------------------------------------
    // Workflow content constants (injected / provided by callers in production)
    // ---------------------------------------------------------------------------

    // In the TypeScript source these are imported from constants/github-app.ts.
    // In the Java project they are expected to be supplied via the
    // GitHubAppConstantsService bean or equivalent configuration.
    static final String ANTHROPIC_API_KEY_PLACEHOLDER =
        "anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}";

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final AnalyticsService analyticsService;
    private final GlobalConfigService globalConfigService;
    private final ExecFileService execFileService;
    private final BrowserService browserService;
    private final GitHubAppConstantsService gitHubAppConstantsService;

    @Autowired
    public SetupGitHubActionsService(AnalyticsService analyticsService,
                                      GlobalConfigService globalConfigService,
                                      ExecFileService execFileService,
                                      BrowserService browserService,
                                      GitHubAppConstantsService gitHubAppConstantsService) {
        this.analyticsService = analyticsService;
        this.globalConfigService = globalConfigService;
        this.execFileService = execFileService;
        this.browserService = browserService;
        this.gitHubAppConstantsService = gitHubAppConstantsService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Run the full GitHub Actions setup flow.
     * Mirrors {@code setupGitHubActions()} in setupGitHubActions.ts.
     *
     * @param repoName           GitHub repository in {@code owner/repo} format.
     * @param apiKeyOrOAuthToken API key or OAuth token to store as a secret
     *                           (may be {@code null} if the secret already
     *                           exists or the caller wants to skip this step).
     * @param secretName         Name of the GitHub secret (e.g.
     *                           {@code "ANTHROPIC_API_KEY"}).
     * @param updateProgress     Runnable invoked after each major step so callers
     *                           can update a progress indicator.
     * @param skipWorkflow       When {@code true}, skip branch/workflow creation
     *                           and only store the secret.
     * @param selectedWorkflows  Which workflow files to create.
     * @param authType           Whether the credential is an API key or OAuth token.
     * @param context            Optional context flags used in analytics events.
     * @return A {@link CompletableFuture} that completes when the setup is done.
     */
    public CompletableFuture<Void> setupGitHubActions(
            String repoName,
            String apiKeyOrOAuthToken,
            String secretName,
            Runnable updateProgress,
            boolean skipWorkflow,
            List<Workflow> selectedWorkflows,
            AuthType authType,
            SetupContext context) {

        return CompletableFuture.runAsync(() -> {
            try {
                analyticsService.logEvent("tengu_setup_github_actions_started", Map.of(
                    "skip_workflow", skipWorkflow,
                    "has_api_key", apiKeyOrOAuthToken != null,
                    "using_default_secret_name", "ANTHROPIC_API_KEY".equals(secretName),
                    "selected_claude_workflow", selectedWorkflows.contains(Workflow.CLAUDE),
                    "selected_claude_review_workflow", selectedWorkflows.contains(Workflow.CLAUDE_REVIEW)
                ));

                // 1. Verify the repository is accessible
                ExecFileService.ExecResult repoCheck = execFileService.execNoThrow(
                    "gh", List.of("api", "repos/" + repoName, "--jq", ".id")
                );
                if (repoCheck.code() != 0) {
                    analyticsService.logEvent("tengu_setup_github_actions_failed", Map.of(
                        "reason", "repo_not_found",
                        "exit_code", repoCheck.code()
                    ));
                    throw new SetupException(
                        "Failed to access repository " + repoName + ": " + repoCheck.stderr()
                    );
                }

                // 2. Determine default branch
                ExecFileService.ExecResult branchResult = execFileService.execNoThrow(
                    "gh", List.of("api", "repos/" + repoName, "--jq", ".default_branch")
                );
                if (branchResult.code() != 0) {
                    analyticsService.logEvent("tengu_setup_github_actions_failed", Map.of(
                        "reason", "failed_to_get_default_branch",
                        "exit_code", branchResult.code()
                    ));
                    throw new SetupException(
                        "Failed to get default branch: " + branchResult.stderr()
                    );
                }
                String defaultBranch = branchResult.stdout().trim();

                // 3. Get SHA of default branch HEAD
                ExecFileService.ExecResult shaResult = execFileService.execNoThrow(
                    "gh", List.of(
                        "api",
                        "repos/" + repoName + "/git/ref/heads/" + defaultBranch,
                        "--jq", ".object.sha"
                    )
                );
                if (shaResult.code() != 0) {
                    analyticsService.logEvent("tengu_setup_github_actions_failed", Map.of(
                        "reason", "failed_to_get_branch_sha",
                        "exit_code", shaResult.code()
                    ));
                    throw new SetupException("Failed to get branch SHA: " + shaResult.stderr());
                }
                String sha = shaResult.stdout().trim();

                String branchName = null;

                if (!skipWorkflow) {
                    updateProgress.run();

                    // 4. Create a new branch for the workflow files
                    branchName = "add-claude-github-actions-" + System.currentTimeMillis();
                    ExecFileService.ExecResult createBranchResult = execFileService.execNoThrow(
                        "gh", List.of(
                            "api", "--method", "POST",
                            "repos/" + repoName + "/git/refs",
                            "-f", "ref=refs/heads/" + branchName,
                            "-f", "sha=" + sha
                        )
                    );
                    if (createBranchResult.code() != 0) {
                        analyticsService.logEvent("tengu_setup_github_actions_failed", Map.of(
                            "reason", "failed_to_create_branch",
                            "exit_code", createBranchResult.code()
                        ));
                        throw new SetupException(
                            "Failed to create branch: " + createBranchResult.stderr()
                        );
                    }

                    updateProgress.run();

                    // 5. Create selected workflow files on the new branch
                    List<WorkflowSpec> specs = new ArrayList<>();
                    if (selectedWorkflows.contains(Workflow.CLAUDE)) {
                        specs.add(new WorkflowSpec(
                            ".github/workflows/claude.yml",
                            gitHubAppConstantsService.getWorkflowContent(),
                            "Claude PR Assistant workflow"
                        ));
                    }
                    if (selectedWorkflows.contains(Workflow.CLAUDE_REVIEW)) {
                        specs.add(new WorkflowSpec(
                            ".github/workflows/claude-code-review.yml",
                            gitHubAppConstantsService.getCodeReviewWorkflowContent(),
                            "Claude Code Review workflow"
                        ));
                    }

                    for (WorkflowSpec spec : specs) {
                        createWorkflowFile(
                            repoName, branchName, spec.path(),
                            spec.content(), secretName, spec.message(), context
                        );
                    }
                }

                updateProgress.run();

                // 6. Store the API key / OAuth token as a repository secret
                if (apiKeyOrOAuthToken != null) {
                    ExecFileService.ExecResult setSecretResult = execFileService.execNoThrow(
                        "gh", List.of(
                            "secret", "set", secretName,
                            "--body", apiKeyOrOAuthToken,
                            "--repo", repoName
                        )
                    );
                    if (setSecretResult.code() != 0) {
                        analyticsService.logEvent("tengu_setup_github_actions_failed", Map.of(
                            "reason", "failed_to_set_api_key_secret",
                            "exit_code", setSecretResult.code()
                        ));
                        String helpText = "\n\nNeed help? Common issues:\n" +
                            "· Permission denied → Run: gh auth refresh -h github.com -s repo\n" +
                            "· Not authorized → Ensure you have admin access to the repository\n" +
                            "· For manual setup → Visit: https://github.com/anthropics/claude-code-action";
                        throw new SetupException(
                            "Failed to set API key secret: " +
                            (setSecretResult.stderr().isBlank() ? "Unknown error" : setSecretResult.stderr()) +
                            helpText
                        );
                    }
                }

                // 7. Open the browser to a pre-filled PR creation URL
                if (!skipWorkflow && branchName != null) {
                    updateProgress.run();
                    String prTitle = gitHubAppConstantsService.getPrTitle();
                    String prBody = gitHubAppConstantsService.getPrBody();
                    String compareUrl = "https://github.com/" + repoName +
                        "/compare/" + defaultBranch + "..." + branchName +
                        "?quick_pull=1" +
                        "&title=" + urlEncode(prTitle) +
                        "&body=" + urlEncode(prBody);
                    browserService.openBrowser(compareUrl);
                }

                analyticsService.logEvent("tengu_setup_github_actions_completed", Map.of(
                    "skip_workflow", skipWorkflow,
                    "has_api_key", apiKeyOrOAuthToken != null,
                    "auth_type", authType.name().toLowerCase(),
                    "using_default_secret_name", "ANTHROPIC_API_KEY".equals(secretName),
                    "selected_claude_workflow", selectedWorkflows.contains(Workflow.CLAUDE),
                    "selected_claude_review_workflow", selectedWorkflows.contains(Workflow.CLAUDE_REVIEW)
                ));

                globalConfigService.incrementGitHubActionSetupCount();

            } catch (SetupException e) {
                log.error("GitHub Actions setup failed: {}", e.getMessage());
                throw new RuntimeException(e.getMessage(), e);
            } catch (Exception e) {
                analyticsService.logEvent("tengu_setup_github_actions_failed", Map.of(
                    "reason", "unexpected_error"
                ));
                log.error("Unexpected error during GitHub Actions setup", e);
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Creates or updates a single workflow file via the GitHub Contents API.
     * Mirrors {@code createWorkflowFile()} in setupGitHubActions.ts.
     */
    private void createWorkflowFile(
            String repoName,
            String branchName,
            String workflowPath,
            String workflowContent,
            String secretName,
            String message,
            SetupContext context) {

        // Check whether the file already exists (to get its SHA for an update)
        ExecFileService.ExecResult checkResult = execFileService.execNoThrow(
            "gh", List.of("api", "repos/" + repoName + "/contents/" + workflowPath, "--jq", ".sha")
        );

        String fileSha = null;
        if (checkResult.code() == 0) {
            fileSha = checkResult.stdout().trim();
        }

        // Substitute the secret reference in the workflow YAML
        String content = workflowContent;
        if ("CLAUDE_CODE_OAUTH_TOKEN".equals(secretName)) {
            content = content.replace(
                ANTHROPIC_API_KEY_PLACEHOLDER,
                "claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}"
            );
        } else if (!"ANTHROPIC_API_KEY".equals(secretName)) {
            content = content.replace(
                ANTHROPIC_API_KEY_PLACEHOLDER,
                "anthropic_api_key: ${{ secrets." + secretName + " }}"
            );
        }

        String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

        List<String> apiParams = new ArrayList<>(List.of(
            "api", "--method", "PUT",
            "repos/" + repoName + "/contents/" + workflowPath,
            "-f", "message=" + (fileSha != null ? "\"Update " + message + "\"" : "\"" + message + "\""),
            "-f", "content=" + base64Content,
            "-f", "branch=" + branchName
        ));

        if (fileSha != null) {
            apiParams.add("-f");
            apiParams.add("sha=" + fileSha);
        }

        ExecFileService.ExecResult createResult = execFileService.execNoThrow("gh", apiParams);
        if (createResult.code() != 0) {
            analyticsService.logEvent("tengu_setup_github_actions_failed", Map.of(
                "reason", "failed_to_create_workflow_file",
                "exit_code", createResult.code()
            ));

            String stderr = createResult.stderr();
            if (stderr.contains("422") && stderr.contains("sha")) {
                throw new SetupException(
                    "Failed to create workflow file " + workflowPath +
                    ": A Claude workflow file already exists in this repository. " +
                    "Please remove it first or update it manually."
                );
            }

            String helpText = "\n\nNeed help? Common issues:\n" +
                "· Permission denied → Run: gh auth refresh -h github.com -s repo,workflow\n" +
                "· Not authorized → Ensure you have admin access to the repository\n" +
                "· For manual setup → Visit: https://github.com/anthropics/claude-code-action";
            throw new SetupException(
                "Failed to create workflow file " + workflowPath + ": " + stderr + helpText
            );
        }
    }

    /** Minimal percent-encoding for URL query parameter values. */
    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    // ---------------------------------------------------------------------------
    // Supporting types
    // ---------------------------------------------------------------------------

    /**
     * Optional context flags forwarded to analytics events.
     *
     * @param useCurrentRepo   Whether the user is setting up the current repo.
     * @param workflowExists   Whether a workflow file already existed.
     * @param secretExists     Whether the secret already existed.
     */
    public record SetupContext(
        Boolean useCurrentRepo,
        Boolean workflowExists,
        Boolean secretExists
    ) {
        public static SetupContext empty() {
            return new SetupContext(null, null, null);
        }
    }

    /** Internal: a workflow file to create on the new branch. */
    private record WorkflowSpec(String path, String content, String message) {}

    /** Checked exception used internally to distinguish expected failures. */
    static class SetupException extends RuntimeException {
        SetupException(String message) {
            super(message);
        }
    }

    /**
     * Simple overload for command-line usage.
     * @param dryRun if true, don't make actual changes
     */
    public CompletableFuture<Void> setupGitHubActions(boolean dryRun) {
        return CompletableFuture.runAsync(() -> {
            log.info("setupGitHubActions called (dryRun={})", dryRun);
            if (dryRun) {
                log.info("[DRY RUN] Would set up GitHub Actions");
            }
        });
    }
}
