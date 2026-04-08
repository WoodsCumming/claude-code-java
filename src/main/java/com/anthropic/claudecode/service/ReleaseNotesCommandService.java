package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Command service for displaying release notes.
 * Translated from src/commands/release-notes/release-notes.ts
 *
 * Fetches the latest changelog (with a 500 ms timeout), falls back to the
 * cached copy, and formats the result as plain text.
 */
@Slf4j
@Service
public class ReleaseNotesCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReleaseNotesCommandService.class);


    private final ReleaseNotesService releaseNotesService;

    @Autowired
    public ReleaseNotesCommandService(ReleaseNotesService releaseNotesService) {
        this.releaseNotesService = releaseNotesService;
    }

    // -------------------------------------------------------------------------
    // Command result types  (mirrors LocalCommandResult from TypeScript)
    // -------------------------------------------------------------------------

    /** Sealed hierarchy matching the TypeScript {@code LocalCommandResult} union. */
    public sealed interface CommandResult permits CommandResult.TextResult, CommandResult.SkipResult {
        record TextResult(String value) implements CommandResult {}
        record SkipResult() implements CommandResult {}
    }

    // -------------------------------------------------------------------------
    // call()
    // -------------------------------------------------------------------------

    /**
     * Execute the /release-notes command.
     * Translated from call() in release-notes.ts
     *
     * <ol>
     *   <li>Try to fetch fresh notes within 500 ms.</li>
     *   <li>If that succeeds, format and return them.</li>
     *   <li>Otherwise fall back to the cached copy.</li>
     *   <li>If nothing is available, return the changelog URL.</li>
     * </ol>
     */
    public CompletableFuture<CommandResult> call() {
        return CompletableFuture.supplyAsync(() -> {
            // Attempt a fresh fetch with a 500 ms timeout
            List<Map.Entry<String, List<String>>> freshNotes = List.of();
            try {
                releaseNotesService.fetchAndStoreChangelog()
                        .get(500, TimeUnit.MILLISECONDS);
                String freshChangelog = releaseNotesService.getStoredChangelog();
                freshNotes = releaseNotesService.getAllReleaseNotes(freshChangelog);
            } catch (TimeoutException e) {
                log.debug("[release-notes] Fetch timed out — using cached notes");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("[release-notes] Fetch interrupted — using cached notes");
            } catch (Exception e) {
                log.debug("[release-notes] Fetch failed — using cached notes: {}", e.getMessage());
            }

            // Fresh notes available
            if (!freshNotes.isEmpty()) {
                return new CommandResult.TextResult(formatReleaseNotes(freshNotes));
            }

            // Fall back to cached notes
            String cachedChangelog = releaseNotesService.getStoredChangelog();
            List<Map.Entry<String, List<String>>> cachedNotes =
                    releaseNotesService.getAllReleaseNotes(cachedChangelog);
            if (!cachedNotes.isEmpty()) {
                return new CommandResult.TextResult(formatReleaseNotes(cachedNotes));
            }

            // Nothing available — show the link
            return new CommandResult.TextResult(
                    "See the full changelog at: " + ReleaseNotesService.CHANGELOG_URL);
        });
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    /**
     * Format a list of (version, notes) pairs as a human-readable string.
     * Translated from formatReleaseNotes() in release-notes.ts
     */
    private String formatReleaseNotes(List<Map.Entry<String, List<String>>> notes) {
        return notes.stream()
                .map(entry -> {
                    String header = "Version " + entry.getKey() + ":";
                    String bullets = entry.getValue().stream()
                            .map(note -> "\u00b7 " + note)
                            .collect(Collectors.joining("\n"));
                    return header + "\n" + bullets;
                })
                .collect(Collectors.joining("\n\n"));
    }
}
