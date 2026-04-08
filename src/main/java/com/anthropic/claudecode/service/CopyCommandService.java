package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Copy command service — models the '/copy' slash-command metadata and
 * delegates clipboard operations to the underlying copy implementation.
 * Translated from src/commands/copy/index.ts
 *
 * <p>The TypeScript source registers a lazy-loaded local-jsx command with the
 * name "copy". It copies Claude's last response to the clipboard, or the
 * Nth-latest response when an index argument is provided (e.g. {@code /copy 2}).
 */
@Slf4j
@Service
public class CopyCommandService {



    /** Command name. Translated from: name: 'copy' in index.ts */
    public static final String NAME = "copy";

    /**
     * Command description.
     * Translated from: description in index.ts
     */
    public static final String DESCRIPTION =
        "Copy Claude's last response to clipboard (or /copy N for the Nth-latest)";

    /** Command type. Translated from: type: 'local-jsx' in index.ts */
    public static final String TYPE = "local-jsx";

    private final SessionHistoryService sessionHistoryService;

    @Autowired
    public CopyCommandService(SessionHistoryService sessionHistoryService) {
        this.sessionHistoryService = sessionHistoryService;
    }

    /**
     * Copy the Nth-latest Claude response to the system clipboard.
     * Translated from the lazy load of copy.js (copy.tsx) in index.ts
     *
     * <p>Passing {@code n = 1} (the default) copies the most recent response.
     *
     * @param n 1-based index of the response to copy; must be &gt;= 1
     * @return a future that resolves to the copied text, or an empty string if
     *         no matching response was found
     */
    public CompletableFuture<String> copy(int n) {
        if (n < 1) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Response index must be >= 1 (got " + n + ")"));
        }
        log.info("Copying response #{} to clipboard", n);
        return sessionHistoryService.getNthLatestAssistantResponse(n)
            .thenApply(responseOpt -> {
                if (responseOpt.isEmpty()) {
                    log.warn("No response found at index {}", n);
                    return "";
                }
                String text = responseOpt.get();
                copyToClipboard(text);
                return text;
            });
    }

    /**
     * Copy the last Claude response to the clipboard (shorthand for {@code copy(1)}).
     */
    public CompletableFuture<String> copy() {
        return copy(1);
    }

    /**
     * Write text to the system clipboard.
     * Mirrors the clipboard write in copy.tsx.
     */
    private void copyToClipboard(String text) {
        try {
            java.awt.datatransfer.Clipboard clipboard =
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            java.awt.datatransfer.StringSelection selection =
                new java.awt.datatransfer.StringSelection(text);
            clipboard.setContents(selection, selection);
            log.debug("Copied {} chars to clipboard", text.length());
        } catch (Exception e) {
            log.warn("Failed to copy to clipboard: {}", e.getMessage());
        }
    }
}
