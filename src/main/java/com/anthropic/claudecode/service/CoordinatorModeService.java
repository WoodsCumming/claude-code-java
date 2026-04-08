package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Coordinator mode service.
 * Translated from src/coordinator/coordinatorMode.ts
 *
 * Coordinator mode enables multi-agent coordination where a lead coordinator
 * manages a team of worker agents. In coordinator mode, the lead agent
 * orchestrates workers via the Agent, SendMessage, and TaskStop tools.
 */
@Slf4j
@Service
public class CoordinatorModeService {



    private static final String COORDINATOR_MODE_ENV = "CLAUDE_CODE_COORDINATOR_MODE";
    private static final String SIMPLE_MODE_ENV = "CLAUDE_CODE_SIMPLE";

    // Internal tools not available to workers in non-simple mode
    private static final Set<String> INTERNAL_WORKER_TOOLS = Set.of(
        "TeamCreate",
        "TeamDelete",
        "SendMessage",
        "SyntheticOutput"
    );

    private final AnalyticsService analyticsService;
    private final GrowthBookService growthBookService;

    @Autowired
    public CoordinatorModeService(
            AnalyticsService analyticsService,
            GrowthBookService growthBookService) {
        this.analyticsService = analyticsService;
        this.growthBookService = growthBookService;
    }

    /**
     * Check if coordinator mode is enabled.
     * Translated from isCoordinatorMode() in coordinatorMode.ts
     *
     * Gated on the COORDINATOR_MODE feature flag.
     */
    public boolean isCoordinatorMode() {
        if (growthBookService.isFeatureEnabled("COORDINATOR_MODE")) {
            return EnvUtils.isEnvTruthy(System.getenv(COORDINATOR_MODE_ENV));
        }
        return false;
    }

    /**
     * Checks if the current coordinator mode matches the session's stored mode.
     * If mismatched, flips the environment variable so isCoordinatorMode() returns
     * the correct value for the resumed session.
     * Translated from matchSessionMode() in coordinatorMode.ts
     *
     * @param sessionMode the session's stored mode ("coordinator", "normal", or null)
     * @return warning message if mode was switched, null if no switch needed
     */
    public String matchSessionMode(String sessionMode) {
        // No stored mode (old session before mode tracking) — do nothing
        if (sessionMode == null) return null;

        boolean currentIsCoordinator = isCoordinatorMode();
        boolean sessionIsCoordinator = "coordinator".equals(sessionMode);

        if (currentIsCoordinator == sessionIsCoordinator) return null;

        // Flip the system property — isCoordinatorMode() reads it live, no caching
        if (sessionIsCoordinator) {
            System.setProperty(COORDINATOR_MODE_ENV, "1");
        } else {
            System.clearProperty(COORDINATOR_MODE_ENV);
        }

        analyticsService.logEvent("tengu_coordinator_mode_switched", Map.of(
            "to", sessionMode
        ));

        return sessionIsCoordinator
            ? "Entered coordinator mode to match resumed session."
            : "Exited coordinator mode to match resumed session.";
    }

    /**
     * Get the coordinator user context injected into the first user turn.
     * Translated from getCoordinatorUserContext() in coordinatorMode.ts
     *
     * @param mcpClients   list of connected MCP clients (for tool context)
     * @param scratchpadDir optional shared scratchpad directory path
     * @return map with "workerToolsContext" key, or empty map if not in coordinator mode
     */
    public Map<String, String> getCoordinatorUserContext(
            List<String> mcpClientNames,
            String scratchpadDir) {
        if (!isCoordinatorMode()) {
            return Map.of();
        }

        String workerTools;
        if (EnvUtils.isEnvTruthy(System.getenv(SIMPLE_MODE_ENV))) {
            // Simple mode: only basic tools, sorted
            workerTools = List.of("Bash", "Edit", "Read").stream()
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        } else {
            // Full async-agent allowed tools minus internal coordinator tools, sorted
            workerTools = getAsyncAgentAllowedTools().stream()
                .filter(name -> !INTERNAL_WORKER_TOOLS.contains(name))
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        }

        StringBuilder content = new StringBuilder();
        content.append("Workers spawned via the Agent tool have access to these tools: ")
               .append(workerTools);

        if (!mcpClientNames.isEmpty()) {
            String serverNames = String.join(", ", mcpClientNames);
            content.append("\n\nWorkers also have access to MCP tools from connected MCP servers: ")
                   .append(serverNames);
        }

        if (scratchpadDir != null && growthBookService.isFeatureEnabled("tengu_scratch")) {
            content.append("\n\nScratchpad directory: ").append(scratchpadDir)
                   .append("\nWorkers can read and write here without permission prompts. ")
                   .append("Use this for durable cross-worker knowledge — structure files however fits the work.");
        }

        return Map.of("workerToolsContext", content.toString());
    }

    /**
     * Get the coordinator system prompt injected at the start of coordinator sessions.
     * Translated from getCoordinatorSystemPrompt() in coordinatorMode.ts
     */
    public String getCoordinatorSystemPrompt() {
        String workerCapabilities = EnvUtils.isEnvTruthy(System.getenv(SIMPLE_MODE_ENV))
            ? "Workers have access to Bash, Read, and Edit tools, plus MCP tools from configured MCP servers."
            : "Workers have access to standard tools, MCP tools from configured MCP servers, and project skills via the Skill tool. Delegate skill invocations (e.g. /commit, /verify) to workers.";

        return """
You are Claude Code, an AI assistant that orchestrates software engineering tasks across multiple workers.

## 1. Your Role

You are a **coordinator**. Your job is to:
- Help the user achieve their goal
- Direct workers to research, implement and verify code changes
- Synthesize results and communicate with the user
- Answer questions directly when possible — don't delegate work that you can handle without tools

Every message you send is to the user. Worker results and system notifications are internal signals, not conversation partners — never thank or acknowledge them. Summarize new information for the user as it arrives.

## 2. Your Tools

- **Agent** - Spawn a new worker
- **SendMessage** - Continue an existing worker (send a follow-up to its `to` agent ID)
- **TaskStop** - Stop a running worker
- **subscribe_pr_activity / unsubscribe_pr_activity** (if available) - Subscribe to GitHub PR events

## 3. Workers

When calling Agent, use subagent_type `worker`. Workers execute tasks autonomously.

""" + workerCapabilities;
    }

    /**
     * Returns the full set of tools available in async agent (non-simple) mode.
     * Mirrors ASYNC_AGENT_ALLOWED_TOOLS from src/constants/tools.ts
     */
    private Set<String> getAsyncAgentAllowedTools() {
        return Set.of(
            "Agent",
            "Bash",
            "Edit",
            "Glob",
            "Grep",
            "Read",
            "SendMessage",
            "Skill",
            "SyntheticOutput",
            "TaskStop",
            "TeamCreate",
            "TeamDelete",
            "WebFetch",
            "WebSearch",
            "Write"
        );
    }
}
