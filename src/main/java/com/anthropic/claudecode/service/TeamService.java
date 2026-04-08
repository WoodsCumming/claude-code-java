package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;

/**
 * Team management service for multi-agent swarms.
 * Translated from src/utils/swarm/teamHelpers.ts
 *
 * Manages teams of agents working together on tasks.
 */
@Slf4j
@Service
public class TeamService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeamService.class);


    private static final String TEAM_LEAD_NAME = "team-lead";

    private final ObjectMapper objectMapper;
    private final Map<String, TeamInfo> activeTeams = new ConcurrentHashMap<>();

    @Autowired
    public TeamService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get the teams directory.
     */
    public String getTeamsDir() {
        return EnvUtils.getTeamsDir();
    }

    /**
     * Create a new team.
     * Translated from spawnTeam operation in teamHelpers.ts
     */
    public TeamInfo createTeam(String teamName, String agentType, String description) throws Exception {
        if (teamName == null || teamName.isBlank()) {
            throw new IllegalArgumentException("Team name is required");
        }

        String teamsDir = getTeamsDir();
        new File(teamsDir).mkdirs();

        String teamFilePath = teamsDir + "/" + teamName + ".json";
        String leadAgentId = "lead-" + UUID.randomUUID().toString().substring(0, 8);

        TeamInfo team = new TeamInfo(teamName, teamFilePath, leadAgentId, agentType, description);
        activeTeams.put(teamName, team);

        // Save team file
        objectMapper.writeValue(new File(teamFilePath), team);

        log.info("Created team: {} (lead: {})", teamName, leadAgentId);
        return team;
    }

    /**
     * Clean up a team.
     * Translated from cleanup operation in teamHelpers.ts
     */
    public boolean cleanupTeam(String teamName) {
        if (teamName == null) return false;

        activeTeams.remove(teamName);

        String teamsDir = getTeamsDir();
        File teamFile = new File(teamsDir + "/" + teamName + ".json");
        boolean deleted = teamFile.delete();

        log.info("Cleaned up team: {}", teamName);
        return deleted;
    }

    /**
     * Get team info.
     */
    public Optional<TeamInfo> getTeam(String teamName) {
        return Optional.ofNullable(activeTeams.get(teamName));
    }

    /**
     * List all active teams.
     */
    public List<TeamInfo> listTeams() {
        return new ArrayList<>(activeTeams.values());
    }

    /**
     * Read a team file.
     */
    public Optional<TeamInfo> readTeamFile(String teamName) {
        String teamsDir = getTeamsDir();
        File teamFile = new File(teamsDir + "/" + teamName + ".json");

        if (!teamFile.exists()) return Optional.empty();

        try {
            return Optional.of(objectMapper.readValue(teamFile, TeamInfo.class));
        } catch (Exception e) {
            log.warn("Could not read team file {}: {}", teamName, e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public static class TeamInfo {
        private String teamName;
        private String teamFilePath;
        private String leadAgentId;
        private String agentType;
        private String description;

        public TeamInfo() {}
        public TeamInfo(String teamName, String teamFilePath, String leadAgentId, String agentType, String description) {
            this.teamName = teamName; this.teamFilePath = teamFilePath; this.leadAgentId = leadAgentId;
            this.agentType = agentType; this.description = description;
        }
        public String getTeamName() { return teamName; }
        public void setTeamName(String v) { teamName = v; }
        public String getTeamFilePath() { return teamFilePath; }
        public void setTeamFilePath(String v) { teamFilePath = v; }
        public String getLeadAgentId() { return leadAgentId; }
        public void setLeadAgentId(String v) { leadAgentId = v; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
    }
}
