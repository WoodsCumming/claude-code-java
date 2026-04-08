package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ClaudeInChromeSkill — activates Chrome browser automation via the
 * {@code mcp__claude-in-chrome__*} tool set.
 *
 * <p>This skill is enabled only when the Claude-in-Chrome MCP extension is
 * connected (see {@link #isEnabled()}).  Once invoked it injects the base
 * Chrome prompt together with an activation notice that instructs the model
 * to call {@code mcp__claude-in-chrome__tabs_context_mcp} first.
 *
 * <p>Translated from: src/skills/bundled/claudeInChrome.ts
 */
@Slf4j
@Service
public class ClaudeInChromeSkill {



    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "claude-in-chrome";

    public static final String DESCRIPTION =
            "Automates your Chrome browser to interact with web pages - clicking elements, "
            + "filling forms, capturing screenshots, reading console logs, and navigating sites. "
            + "Opens pages in new tabs within your existing Chrome session. "
            + "Requires site-level permissions before executing (configured in the extension).";

    public static final String WHEN_TO_USE =
            "When the user wants to interact with web pages, automate browser tasks, capture screenshots, "
            + "read console logs, or perform any browser-based actions. "
            + "Always invoke BEFORE attempting to use any mcp__claude-in-chrome__* tools.";

    // -------------------------------------------------------------------------
    // Prompt constants — mirrors BASE_CHROME_PROMPT + SKILL_ACTIVATION_MESSAGE
    // -------------------------------------------------------------------------

    /**
     * Base Chrome automation prompt.  In the TypeScript source this is imported
     * from {@code utils/claudeInChrome/prompt.ts}; here we inline the
     * structural intent as a placeholder.
     */
    private static final String BASE_CHROME_PROMPT =
            "You have access to Chrome browser automation tools via the mcp__claude-in-chrome__* tool set. "
            + "Use these tools to interact with the user's Chrome browser. "
            + "Always request necessary site permissions before executing automation actions. "
            + "Operate within the user's existing Chrome session — do not open a new browser window.";

    private static final String SKILL_ACTIVATION_MESSAGE = """

            Now that this skill is invoked, you have access to Chrome browser automation tools. \
            You can now use the mcp__claude-in-chrome__* tools to interact with web pages.

            IMPORTANT: Start by calling mcp__claude-in-chrome__tabs_context_mcp to get information \
            about the user's current browser tabs.
            """;

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the Claude-in-Chrome MCP server is available.
     * Mirrors {@code shouldAutoEnableClaudeInChrome()} from the TS source.
     *
     * <p>The real implementation would query the MCP connection registry; this
     * placeholder delegates to an environment variable for testability.
     */
    public boolean isEnabled() {
        // Check for the presence of the Claude-in-Chrome MCP server indicator.
        String indicator = System.getenv("CLAUDE_IN_CHROME_ENABLED");
        return "true".equalsIgnoreCase(indicator);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the prompt for the {@code /claude-in-chrome} command.
     *
     * @param args optional user-supplied task description
     * @return a single-element list containing the full prompt text
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder prompt = new StringBuilder();
            prompt.append(BASE_CHROME_PROMPT);
            prompt.append(SKILL_ACTIVATION_MESSAGE);

            if (args != null && !args.isBlank()) {
                prompt.append("\n## Task\n\n").append(args.strip());
            }

            return List.of(new PromptPart("text", prompt.toString()));
        });
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** Simple record representing a single prompt part sent to the model. */
    public record PromptPart(String type, String text) {}
}
