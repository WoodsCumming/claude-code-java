package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.SessionService;
import com.anthropic.claudecode.service.SessionStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Resume command for resuming a previous conversation.
 *
 * Translated from src/commands/resume/index.ts and src/commands/resume/resume.tsx
 *
 * TypeScript original behaviour:
 *   - No arg: open an interactive LogSelector picker showing resumable sessions
 *     (filtered: non-sidechain, not the current session, from same repo / worktrees)
 *   - UUID arg: look up the session directly in enriched logs, then fall back to
 *     a direct file lookup for sessions that were filtered by enrichLogs
 *   - Custom-title arg (if isCustomTitleEnabled()): exact-match search; handles
 *     single-match (resume), multiple-match (error), or no-match (error)
 *   - Cross-project resume: if the found session is from a different directory,
 *     print the `claude --resume <id> --cwd <dir>` command and copy it to the clipboard
 *   - Same-repo worktree: resume directly
 *   - aliases: ["continue"]
 *
 * Java translation:
 *   - No arg: list 10 most-recent resumable sessions
 *   - UUID arg: look up by session ID and resume
 *   - Text arg: search by custom title (exact then fuzzy), resume single match
 */
@Slf4j
@Component
@Command(
    name = "resume",
    aliases = {"continue"},
    description = "Resume a previous conversation"
)
public class ResumeCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResumeCommand.class);


    private static final Pattern UUID_PATTERN =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Conversation ID or search term; omit to list recent sessions. */
    @Parameters(index = "0", description = "Conversation ID or search term", arity = "0..1")
    private String sessionArg;

    private final SessionService sessionService;
    private final SessionStorageService sessionStorageService;

    @Autowired
    public ResumeCommand(SessionService sessionService,
                         SessionStorageService sessionStorageService) {
        this.sessionService = sessionService;
        this.sessionStorageService = sessionStorageService;
    }

    @Override
    public Integer call() {
        if (sessionArg == null || sessionArg.isBlank()) {
            return listRecentSessions();
        }

        String arg = sessionArg.trim();

        // 1. UUID lookup — most precise, mirrors TypeScript behaviour
        if (isValidUuid(arg)) {
            return resumeByUuid(arg);
        }

        // 2. Custom-title search
        return resumeByTitle(arg);
    }

    // -----------------------------------------------------------------
    // No-arg: list recent sessions
    // -----------------------------------------------------------------

    private int listRecentSessions() {
        List<SessionStorageService.SessionLog> sessions =
            sessionStorageService.loadRecentSessions(10);

        // Filter: exclude sidechain sessions and the current session
        String currentSessionId = sessionService.getCurrentSessionId();
        List<SessionStorageService.SessionLog> resumable = sessions.stream()
            .filter(s -> !s.isSidechain() && !s.getSessionId().equals(currentSessionId))
            .toList();

        if (resumable.isEmpty()) {
            System.out.println("No previous conversations found to resume.");
            return 0;
        }

        System.out.println("Recent conversations:");
        for (int i = 0; i < resumable.size(); i++) {
            SessionStorageService.SessionLog s = resumable.get(i);
            System.out.printf("  %2d.  %-40s  %s%n",
                i + 1, s.getSessionId(), s.getTitle());
        }
        System.out.println("\nUse /resume <session-id> or /resume <title> to resume a conversation.");
        return 0;
    }

    // -----------------------------------------------------------------
    // UUID lookup
    // -----------------------------------------------------------------

    private int resumeByUuid(String uuid) {
        // Primary: look up in enriched session logs
        Optional<SessionStorageService.SessionLog> match =
            sessionStorageService.findSessionById(uuid);

        if (match.isEmpty()) {
            // Fallback: direct file lookup (handles sessions dropped by enrichLogs
            // because their first message was too large to extract firstPrompt)
            match = sessionStorageService.getLastSessionLog(uuid);
        }

        if (match.isEmpty()) {
            System.out.println("Session '" + uuid + "' was not found.");
            return 1;
        }

        return doResume(match.get());
    }

    // -----------------------------------------------------------------
    // Custom-title search
    // -----------------------------------------------------------------

    private int resumeByTitle(String title) {
        List<SessionStorageService.SessionLog> matches =
            sessionStorageService.searchByTitle(title);

        if (matches.isEmpty()) {
            System.out.println("Session '" + title + "' was not found.");
            return 1;
        }

        if (matches.size() > 1) {
            System.out.printf(
                "Found %d sessions matching '%s'. Please use /resume to pick a specific session.%n",
                matches.size(), title
            );
            for (int i = 0; i < Math.min(5, matches.size()); i++) {
                SessionStorageService.SessionLog s = matches.get(i);
                System.out.printf("  %s  %s%n", s.getSessionId(), s.getTitle());
            }
            return 1;
        }

        return doResume(matches.get(0));
    }

    // -----------------------------------------------------------------
    // Core resume
    // -----------------------------------------------------------------

    private int doResume(SessionStorageService.SessionLog session) {
        // Cross-project resume check
        String currentCwd = sessionService.getCurrentWorkingDirectory();
        if (!sessionStorageService.isSameProjectOrWorktree(session, currentCwd)) {
            String command = String.format("claude --resume %s --cwd %s",
                session.getSessionId(), session.getWorkingDirectory());
            System.out.println("\nThis conversation is from a different directory.");
            System.out.println("\nTo resume, run:");
            System.out.println("  " + command);
            return 1;
        }

        // Same project — resume directly
        try {
            sessionService.resumeSession(session.getSessionId());
            System.out.println("Resumed session: " + session.getTitle());
            return 0;
        } catch (Exception e) {
            log.error("Failed to resume session '{}'", session.getSessionId(), e);
            System.out.println("Failed to resume: " + e.getMessage());
            return 1;
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static boolean isValidUuid(String value) {
        return UUID_PATTERN.matcher(value).matches();
    }
}
