package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.SwarmBackendTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pane-backend executor adapter.
 * Translated from src/utils/swarm/backends/PaneBackendExecutor.ts
 *
 * Adapts pane-based backends (TmuxBackendService / ITermBackendService) to the
 * TeammateExecutor abstraction so that the rest of the application can interact
 * with any backend through a single interface.
 *
 * Responsibilities:
 *   - spawn()        : Creates a pane and sends the Claude CLI command to it
 *   - sendMessage()  : Writes to the teammate's file-based mailbox
 *   - terminate()    : Sends a shutdown request via mailbox
 *   - kill()         : Kills the pane via the backend
 *   - isActive()     : Checks if the pane is still tracked
 */
@Slf4j
@Service
public class PaneBackendExecutorService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaneBackendExecutorService.class);


    /**
     * Tracks a spawned teammate's pane information.
     */
    private record TeammateInfo(String paneId, boolean insideTmux) {}

    /**
     * Represents a message to a teammate (mirrors TeammateMessage in types.ts).
     */
    public record TeammateMessage(String text, String from, String color, String timestamp) {
        public TeammateMessage(String text, String from) {
            this(text, from, null, Instant.now().toString());
        }
    }

    /**
     * Configuration for spawning a teammate (mirrors TeammateSpawnConfig in types.ts).
     */
    public record TeammateSpawnConfig(
            String name,
            String teamName,
            String prompt,
            String cwd,
            String model,
            String color,
            String parentSessionId,
            boolean planModeRequired,
            List<String> permissions,
            boolean allowPermissionPrompts
    ) {}

    /**
     * Result of spawning a teammate (mirrors TeammateSpawnResult in types.ts).
     */
    public record TeammateSpawnResult(
            boolean success,
            String agentId,
            String paneId,
            String error
    ) {
        public static TeammateSpawnResult success(String agentId, String paneId) {
            return new TeammateSpawnResult(true, agentId, paneId, null);
        }

        public static TeammateSpawnResult failure(String agentId, String error) {
            return new TeammateSpawnResult(false, agentId, null, error);
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /**
     * Backend type served by this executor.
     * Determined at construction time from whichever backend is injected.
     */
    private final SwarmBackendTypes.BackendType backendType;

    private final TmuxBackendService tmuxBackend;
    private final ITermBackendService itermBackend;
    private final SwarmBackendDetectionService detectionService;
    private final SwarmBackendRegistryService registryService;
    private final TeammateMailboxService mailboxService;

    /** Maps agentId → pane tracking info for all spawned teammates. */
    private final Map<String, TeammateInfo> spawnedTeammates = new ConcurrentHashMap<>();

    /** Ensures cleanup is registered only once. */
    private volatile boolean cleanupRegistered = false;

    @Autowired
    public PaneBackendExecutorService(
            TmuxBackendService tmuxBackend,
            ITermBackendService itermBackend,
            SwarmBackendDetectionService detectionService,
            SwarmBackendRegistryService registryService,
            TeammateMailboxService mailboxService) {
        this.tmuxBackend = tmuxBackend;
        this.itermBackend = itermBackend;
        this.detectionService = detectionService;
        this.registryService = registryService;
        this.mailboxService = mailboxService;
        SwarmBackendRegistryService.PaneBackendType pt;
        try {
            SwarmBackendRegistryService.BackendDetectionResult detection =
                    registryService.detectAndGetBackend().join();
            pt = detection != null ? detection.backendType() : SwarmBackendRegistryService.PaneBackendType.TMUX;
        } catch (Exception e) {
            log.debug("[PaneBackendExecutor] Backend detection failed at startup ({}), defaulting to TMUX", e.getMessage());
            pt = SwarmBackendRegistryService.PaneBackendType.TMUX;
        }
        this.backendType = pt == SwarmBackendRegistryService.PaneBackendType.ITERM2
                ? SwarmBackendTypes.BackendType.ITERM2 : SwarmBackendTypes.BackendType.TMUX;
    }

    // -------------------------------------------------------------------------
    // TeammateExecutor interface
    // -------------------------------------------------------------------------

    public SwarmBackendTypes.BackendType getType() {
        return backendType;
    }

    /**
     * Checks if the underlying pane backend is available.
     * Translated from isAvailable() in PaneBackendExecutor.ts
     */
    public CompletableFuture<Boolean> isAvailable() {
        return switch (backendType) {
            case TMUX   -> tmuxBackend.isAvailable();
            case ITERM2 -> itermBackend.isAvailable();
            default     -> CompletableFuture.completedFuture(false);
        };
    }

    /**
     * Spawns a teammate in a new pane.
     *
     * Creates a pane via the backend, builds the CLI command with teammate
     * identity flags, and sends it to the pane.
     *
     * Translated from spawn() in PaneBackendExecutor.ts
     */
    public CompletableFuture<TeammateSpawnResult> spawn(TeammateSpawnConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            String agentId = formatAgentId(config.name(), config.teamName());

            try {
                // Assign a unique color to this teammate
                String teammateColor = (config.color() != null)
                        ? config.color()
                        : assignTeammateColor(agentId);

                // Create a pane in the swarm view
                Object createResult = createPaneInSwarmView(config.name(), teammateColor);
                String paneId = getPaneId(createResult);
                boolean isFirstTeammate = getIsFirstTeammate(createResult);

                // Check if we're inside tmux to determine how to send commands
                boolean insideTmux = detectionService.isInsideTmux();

                // Enable pane border status on first teammate when inside tmux
                if (isFirstTeammate && insideTmux) {
                    enablePaneBorderStatus();
                }

                // Build the spawn command
                String spawnCommand = buildSpawnCommand(config, agentId, teammateColor, insideTmux);

                // Send the command to the new pane
                // Use swarm socket when running outside tmux (external swarm session)
                sendCommandToPane(paneId, spawnCommand, !insideTmux).join();

                // Track the spawned teammate
                spawnedTeammates.put(agentId, new TeammateInfo(paneId, insideTmux));

                // Register cleanup to kill all panes on leader exit
                ensureCleanupRegistered();

                // Send initial instructions to teammate via mailbox
                TeammateMailboxService.MailboxMessage initMsg = TeammateMailboxService.MailboxMessage.builder()
                        .id(UUID.randomUUID().toString())
                        .from("team-lead")
                        .content(config.prompt())
                        .timestamp(Instant.now().toString())
                        .type("message")
                        .build();
                mailboxService.sendToMailbox(config.teamName(), config.name(), initMsg).join();

                log.debug("[PaneBackendExecutor] Spawned teammate {} in pane {}", agentId, paneId);

                return TeammateSpawnResult.success(agentId, paneId);

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                log.debug("[PaneBackendExecutor] Failed to spawn {}: {}", agentId, errorMessage);
                return TeammateSpawnResult.failure(agentId, errorMessage);
            }
        });
    }

    /**
     * Sends a message to a pane-based teammate via file-based mailbox.
     *
     * All teammates (pane and in-process) use the same mailbox mechanism.
     * Translated from sendMessage() in PaneBackendExecutor.ts
     */
    public CompletableFuture<Void> sendMessage(String agentId, TeammateMessage message) {
        return CompletableFuture.runAsync(() -> {
            log.debug("[PaneBackendExecutor] sendMessage() to {}: {}...",
                    agentId, abbreviate(message.text(), 50));

            AgentIdParts parsed = parseAgentId(agentId);
            if (parsed == null) {
                throw new IllegalArgumentException(
                        "Invalid agentId format: " + agentId + ". Expected format: agentName@teamName");
            }

            String timestamp = (message.timestamp() != null)
                    ? message.timestamp()
                    : Instant.now().toString();

            TeammateMailboxService.MailboxMessage mailboxMsg = TeammateMailboxService.MailboxMessage.builder()
                    .id(UUID.randomUUID().toString())
                    .from(message.from())
                    .content(message.text())
                    .timestamp(timestamp)
                    .type("message")
                    .metadata(message.color() != null ? Map.of("color", message.color()) : null)
                    .build();
            mailboxService.sendToMailbox(parsed.teamName(), parsed.agentName(), mailboxMsg).join();

            log.debug("[PaneBackendExecutor] sendMessage() completed for {}", agentId);
        });
    }

    /**
     * Gracefully terminates a pane-based teammate.
     *
     * Sends a shutdown request via mailbox and lets the teammate process
     * handle the exit gracefully.
     *
     * Translated from terminate() in PaneBackendExecutor.ts
     */
    public CompletableFuture<Boolean> terminate(String agentId, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[PaneBackendExecutor] terminate() called for {}: {}", agentId, reason);

            AgentIdParts parsed = parseAgentId(agentId);
            if (parsed == null) {
                log.debug("[PaneBackendExecutor] terminate() failed: invalid agentId format");
                return false;
            }

            // Build shutdown request JSON
            String requestId = "shutdown-" + agentId + "-" + System.currentTimeMillis();
            String shutdownJson = buildShutdownJson(requestId, agentId, reason);

            TeammateMailboxService.MailboxMessage shutdownMsg = TeammateMailboxService.MailboxMessage.builder()
                    .id(UUID.randomUUID().toString())
                    .from("team-lead")
                    .content(shutdownJson)
                    .timestamp(Instant.now().toString())
                    .type("shutdown_request")
                    .build();
            mailboxService.sendToMailbox(parsed.teamName(), parsed.agentName(), shutdownMsg).join();

            log.debug("[PaneBackendExecutor] terminate() sent shutdown request to {}", agentId);
            return true;
        });
    }

    /**
     * Force-kills a pane-based teammate by killing its pane.
     * Translated from kill() in PaneBackendExecutor.ts
     */
    public CompletableFuture<Boolean> kill(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[PaneBackendExecutor] kill() called for {}", agentId);

            TeammateInfo info = spawnedTeammates.get(agentId);
            if (info == null) {
                log.debug("[PaneBackendExecutor] kill() failed: teammate {} not found in spawned map",
                        agentId);
                return false;
            }

            // Use external session socket when we spawned outside tmux
            boolean killed = killPane(info.paneId(), !info.insideTmux()).join();

            if (killed) {
                spawnedTeammates.remove(agentId);
                log.debug("[PaneBackendExecutor] kill() succeeded for {}", agentId);
            } else {
                log.debug("[PaneBackendExecutor] kill() failed for {}", agentId);
            }

            return killed;
        });
    }

    /**
     * Checks if a pane-based teammate is still active.
     *
     * Best-effort check: active if we still have a record of the teammate.
     * Translated from isActive() in PaneBackendExecutor.ts
     */
    public CompletableFuture<Boolean> isActive(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[PaneBackendExecutor] isActive() called for {}", agentId);

            if (!spawnedTeammates.containsKey(agentId)) {
                log.debug("[PaneBackendExecutor] isActive(): teammate {} not found", agentId);
                return false;
            }
            // Assume active if we have a record — a robust check would query
            // the backend for pane existence, but that requires an additional method.
            return true;
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Object createPaneInSwarmView(String name, String color) {
        return switch (backendType) {
            case TMUX   -> tmuxBackend.createTeammatePaneInSwarmView(name, color).join();
            case ITERM2 -> itermBackend.createTeammatePaneInSwarmView(name, color).join();
            default     -> throw new UnsupportedOperationException(
                    "createTeammatePaneInSwarmView not supported for backend: " + backendType);
        };
    }

    private String getPaneId(Object result) {
        return switch (result) {
            case TmuxBackendService.CreatePaneResult r  -> r.paneId();
            case ITermBackendService.CreatePaneResult r -> r.paneId();
            default -> throw new IllegalArgumentException("Unexpected pane result type: " + result);
        };
    }

    private boolean getIsFirstTeammate(Object result) {
        return switch (result) {
            case TmuxBackendService.CreatePaneResult r  -> r.isFirstTeammate();
            case ITermBackendService.CreatePaneResult r -> r.isFirstTeammate();
            default -> false;
        };
    }

    private void enablePaneBorderStatus() {
        if (backendType == SwarmBackendTypes.BackendType.TMUX) {
            tmuxBackend.enablePaneBorderStatus().join();
        }
        // iTerm2: no-op
    }

    private CompletableFuture<Void> sendCommandToPane(
            String paneId, String command, boolean useExternalSession) {
        return switch (backendType) {
            case TMUX   -> tmuxBackend.sendCommandToPane(paneId, command, useExternalSession);
            case ITERM2 -> itermBackend.sendCommandToPane(paneId, command, useExternalSession);
            default     -> CompletableFuture.completedFuture(null);
        };
    }

    private CompletableFuture<Boolean> killPane(String paneId, boolean useExternalSession) {
        return switch (backendType) {
            case TMUX   -> tmuxBackend.killPane(paneId, useExternalSession);
            case ITERM2 -> itermBackend.killPane(paneId, useExternalSession);
            default     -> CompletableFuture.completedFuture(false);
        };
    }

    /**
     * Builds the full shell command to spawn a Claude Code teammate process.
     * Translated from the spawn command assembly in PaneBackendExecutor.ts
     */
    private String buildSpawnCommand(
            TeammateSpawnConfig config,
            String agentId,
            String teammateColor,
            boolean insideTmux) {

        String binaryPath = getTeammateCommand();
        String parentSessionId = (config.parentSessionId() != null && !config.parentSessionId().isBlank())
                ? config.parentSessionId()
                : getSessionId();

        List<String> parts = new ArrayList<>();
        parts.add("--agent-id " + shellQuote(agentId));
        parts.add("--agent-name " + shellQuote(config.name()));
        parts.add("--team-name " + shellQuote(config.teamName()));
        parts.add("--agent-color " + shellQuote(teammateColor));
        parts.add("--parent-session-id " + shellQuote(parentSessionId));
        if (config.planModeRequired()) {
            parts.add("--plan-mode-required");
        }

        String teammateArgs = String.join(" ", parts);
        String flagsStr = buildInheritedCliFlags(config);
        String envStr = buildInheritedEnvVars();

        return "cd " + shellQuote(config.cwd()) +
                " && env " + envStr +
                " " + shellQuote(binaryPath) +
                " " + teammateArgs +
                (flagsStr.isBlank() ? "" : " " + flagsStr);
    }

    /**
     * Registers a JVM shutdown hook to kill all spawned panes on leader exit.
     * Translated from the registerCleanup() call in PaneBackendExecutor.ts
     */
    private synchronized void ensureCleanupRegistered() {
        if (cleanupRegistered) return;
        cleanupRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Map.Entry<String, TeammateInfo> entry : spawnedTeammates.entrySet()) {
                String id = entry.getKey();
                TeammateInfo info = entry.getValue();
                log.debug("[PaneBackendExecutor] Cleanup: killing pane for {}", id);
                try {
                    killPane(info.paneId(), !info.insideTmux()).join();
                } catch (Exception e) {
                    log.debug("[PaneBackendExecutor] Cleanup failed for {}: {}", id, e.getMessage());
                }
            }
            spawnedTeammates.clear();
        }, "swarm-pane-cleanup"));
    }

    private String buildShutdownJson(String requestId, String agentId, String reason) {
        // Simple JSON construction without an external library dependency
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"type\":\"shutdown_request\",");
        sb.append("\"requestId\":").append(jsonString(requestId)).append(",");
        sb.append("\"from\":\"team-lead\",");
        sb.append("\"reason\":").append(reason != null ? jsonString(reason) : "null");
        sb.append("}");
        return sb.toString();
    }

    private String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // -------------------------------------------------------------------------
    // Agent ID utilities  (mirrors agentId.ts)
    // -------------------------------------------------------------------------

    private record AgentIdParts(String agentName, String teamName) {}

    private String formatAgentId(String name, String teamName) {
        return name + "@" + teamName;
    }

    private AgentIdParts parseAgentId(String agentId) {
        if (agentId == null) return null;
        int at = agentId.indexOf('@');
        if (at < 1 || at == agentId.length() - 1) return null;
        return new AgentIdParts(agentId.substring(0, at), agentId.substring(at + 1));
    }

    // -------------------------------------------------------------------------
    // Stubs for application-wide helpers (mirrors spawnUtils.ts / state.ts)
    // These should be replaced with real Spring-bean injections as those
    // services are ported.
    // -------------------------------------------------------------------------

    /**
     * Returns the path of the Claude Code binary that should be used for teammates.
     * Mirrors getTeammateCommand() in spawnUtils.ts.
     */
    private String getTeammateCommand() {
        String override = System.getenv("CLAUDE_CODE_BINARY");
        if (override != null && !override.isBlank()) return override;
        // Default: same binary as the current process (via ProcessHandle)
        return ProcessHandle.current()
                .info()
                .command()
                .orElse("claude");
    }

    /**
     * Returns the current session ID.
     * Mirrors getSessionId() from bootstrap/state.ts.
     */
    private String getSessionId() {
        String id = System.getenv("CLAUDE_CODE_SESSION_ID");
        return (id != null && !id.isBlank()) ? id : "unknown-session";
    }

    /**
     * Builds inherited CLI flags to forward to the teammate process.
     * Mirrors buildInheritedCliFlags() in spawnUtils.ts.
     */
    private String buildInheritedCliFlags(TeammateSpawnConfig config) {
        List<String> flags = new ArrayList<>();
        if (config.planModeRequired()) flags.add("--plan-mode-required");
        if (config.model() != null && !config.model().isBlank()) {
            flags.add("--model " + shellQuote(config.model()));
        }
        return String.join(" ", flags);
    }

    /**
     * Builds the env var string to forward to the teammate process.
     * Mirrors buildInheritedEnvVars() in spawnUtils.ts.
     */
    private String buildInheritedEnvVars() {
        // Forward a safe set of env vars; extend as the real implementation requires.
        List<String> entries = new ArrayList<>();
        forwardEnvVar(entries, "ANTHROPIC_API_KEY");
        forwardEnvVar(entries, "ANTHROPIC_BASE_URL");
        forwardEnvVar(entries, "CLAUDE_CODE_USE_BEDROCK");
        forwardEnvVar(entries, "CLAUDE_CODE_USE_VERTEX");
        return String.join(" ", entries);
    }

    private void forwardEnvVar(List<String> entries, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            entries.add(name + "=" + shellQuote(value));
        }
    }

    /**
     * Assigns a display color to a teammate by cycling through available colors.
     * Mirrors assignTeammateColor() in teammateLayoutManager.ts.
     */
    private String assignTeammateColor(String agentId) {
        String[] palette = {"blue", "green", "yellow", "purple", "orange", "pink", "cyan", "red"};
        return palette[Math.abs(agentId.hashCode()) % palette.length];
    }

    /**
     * Single-value shell quoting (wraps in single quotes, escapes internal single quotes).
     * Mirrors quote([value]) from bash/shellQuote.ts.
     */
    private String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
