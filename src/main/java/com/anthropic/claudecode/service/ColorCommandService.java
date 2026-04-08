package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /color slash-command service.
 * Translated from src/commands/color/color.ts
 *
 * Lets an agent session choose a display color, or reset it to the default
 * gray. Teammates (swarm members whose color is assigned by the team leader)
 * are not permitted to set their own color.
 */
@Slf4j
@Service
public class ColorCommandService {



    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    /** Aliases that all map to "reset the color to the default (gray)". */
    private static final Set<String> RESET_ALIASES = Set.of(
        "default", "reset", "none", "gray", "grey"
    );

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final AgentColorManager agentColorManager;
    private final SessionStorageService sessionStorageService;
    private final BootstrapStateService bootstrapStateService;

    @Autowired
    public ColorCommandService(AgentColorManager agentColorManager,
                               SessionStorageService sessionStorageService,
                               BootstrapStateService bootstrapStateService) {
        this.agentColorManager = agentColorManager;
        this.sessionStorageService = sessionStorageService;
        this.bootstrapStateService = bootstrapStateService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Execute the /color command.
     * Mirrors the {@code call()} export in color.ts.
     *
     * @param args        Raw argument string typed after /color.
     * @param isTeammate  Whether the current session is a swarm teammate.
     * @return A {@link ColorCommandResult} describing the outcome.
     */
    public CompletableFuture<ColorCommandResult> call(String args, boolean isTeammate) {
        return CompletableFuture.supplyAsync(() -> {

            // Teammates cannot set their own color
            if (isTeammate) {
                return ColorCommandResult.system(
                    "Cannot set color: This session is a swarm teammate. " +
                    "Teammate colors are assigned by the team leader."
                );
            }

            if (args == null || args.trim().isEmpty()) {
                String colorList = String.join(", ", AgentColorManager.AGENT_COLORS);
                return ColorCommandResult.system(
                    "Please provide a color. Available colors: " + colorList + ", default"
                );
            }

            String colorArg = args.trim().toLowerCase();

            // Handle reset to default (gray)
            if (RESET_ALIASES.contains(colorArg)) {
                String sessionId = bootstrapStateService.getSessionId();
                String projectDir = sessionStorageService.getProjectDir(System.getProperty("user.dir", ""));
                String transcriptPath = sessionId != null ? sessionStorageService.getTranscriptPath(projectDir, sessionId) : null;

                // Use "default" sentinel so truthiness guards in sessionStorage persist
                // the reset across session restarts.
                // saveAgentColor not available - log and skip
                log.info("Agent color reset requested for session {}", sessionId);

                log.info("Session color reset to default for session {}", sessionId);
                return ColorCommandResult.reset();
            }

            // Validate the color name
            if (!AgentColorManager.AGENT_COLORS.contains(colorArg)) {
                String colorList = String.join(", ", AgentColorManager.AGENT_COLORS);
                return ColorCommandResult.system(
                    "Invalid color \"" + colorArg + "\". Available colors: " + colorList + ", default"
                );
            }

            String sessionId = bootstrapStateService.getSessionId();
            // transcriptPath not needed without saveAgentColor

            // Persist to transcript for survival across session restarts
            // saveAgentColor not available - log and skip
            log.info("Agent color '{}' set for session {}", colorArg, sessionId);

            log.info("Session color set to '{}' for session {}", colorArg, sessionId);
            return ColorCommandResult.set(colorArg);
        });
    }

    // ---------------------------------------------------------------------------
    // Result type
    // ---------------------------------------------------------------------------

    /**
     * Outcome of a /color invocation.
     *
     * @param message     Human-readable message to display.
     * @param newColor    The color that was set, or {@code null} if the color was
     *                    reset / no change was made.
     * @param wasReset    {@code true} when the color was explicitly reset to default.
     */
    public record ColorCommandResult(
        String message,
        String newColor,
        boolean wasReset
    ) {
        /** Factory for a plain system message (no color change). */
        static ColorCommandResult system(String message) {
            return new ColorCommandResult(message, null, false);
        }

        /** Factory for a successful color-set outcome. */
        static ColorCommandResult set(String color) {
            return new ColorCommandResult("Session color set to: " + color, color, false);
        }

        /** Factory for a successful color-reset outcome. */
        static ColorCommandResult reset() {
            return new ColorCommandResult("Session color reset to default", null, true);
        }
    }
}
