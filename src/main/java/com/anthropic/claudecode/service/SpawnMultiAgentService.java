package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.SwarmConstants;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spawn multi-agent service — the main entry point for teammate spawning.
 * Translated from src/tools/shared/spawnMultiAgent.ts
 *
 * <p>Handles spawning new Claude Code agent processes for swarm coordination.
 * In the TypeScript source three spawn strategies are supported:
 * <ul>
 *   <li>split-pane (tmux or iTerm2) — {@link #handleSpawnSplitPane}</li>
 *   <li>separate tmux window — {@link #handleSpawnSeparateWindow}</li>
 *   <li>in-process — {@link #handleSpawnInProcess}</li>
 * </ul>
 * The active strategy is selected by {@link #spawnTeammate} based on the
 * {@code isInProcessEnabled()} feature flag.</p>
 */
@Slf4j
@Service
public class SpawnMultiAgentService {



    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Result returned after a successful spawn.
     * Translated from SpawnOutput in spawnMultiAgent.ts
     */
    @Data
    @lombok.Builder
    
    public static class SpawnOutput {
        private String teammateId;
        private String agentId;
        private String agentType;        // nullable
        private String model;            // nullable
        private String name;
        private String color;            // nullable
        private String tmuxSessionName;
        private String tmuxWindowName;
        private String tmuxPaneId;
        private String teamName;         // nullable
        private boolean isSplitpane;
        private boolean planModeRequired; // nullable → false default
    }

    /**
     * Configuration for a teammate spawn request.
     * Translated from SpawnTeammateConfig in spawnMultiAgent.ts
     */
    @Data
    @lombok.Builder
    
    public static class SpawnTeammateConfig {
        private String name;
        private String prompt;
        private String teamName;          // nullable
        private String cwd;               // nullable
        private Boolean useSplitpane;     // nullable → defaults to true
        private Boolean planModeRequired; // nullable
        private String model;             // nullable; "inherit" → use leader's model
        private String agentType;         // nullable
        private String description;       // nullable
        private String invokingRequestId; // nullable
    }

    /**
     * Result wrapper for spawn operations.
     * Translated from { data: SpawnOutput } in spawnMultiAgent.ts
     */
    public record SpawnResult(SpawnOutput data) {}

    // =========================================================================
    // Model resolution
    // =========================================================================

    /**
     * Resolve a teammate model value. Handles the 'inherit' alias by
     * substituting the leader's model.
     * Translated from resolveTeammateModel() in spawnMultiAgent.ts
     *
     * @param inputModel  the model value from the spawn config (nullable)
     * @param leaderModel the currently active model of the leader agent (nullable)
     */
    public static String resolveTeammateModel(String inputModel, String leaderModel) {
        if ("inherit".equals(inputModel)) {
            return Optional.ofNullable(leaderModel)
                    .orElse(getDefaultTeammateModel(leaderModel));
        }
        return Optional.ofNullable(inputModel)
                .orElse(getDefaultTeammateModel(leaderModel));
    }

    private static String getDefaultTeammateModel(String leaderModel) {
        // In a full implementation this reads globalConfig().teammateDefaultModel
        // and falls back to a hardcoded model name.
        return Optional.ofNullable(leaderModel).orElse("claude-opus-4-5");
    }

    // =========================================================================
    // Unique name generation
    // =========================================================================

    /**
     * Generates a unique teammate name within a team by appending a numeric suffix
     * when the base name is already taken.
     * Translated from generateUniqueTeammateName() in spawnMultiAgent.ts
     *
     * @param baseName    the desired name (e.g. "tester")
     * @param existingNames set of already-used lowercase names in the team
     */
    public static String generateUniqueTeammateName(String baseName, java.util.Set<String> existingNames) {
        if (existingNames == null || !existingNames.contains(baseName.toLowerCase())) {
            return baseName;
        }
        int suffix = 2;
        while (existingNames.contains((baseName + "-" + suffix).toLowerCase())) {
            suffix++;
        }
        return baseName + "-" + suffix;
    }

    // =========================================================================
    // Spawn handlers (stub implementations — real logic requires tmux/it2 APIs)
    // =========================================================================

    /**
     * Spawn a teammate in a split-pane view (tmux or iTerm2).
     * Translated from handleSpawnSplitPane() in spawnMultiAgent.ts
     */
    private CompletableFuture<SpawnResult> handleSpawnSplitPane(SpawnTeammateConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[spawn:split-pane] name={} team={} model={}", config.getName(), config.getTeamName(), config.getModel());
            String teammateId = buildTeammateId(config.getName(), config.getTeamName());
            String color = assignColor(teammateId);
            String paneId = "%" + UUID.randomUUID().toString().substring(0, 8);

            return new SpawnResult(SpawnOutput.builder()
                    .teammateId(teammateId)
                    .agentId(teammateId)
                    .agentType(config.getAgentType())
                    .model(config.getModel())
                    .name(sanitizeAgentName(config.getName()))
                    .color(color)
                    .tmuxSessionName("current")
                    .tmuxWindowName("current")
                    .tmuxPaneId(paneId)
                    .teamName(config.getTeamName())
                    .isSplitpane(true)
                    .planModeRequired(Boolean.TRUE.equals(config.getPlanModeRequired()))
                    .build());
        });
    }

    /**
     * Spawn a teammate in a separate tmux window (legacy behavior).
     * Translated from handleSpawnSeparateWindow() in spawnMultiAgent.ts
     */
    private CompletableFuture<SpawnResult> handleSpawnSeparateWindow(SpawnTeammateConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[spawn:separate-window] name={} team={}", config.getName(), config.getTeamName());
            String sanitized = sanitizeAgentName(config.getName());
            String teammateId = buildTeammateId(sanitized, config.getTeamName());
            String windowName = "teammate-" + sanitize(sanitized);
            String paneId = "%" + UUID.randomUUID().toString().substring(0, 8);

            return new SpawnResult(SpawnOutput.builder()
                    .teammateId(teammateId)
                    .agentId(teammateId)
                    .agentType(config.getAgentType())
                    .model(config.getModel())
                    .name(sanitized)
                    .color(assignColor(teammateId))
                    .tmuxSessionName(SwarmConstants.SWARM_SESSION_NAME)
                    .tmuxWindowName(windowName)
                    .tmuxPaneId(paneId)
                    .teamName(config.getTeamName())
                    .isSplitpane(false)
                    .planModeRequired(Boolean.TRUE.equals(config.getPlanModeRequired()))
                    .build());
        });
    }

    /**
     * Spawn a teammate in-process (same JVM).
     * Translated from handleSpawnInProcess() in spawnMultiAgent.ts
     */
    private CompletableFuture<SpawnResult> handleSpawnInProcess(SpawnTeammateConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("[spawn:in-process] name={} team={}", config.getName(), config.getTeamName());
            String sanitized = sanitizeAgentName(config.getName());
            String teammateId = buildTeammateId(sanitized, config.getTeamName());

            return new SpawnResult(SpawnOutput.builder()
                    .teammateId(teammateId)
                    .agentId(teammateId)
                    .agentType(config.getAgentType())
                    .model(config.getModel())
                    .name(sanitized)
                    .color(assignColor(teammateId))
                    .tmuxSessionName("in-process")
                    .tmuxWindowName("in-process")
                    .tmuxPaneId("in-process")
                    .teamName(config.getTeamName())
                    .isSplitpane(false)
                    .planModeRequired(Boolean.TRUE.equals(config.getPlanModeRequired()))
                    .build());
        });
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Spawns a new teammate with the given configuration.
     * This is the main entry point used by both TeammateTool and AgentTool.
     * Translated from spawnTeammate() in spawnMultiAgent.ts
     *
     * <p>Strategy selection:
     * <ol>
     *   <li>If {@code IN_PROCESS_ENABLED} env var is set, use in-process mode.</li>
     *   <li>Else if {@code use_splitpane} is not {@code false}, use split-pane.</li>
     *   <li>Otherwise use a separate tmux window.</li>
     * </ol>
     * </p>
     */
    public CompletableFuture<SpawnResult> spawnTeammate(SpawnTeammateConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("name is required for spawn operation"));
        }
        if (config.getPrompt() == null || config.getPrompt().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("prompt is required for spawn operation"));
        }
        if (config.getTeamName() == null || config.getTeamName().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "team_name is required for spawn operation. Call spawnTeam first to establish team context."));
        }

        boolean inProcessEnabled = "true".equalsIgnoreCase(System.getenv("IN_PROCESS_ENABLED"));
        if (inProcessEnabled) {
            return handleSpawnInProcess(config);
        }
        boolean useSplitPane = !Boolean.FALSE.equals(config.getUseSplitpane()); // default true
        if (useSplitPane) {
            return handleSpawnSplitPane(config);
        }
        return handleSpawnSeparateWindow(config);
    }

    /**
     * Get the teammate command.
     * Translated from getTeammateCommand() in spawnMultiAgent.ts
     */
    public String getTeammateCommand() {
        String override = System.getenv(SwarmConstants.TEAMMATE_COMMAND_ENV_VAR);
        if (override != null && !override.isBlank()) return override;
        return ProcessHandle.current().info().command().orElse("claude");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Format {@code agentName@teamName} agent ID. */
    private static String buildTeammateId(String agentName, String teamName) {
        return agentName + "@" + (teamName != null ? teamName : "default");
    }

    /** Strip the '@' character from agent names to prevent ID format corruption. */
    private static String sanitizeAgentName(String name) {
        return name == null ? "" : name.replace("@", "");
    }

    /** Convert a name to a tmux-safe slug (lowercase, dashes only). */
    private static String sanitize(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    /** Assign a deterministic colour slot (maps to a ANSI colour index). */
    private static String assignColor(String teammateId) {
        // Simple deterministic colour from hash — mirrors assignTeammateColor() in TypeScript
        String[] colors = {"cyan", "magenta", "yellow", "blue", "green", "red"};
        int idx = Math.abs(teammateId.hashCode()) % colors.length;
        return colors[idx];
    }
}
