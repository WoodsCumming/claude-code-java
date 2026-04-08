package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * /export slash-command descriptor.
 * Translated from src/commands/export/index.ts
 *
 * The TypeScript source is a thin command-registration module that declares
 * the {@code export} command metadata and lazily loads the JSX panel.
 * In the Java project the export rendering is handled by a separate view
 * layer; this service exposes the command metadata and serves as the
 * Spring-managed entry point.
 */
@Slf4j
@Service
public class ExportCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExportCommandService.class);


    // ---------------------------------------------------------------------------
    // Command metadata (mirrors the Command object in export/index.ts)
    // ---------------------------------------------------------------------------

    /** Slash-command name. */
    public static final String NAME = "export";

    /** Short description displayed in the help listing. */
    public static final String DESCRIPTION =
        "Export the current conversation to a file or clipboard";

    /** Optional argument hint shown next to the command in help output. */
    public static final String ARGUMENT_HINT = "[filename]";

    /** Command type — rendered as a JSX/local-jsx panel in the TUI. */
    public static final String TYPE = "local-jsx";

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** Returns the command name. */
    public String getName() {
        return NAME;
    }

    /** Returns the human-readable description for the /export command. */
    public String getDescription() {
        return DESCRIPTION;
    }

    /** Returns the argument hint for the /export command. */
    public String getArgumentHint() {
        return ARGUMENT_HINT;
    }

    /** Returns the command type identifier. */
    public String getType() {
        return TYPE;
    }
}
