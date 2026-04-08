package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Command service for the /pr-comments command.
 * Translated from src/commands/pr_comments/index.ts and
 * src/commands/createMovedToPluginCommand.ts
 *
 * <p>This command has been moved to a plugin. For internal (ant) users it
 * instructs the user to install the plugin; for external users it falls back to
 * a built-in prompt that fetches and formats GitHub PR comments via the {@code gh}
 * CLI.
 */
@Slf4j
@Service
public class PrCommentsCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PrCommentsCommandService.class);


    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "pr-comments";
    public static final String DESCRIPTION = "Get comments from a GitHub pull request";
    public static final String PROGRESS_MESSAGE = "fetching PR comments";
    public static final String TYPE = "prompt";
    public static final String PLUGIN_NAME = "pr-comments";
    public static final String PLUGIN_COMMAND = "pr-comments";

    // -------------------------------------------------------------------------
    // Content block / result types
    // -------------------------------------------------------------------------

    /**
     * A single text content block — mirrors {@code ContentBlockParam} in the SDK.
     */
    public record TextContentBlock(String type, String text) {
        public TextContentBlock(String text) {
            this("text", text);
        }
    }

    /** Mirrors the TypeScript {@code LocalCommandResult} union. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Build the prompt content blocks for the /pr-comments command.
     * Translated from {@code getPromptForCommand()} in createMovedToPluginCommand.ts
     *
     * @param args   optional additional user arguments passed to the command
     * @param isAnt  {@code true} when the current user is an internal Anthropic employee
     * @return a {@link CompletableFuture} resolving to the list of content blocks
     */
    public CompletableFuture<List<TextContentBlock>> getPromptForCommand(String args, boolean isAnt) {
        return CompletableFuture.supplyAsync(() -> {
            if (isAnt) {
                // Internal users: instruct them to install the plugin
                String text = """
                        This command has been moved to a plugin. Tell the user:

                        1. To install the plugin, run:
                           claude plugin install pr-comments@claude-code-marketplace

                        2. After installation, use /pr-comments:pr-comments to run this command

                        3. For more information, see: https://github.com/anthropics/claude-code-marketplace/blob/main/pr-comments/README.md

                        Do not attempt to run the command. Simply inform the user about the plugin installation.""";
                log.info("[pr-comments] Routing internal user to plugin installation instructions");
                return List.of(new TextContentBlock(text));
            }

            // External users: built-in fallback prompt
            String additionalInput = (args != null && !args.isBlank())
                    ? "\nAdditional user input: " + args
                    : "";

            String text = """
                    You are an AI assistant integrated into a git-based version control system. \
Your task is to fetch and display comments from a GitHub pull request.

Follow these steps:

1. Use `gh pr view --json number,headRepository` to get the PR number and repository info
2. Use `gh api /repos/{owner}/{repo}/issues/{number}/comments` to get PR-level comments
3. Use `gh api /repos/{owner}/{repo}/pulls/{number}/comments` to get review comments. \
Pay particular attention to the following fields: `body`, `diff_hunk`, `path`, `line`, etc. \
If the comment references some code, consider fetching it using eg \
`gh api /repos/{owner}/{repo}/contents/{path}?ref={branch} | jq .content -r | base64 -d`
4. Parse and format all comments in a readable way
5. Return ONLY the formatted comments, with no additional text

Format the comments as:

## Comments

[For each comment thread:]
- @author file.ts#line:
  ```diff
  [diff_hunk from the API response]
  ```
  > quoted comment text

  [any replies indented]

If there are no comments, return "No comments found."

Remember:
1. Only show the actual comments, no explanatory text
2. Include both PR-level and code review comments
3. Preserve the threading/nesting of comment replies
4. Show the file and line number context for code review comments
5. Use jq to parse the JSON responses from the GitHub API
""" + additionalInput;

            log.info("[pr-comments] Building fallback PR comments prompt for external user");
            return List.of(new TextContentBlock(text));
        });
    }

    /**
     * Convenience overload that reads {@code USER_TYPE} from the environment.
     *
     * @param args optional additional user arguments
     * @return a {@link CompletableFuture} resolving to the list of content blocks
     */
    public CompletableFuture<List<TextContentBlock>> getPromptForCommand(String args) {
        boolean isAnt = "ant".equals(System.getenv("USER_TYPE"));
        return getPromptForCommand(args, isAnt);
    }
}
