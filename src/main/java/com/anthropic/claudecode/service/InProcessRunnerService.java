package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * In-process teammate backend — runs teammate agents within the same JVM process.
 *
 * Translated from src/utils/swarm/backends/InProcessBackend.ts
 *
 * Unlike pane-based backends (tmux/iTerm2), in-process teammates share resources
 * with the leader (API client, MCP connections). They communicate via file-based
 * mailboxes and are terminated via thread interruption rather than kill-pane.
 *
 * Call setContext() before spawn() to provide AppState access.
 */
@Slf4j
@Service
public class InProcessRunnerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InProcessRunnerService.class);


    private final SpawnInProcessService spawnInProcessService;
    private final TeammateMailboxService teammateMailboxService;

    /**
     * Tool use / app context set by the caller before spawning.
     * Translated from 'context' field in InProcessBackend.ts
     */
    private volatile Object context = null;

    @Autowired
    public InProcessRunnerService(
            SpawnInProcessService spawnInProcessService,
            TeammateMailboxService teammateMailboxService) {
        this.spawnInProcessService = spawnInProcessService;
        this.teammateMailboxService = teammateMailboxService;
    }

    // =========================================================================
    // TeammateExecutor interface
    // =========================================================================

    /** Always in-process. Translated from readonly type = 'in-process' in InProcessBackend.ts */
    public String getType() { return "in-process"; }

    /**
     * Sets the tool-use context for this backend.
     * Translated from setContext() in InProcessBackend.ts
     */
    public void setContext(Object context) {
        this.context = context;
    }

    /**
     * In-process backend is always available (no external dependencies).
     * Translated from isAvailable() in InProcessBackend.ts
     */
    public CompletableFuture<Boolean> isAvailable() {
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Spawns an in-process teammate.
     *
     * Uses SpawnInProcessService to:
     * 1. Create TeammateContext
     * 2. Create independent AbortController equivalent
     * 3. Register teammate in AppState.tasks
     * 4. Start agent execution
     *
     * Translated from spawn() in InProcessBackend.ts
     */
    public CompletableFuture<SpawnResult> spawn(TeammateSpawnConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            if (context == null) {
                log.debug("[InProcessBackend] spawn() called without context for {}", config.name());
                return SpawnResult.failure(
                    config.name() + "@" + config.teamName(),
                    "InProcessBackend not initialized. Call setContext() before spawn()."
                );
            }

            log.debug("[InProcessBackend] spawn() called for {}", config.name());
            SpawnInProcessService.InProcessSpawnConfig spawnConfig =
                new SpawnInProcessService.InProcessSpawnConfig(
                    config.name(), config.teamName(), null, config.prompt(),
                    config.model(), config.color(), config.planModeRequired(),
                    null, null);
            SpawnInProcessService.InProcessSpawnResult result =
                spawnInProcessService.spawnInProcessTeammate(spawnConfig).join();
            return result.isSuccess()
                ? SpawnResult.success(result.getAgentId(), result.getTaskId())
                : SpawnResult.failure(result.getAgentId(), result.getError());
        });
    }

    /**
     * Sends a message to an in-process teammate via file-based mailbox.
     * Translated from sendMessage() in InProcessBackend.ts
     *
     * agentId format: "agentName@teamName"
     */
    public CompletableFuture<Void> sendMessage(String agentId, TeammateMessage message) {
        return CompletableFuture.runAsync(() -> {
            log.debug("[InProcessBackend] sendMessage() to {}: {}...",
                    agentId, message.text().substring(0, Math.min(50, message.text().length())));

            ParsedAgentId parsed = parseAgentId(agentId);
            if (parsed == null) {
                log.debug("[InProcessBackend] Invalid agentId format: {}", agentId);
                throw new IllegalArgumentException(
                    "Invalid agentId format: " + agentId + ". Expected format: agentName@teamName");
            }

            String timestamp = message.timestamp() != null
                    ? message.timestamp()
                    : Instant.now().toString();

            teammateMailboxService.writeToMailbox(
                parsed.agentName(),
                new TeammateMailboxService.MailboxEntry(
                    message.text(), message.from(), message.color(), timestamp),
                parsed.teamName()
            );

            log.debug("[InProcessBackend] sendMessage() completed for {}", agentId);
        });
    }

    /**
     * Gracefully terminates an in-process teammate by sending a shutdown request.
     * Translated from terminate() in InProcessBackend.ts
     */
    public CompletableFuture<Boolean> terminate(String agentId, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[InProcessBackend] terminate() called for {}: {}", agentId, reason);

            if (context == null) {
                log.debug("[InProcessBackend] terminate() failed: no context set for {}", agentId);
                return false;
            }

            boolean result = spawnInProcessService.requestShutdown(agentId, reason, context);
            log.debug("[InProcessBackend] terminate() {} for {}", result ? "succeeded" : "failed", agentId);
            return result;
        });
    }

    /**
     * Force kills an in-process teammate using the AbortController equivalent.
     * Translated from kill() in InProcessBackend.ts
     */
    public CompletableFuture<Boolean> kill(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[InProcessBackend] kill() called for {}", agentId);

            if (context == null) {
                log.debug("[InProcessBackend] kill() failed: no context set for {}", agentId);
                return false;
            }

            boolean killed = spawnInProcessService.killInProcessTeammate(agentId, context);
            log.debug("[InProcessBackend] kill() {} for {}", killed ? "succeeded" : "failed", agentId);
            return killed;
        });
    }

    /**
     * Checks whether an in-process teammate is still active.
     * Returns true if the teammate exists, has status 'running', and is not aborted.
     * Translated from isActive() in InProcessBackend.ts
     */
    public CompletableFuture<Boolean> isActive(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("[InProcessBackend] isActive() called for {}", agentId);

            if (context == null) {
                log.debug("[InProcessBackend] isActive() failed: no context set for {}", agentId);
                return false;
            }

            boolean active = spawnInProcessService.isTeammateActive(agentId, context);
            log.debug("[InProcessBackend] isActive() for {}: {}", agentId, active);
            return active;
        });
    }

    // =========================================================================
    // Value types
    // =========================================================================

    /**
     * Configuration for spawning a teammate.
     * Translated from TeammateSpawnConfig in types.ts
     */
    public record TeammateSpawnConfig(
        String name,
        String teamName,
        String prompt,
        String color,
        boolean planModeRequired,
        String model,
        String systemPrompt,
        String systemPromptMode,
        java.util.List<String> permissions,
        boolean allowPermissionPrompts
    ) {}

    /**
     * Result of spawning a teammate.
     * Translated from TeammateSpawnResult in types.ts
     */
    public record SpawnResult(
        boolean success,
        String agentId,
        String taskId,
        String error
    ) {
        public static SpawnResult failure(String agentId, String error) {
            return new SpawnResult(false, agentId, null, error);
        }
        public static SpawnResult success(String agentId, String taskId) {
            return new SpawnResult(true, agentId, taskId, null);
        }
    }

    /**
     * A message sent to/from a teammate.
     * Translated from TeammateMessage in types.ts
     */
    public record TeammateMessage(String text, String from, String color, String timestamp) {}

    /**
     * Parsed agentId components (agentName@teamName).
     * Translated from parseAgentId() in agentId.ts
     */
    public record ParsedAgentId(String agentName, String teamName) {}

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Parses "agentName@teamName" into components.
     * Returns null if the format is invalid.
     * Translated from parseAgentId() in utils/agentId.ts
     */
    private ParsedAgentId parseAgentId(String agentId) {
        if (agentId == null) return null;
        int atIdx = agentId.indexOf('@');
        if (atIdx <= 0 || atIdx >= agentId.length() - 1) return null;
        return new ParsedAgentId(agentId.substring(0, atIdx), agentId.substring(atIdx + 1));
    }
}
