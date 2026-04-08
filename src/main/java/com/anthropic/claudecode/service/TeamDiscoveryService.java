package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * Team discovery service for finding teams and teammate status.
 * Translated from src/utils/teamDiscovery.ts
 *
 * Scans ~/.claude/teams/ to find teams where the current session is the leader.
 * Used by the Teams UI in the footer to show team status.
 */
@Slf4j
@Service
public class TeamDiscoveryService {



    /** Backend types for swarm panes. */
    public enum PaneBackendType {
        tmux, iterm2, process
    }

    /**
     * Summary of a team's member/running/idle counts.
     * Translated from TeamSummary in teamDiscovery.ts
     */
    public record TeamSummary(
        String name,
        int memberCount,
        int runningCount,
        int idleCount
    ) {}

    /**
     * Detailed status of an individual teammate.
     * Translated from TeammateStatus in teamDiscovery.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TeammateStatus {
        private String name;
        private String agentId;
        private String agentType;
        private String model;
        private String prompt;
        /** running | idle | unknown */
        private String status;
        private String color;
        /** ISO timestamp from idle notification */
        private String idleSince;
        private String tmuxPaneId;
        private String cwd;
        private String worktreePath;
        /** Whether the pane is currently hidden from the swarm view */
        private Boolean isHidden;
        private PaneBackendType backendType;
        /** Current permission mode for this teammate */
        private String mode;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public String getIdleSince() { return idleSince; }
        public void setIdleSince(String v) { idleSince = v; }
        public String getTmuxPaneId() { return tmuxPaneId; }
        public void setTmuxPaneId(String v) { tmuxPaneId = v; }
        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public String getWorktreePath() { return worktreePath; }
        public void setWorktreePath(String v) { worktreePath = v; }
        public boolean isIsHidden() { return isHidden; }
        public void setIsHidden(Boolean v) { isHidden = v; }
        public PaneBackendType getBackendType() { return backendType; }
        public void setBackendType(PaneBackendType v) { backendType = v; }
        public String getMode() { return mode; }
        public void setMode(String v) { mode = v; }
    }

    private final ObjectMapper objectMapper;

    @Autowired
    public TeamDiscoveryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get detailed teammate statuses for a team.
     * Reads isActive from config to determine running/idle status.
     * Translated from getTeammateStatuses() in teamDiscovery.ts
     */
    public List<TeammateStatus> getTeammateStatuses(String teamName) {
        File teamFile = resolveTeamConfigFile(teamName);
        if (teamFile == null || !teamFile.exists()) return List.of();

        Map<String, Object> teamData;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(teamFile, Map.class);
            teamData = raw;
        } catch (Exception e) {
            log.debug("[TeamDiscovery] Could not read team file for {}: {}", teamName, e.getMessage());
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members =
            (List<Map<String, Object>>) teamData.getOrDefault("members", List.of());

        @SuppressWarnings("unchecked")
        List<String> hiddenPaneIdsList =
            (List<String>) teamData.getOrDefault("hiddenPaneIds", List.of());
        Set<String> hiddenPaneIds = new HashSet<>(hiddenPaneIdsList);

        List<TeammateStatus> statuses = new ArrayList<>();
        for (Map<String, Object> member : members) {
            String name = (String) member.get("name");

            // Exclude team-lead from the list (matches TS behaviour)
            if ("team-lead".equals(name)) continue;

            // Read isActive from config, defaulting to true (active) if undefined
            Object isActiveObj = member.get("isActive");
            boolean isActive = isActiveObj == null || Boolean.TRUE.equals(isActiveObj);
            String status = isActive ? "running" : "idle";

            String tmuxPaneId = (String) member.getOrDefault("tmuxPaneId", "");

            // Resolve backendType safely
            PaneBackendType backendType = null;
            String backendTypeStr = (String) member.get("backendType");
            if (backendTypeStr != null) {
                try {
                    backendType = PaneBackendType.valueOf(backendTypeStr);
                } catch (IllegalArgumentException ignored) {}
            }

            TeammateStatus ts = new TeammateStatus();
            ts.setName(name);
            ts.setAgentId((String) member.get("agentId"));
            ts.setAgentType((String) member.get("agentType"));
            ts.setModel((String) member.get("model"));
            ts.setPrompt((String) member.get("prompt"));
            ts.setStatus(status);
            ts.setColor((String) member.get("color"));
            ts.setTmuxPaneId(tmuxPaneId);
            ts.setCwd((String) member.getOrDefault("cwd", ""));
            ts.setWorktreePath((String) member.get("worktreePath"));
            ts.setIsHidden(!tmuxPaneId.isEmpty() && hiddenPaneIds.contains(tmuxPaneId));
            ts.setBackendType(backendType);
            ts.setMode((String) member.get("mode"));

            statuses.add(ts);
        }
        return statuses;
    }

    /**
     * Build a TeamSummary from live teammate statuses.
     */
    public Optional<TeamSummary> getTeamSummary(String teamName) {
        List<TeammateStatus> statuses = getTeammateStatuses(teamName);
        if (statuses.isEmpty()) return Optional.empty();

        long running = statuses.stream().filter(s -> "running".equals(s.getStatus())).count();
        long idle    = statuses.stream().filter(s -> "idle".equals(s.getStatus())).count();

        return Optional.of(new TeamSummary(
            teamName,
            statuses.size(),
            (int) running,
            (int) idle
        ));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Locate the config.json for the given team name.
     * Tries both "config.json" and the legacy "team.json" layout.
     */
    private File resolveTeamConfigFile(String teamName) {
        String teamsDir = EnvUtils.getClaudeConfigHomeDir() + "/teams";
        // Sanitize team name the same way the TS side does (replace non-alphanum with '-', lowercase)
        String sanitized = teamName.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();

        File config = new File(teamsDir + "/" + sanitized + "/config.json");
        if (config.exists()) return config;

        // Legacy layout fallback
        File legacy = new File(teamsDir + "/" + teamName + "/team.json");
        if (legacy.exists()) return legacy;

        return config; // return even if absent; caller checks exists()
    }
}
