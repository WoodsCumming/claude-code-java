package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Swarm team helpers service.
 * Translated from src/utils/swarm/teamHelpers.ts
 *
 * Manages team files and inter-agent coordination.
 */
@Slf4j
@Service
public class SwarmTeamHelpersService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SwarmTeamHelpersService.class);


    private final ObjectMapper objectMapper;

    @Autowired
    public SwarmTeamHelpersService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get the teams directory.
     * Translated from getTeamsDir() in envUtils.ts
     */
    public String getTeamsDir() {
        String teamsDir = System.getenv("CLAUDE_TEAMS_DIR");
        if (teamsDir != null && !teamsDir.isBlank()) return teamsDir;
        return EnvUtils.getClaudeConfigHomeDir() + "/teams";
    }

    /**
     * Read a team file.
     * Translated from readTeamFile() in teamHelpers.ts
     */
    public Optional<Map<String, Object>> readTeamFile(String teamName) {
        String path = getTeamsDir() + "/" + teamName + "/team.json";
        try {
            File file = new File(path);
            if (!file.exists()) return Optional.empty();
            Map<String, Object> data = objectMapper.readValue(file, Map.class);
            return Optional.of(data);
        } catch (Exception e) {
            log.debug("Could not read team file for {}: {}", teamName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Write a team file.
     * Translated from writeTeamFile() in teamHelpers.ts
     */
    public CompletableFuture<Void> writeTeamFile(String teamName, Map<String, Object> data) {
        return CompletableFuture.runAsync(() -> {
            String dir = getTeamsDir() + "/" + teamName;
            try {
                new File(dir).mkdirs();
                objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(dir + "/team.json"), data);
            } catch (Exception e) {
                log.error("Could not write team file for {}: {}", teamName, e.getMessage());
            }
        });
    }

    /**
     * Sanitize an agent name for use in team files.
     * Translated from sanitizeAgentName() in teamHelpers.ts
     */
    public String sanitizeAgentName(String name) {
        if (name == null) return "agent";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }

    /**
     * Sanitize a name for general use.
     * Translated from sanitizeName() in teamHelpers.ts
     */
    public String sanitizeName(String name) {
        if (name == null) return "";
        return name.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    /**
     * Assign a color to a teammate.
     * Translated from assignTeammateColor() in teamHelpers.ts
     */
    public String assignTeammateColor(String teamName, String agentName) {
        List<String> colors = List.of(
            "red", "blue", "green", "yellow", "purple", "orange", "pink", "cyan"
        );
        int hash = (teamName + agentName).hashCode();
        return colors.get(Math.abs(hash) % colors.size());
    }
}
