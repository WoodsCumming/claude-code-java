package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * /diff slash-command descriptor.
 * Translated from src/commands/diff/index.ts
 *
 * The TypeScript source is a thin command-registration module that declares
 * the {@code diff} command metadata and lazily loads the JSX implementation.
 * In the Java project the diff rendering is handled by a separate view layer;
 * this service exposes the command metadata and acts as the Spring-managed
 * entry point for the command.
 */
@Slf4j
@Service
public class DiffCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DiffCommandService.class);


    // ---------------------------------------------------------------------------
    // Command metadata (mirrors the Command object in diff/index.ts)
    // ---------------------------------------------------------------------------

    /** Slash-command name. */
    public static final String NAME = "diff";

    /** Short description displayed in the help listing. */
    public static final String DESCRIPTION = "View uncommitted changes and per-turn diffs";

    /** Command type — rendered as a JSX/local-jsx panel in the TUI. */
    public static final String TYPE = "local-jsx";

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** Returns the command name. */
    public String getName() {
        return NAME;
    }

    /** Returns the human-readable description for the /diff command. */
    public String getDescription() {
        return DESCRIPTION;
    }

    /** Returns the command type identifier. */
    public String getType() {
        return TYPE;
    }
}
