package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.BundledMode;
import com.anthropic.claudecode.util.SwarmConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Swarm spawn utilities service.
 * Translated from src/utils/swarm/spawnUtils.ts
 *
 * Shared utilities for spawning teammates across different backends.
 */
@Slf4j
@Service
public class SwarmSpawnUtilsService {



    /**
     * Get the command to use for spawning teammate processes.
     * Translated from getTeammateCommand() in spawnUtils.ts
     */
    public String getTeammateCommand() {
        String override = System.getenv(SwarmConstants.TEAMMATE_COMMAND_ENV_VAR);
        if (override != null && !override.isBlank()) return override;
        return ProcessHandle.current().info().command().orElse("claude");
    }

    /**
     * Build inherited environment variables for spawned teammates.
     * Translated from buildInheritedEnvVars() in spawnUtils.ts
     */
    public Map<String, String> buildInheritedEnvVars(
            String agentId,
            String teamName,
            String color,
            String parentSessionId) {

        Map<String, String> env = new LinkedHashMap<>();

        // Inherit key environment variables
        inheritEnvVar(env, "ANTHROPIC_API_KEY");
        inheritEnvVar(env, "ANTHROPIC_BASE_URL");
        inheritEnvVar(env, "CLAUDE_CODE_ENTRYPOINT");
        inheritEnvVar(env, "CLAUDE_CONFIG_DIR");

        // Set teammate-specific variables
        if (agentId != null) env.put("CLAUDE_CODE_AGENT_ID", agentId);
        if (teamName != null) env.put("CLAUDE_CODE_TEAM_NAME", teamName);
        if (color != null) env.put(SwarmConstants.TEAMMATE_COLOR_ENV_VAR, color);
        if (parentSessionId != null) env.put("CLAUDE_CODE_PARENT_SESSION_ID", parentSessionId);

        return env;
    }

    private void inheritEnvVar(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null) env.put(key, value);
    }
}
