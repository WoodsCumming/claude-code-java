package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Command descriptor for the /tag command.
 * Translated from src/commands/tag/index.ts
 *
 * <p>Toggles a searchable tag on the current session. This command is only
 * enabled for internal Anthropic employees ({@code USER_TYPE=ant}).
 */
@Slf4j
@Service
public class TagCommandService {



    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "tag";
    public static final String DESCRIPTION = "Toggle a searchable tag on the current session";
    public static final String TYPE = "local-jsx";
    public static final String ARGUMENT_HINT = "<tag-name>";

    // -------------------------------------------------------------------------
    // Command result type
    // -------------------------------------------------------------------------

    /** Mirrors the TypeScript {@code LocalCommandResult} union. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // Dynamic command properties
    // -------------------------------------------------------------------------

    /**
     * Returns whether this command is currently enabled.
     * Translated from the {@code isEnabled()} arrow function in tag/index.ts
     *
     * <p>The command is only available to internal Anthropic employees
     * ({@code USER_TYPE} environment variable set to {@code "ant"}).
     */
    public boolean isEnabled() {
        return "ant".equals(System.getenv("USER_TYPE"));
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute the /tag command.
     * In the TypeScript source the actual rendering is deferred to tag.js (JSX).
     * Here we return a text result indicating the tag toggle view should be launched.
     *
     * @param tagName the tag name to toggle on the current session (may be null/blank)
     */
    public CommandResult call(String tagName) {
        if (!isEnabled()) {
            log.warn("[tag] Command invoked but USER_TYPE is not 'ant'");
            return new CommandResult.TextResult(
                    "The /tag command is only available for internal Anthropic users.");
        }

        if (tagName == null || tagName.isBlank()) {
            log.info("[tag] No tag name provided; launching tag picker view");
            return new CommandResult.TextResult(
                    "Please provide a tag name. Usage: /tag <tag-name>");
        }

        log.info("[tag] Toggling tag '{}' on current session", tagName);
        return new CommandResult.TextResult(
                "Toggling tag '" + tagName + "' on the current session.");
    }

    /**
     * Execute the /tag command with no arguments — opens the tag picker view.
     */
    public CommandResult call() {
        return call(null);
    }
}
