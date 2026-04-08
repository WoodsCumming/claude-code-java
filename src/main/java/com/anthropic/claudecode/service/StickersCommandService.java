package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Command service for the /stickers command.
 * Translated from src/commands/stickers/stickers.ts
 *
 * <p>Opens the Claude Code sticker page in the default browser.
 * If the browser cannot be opened, a fallback message with the URL is returned.
 */
@Slf4j
@Service
public class StickersCommandService {



    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    public static final String STICKER_URL = "https://www.stickermule.com/claudecode";

    // -------------------------------------------------------------------------
    // Command result type
    // -------------------------------------------------------------------------

    /** Mirrors the TypeScript {@code LocalCommandResult} union. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final BrowserService browserService;

    @Autowired
    public StickersCommandService(BrowserService browserService) {
        this.browserService = browserService;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Execute the /stickers command.
     * Translated from {@code call()} in stickers.ts
     *
     * <p>Attempts to open the sticker page in the system default browser.
     * Returns a success message when the browser opens, or a fallback URL message
     * when it cannot be launched.
     *
     * @return a {@link CompletableFuture} resolving to the {@link CommandResult}
     */
    public CompletableFuture<CommandResult> call() {
        return CompletableFuture.supplyAsync(() -> {
            boolean success = browserService.openBrowser(STICKER_URL);
            if (success) {
                log.info("[stickers] Browser opened successfully for URL: {}", STICKER_URL);
                return new CommandResult.TextResult("Opening sticker page in browser\u2026");
            } else {
                log.warn("[stickers] Failed to open browser for URL: {}", STICKER_URL);
                return new CommandResult.TextResult(
                        "Failed to open browser. Visit: " + STICKER_URL);
            }
        });
    }
}
