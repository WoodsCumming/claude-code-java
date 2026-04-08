package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Command descriptor for the /mobile command.
 * Translated from src/commands/mobile/index.ts
 *
 * Shows a QR code to download the Claude mobile app.
 * Aliases: /ios, /android
 */
@Slf4j
@Service
public class MobileCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MobileCommandService.class);


    // -------------------------------------------------------------------------
    // Command metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "mobile";
    public static final List<String> ALIASES = List.of("ios", "android");
    public static final String DESCRIPTION = "Show QR code to download the Claude mobile app";
    public static final String TYPE = "local-jsx";

    // -------------------------------------------------------------------------
    // Command result type
    // -------------------------------------------------------------------------

    /** Mirrors the TypeScript {@code LocalCommandResult} union. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the command name. */
    public String getName() {
        return NAME;
    }

    /** Returns the command aliases. */
    public List<String> getAliases() {
        return ALIASES;
    }

    /** Returns the command description. */
    public String getDescription() {
        return DESCRIPTION;
    }

    /** Returns the command type. */
    public String getType() {
        return TYPE;
    }

    /**
     * Execute the /mobile command.
     * In the TypeScript source the actual rendering is deferred to mobile.js (JSX).
     * Here we return a text result indicating that the mobile QR code view should be launched.
     */
    public CommandResult call() {
        log.info("[mobile] Launching mobile QR code view");
        return new CommandResult.TextResult(
                "Opening mobile download view. Scan the QR code to download the Claude mobile app.");
    }
}
