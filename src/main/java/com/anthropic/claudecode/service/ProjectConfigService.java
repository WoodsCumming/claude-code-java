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

/**
 * Project configuration service.
 * Translated from src/utils/config.ts (getCurrentProjectConfig, saveCurrentProjectConfig)
 *
 * Manages per-project configuration stored in ~/.claude/projects/<hash>/config.json.
 */
@Slf4j
@Service
public class ProjectConfigService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectConfigService.class);

    private static final String PROJECTS_SUBDIR = "projects";
    private static final String CONFIG_FILE = "config.json";

    private final ObjectMapper objectMapper;
    private ProjectConfig cachedConfig;

    @Autowired
    public ProjectConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get the project config file path.
     */
    public String getProjectConfigPath(String projectPath) {
        if (projectPath == null) projectPath = System.getProperty("user.dir");
        String sanitized = projectPath.replace("/", "-").replace("\\", "-")
            .replaceAll("[^a-zA-Z0-9.-]", "_");
        return EnvUtils.getClaudeConfigHomeDir() + "/" + PROJECTS_SUBDIR + "/" + sanitized + "/" + CONFIG_FILE;
    }

    /**
     * Get the current project config.
     * Translated from getCurrentProjectConfig() in config.ts
     */
    public ProjectConfig getCurrentProjectConfig() {
        if (cachedConfig != null) return cachedConfig;

        String configPath = getProjectConfigPath(System.getProperty("user.dir"));
        File configFile = new File(configPath);

        if (!configFile.exists()) {
            cachedConfig = new ProjectConfig();
            return cachedConfig;
        }

        try {
            cachedConfig = objectMapper.readValue(configFile, ProjectConfig.class);
            if (cachedConfig == null) cachedConfig = new ProjectConfig();
        } catch (Exception e) {
            log.debug("Could not read project config: {}", e.getMessage());
            cachedConfig = new ProjectConfig();
        }

        return cachedConfig;
    }

    /**
     * Save the current project config.
     * Translated from saveCurrentProjectConfig() in config.ts
     */
    public void saveCurrentProjectConfig(ProjectConfig config) {
        this.cachedConfig = config;

        String configPath = getProjectConfigPath(System.getProperty("user.dir"));
        try {
            new File(configPath).getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(configPath), config);
        } catch (Exception e) {
            log.error("Could not save project config: {}", e.getMessage());
        }
    }

    /**
     * Update project config with a function.
     */
    public void updateProjectConfig(java.util.function.UnaryOperator<ProjectConfig> updater) {
        ProjectConfig current = getCurrentProjectConfig();
        ProjectConfig updated = updater.apply(current);
        saveCurrentProjectConfig(updated);
    }

    public static class ProjectConfig {
        private List<String> allowedTools = new ArrayList<>();
        private List<String> mcpContextUris = new ArrayList<>();
        private Map<String, Object> mcpServers = new LinkedHashMap<>();
        private Long lastAPIDuration;
        private String lastSessionId;
        private Map<String, Object> lastModelUsage;
        private Boolean hasTrustDialogAccepted;
        private String lastCompactAt;

        public ProjectConfig() {}

        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> v) { allowedTools = v; }
        public List<String> getMcpContextUris() { return mcpContextUris; }
        public void setMcpContextUris(List<String> v) { mcpContextUris = v; }
        public Map<String, Object> getMcpServers() { return mcpServers; }
        public void setMcpServers(Map<String, Object> v) { mcpServers = v; }
        public Long getLastAPIDuration() { return lastAPIDuration; }
        public void setLastAPIDuration(Long v) { lastAPIDuration = v; }
        public String getLastSessionId() { return lastSessionId; }
        public void setLastSessionId(String v) { lastSessionId = v; }
        public Map<String, Object> getLastModelUsage() { return lastModelUsage; }
        public void setLastModelUsage(Map<String, Object> v) { lastModelUsage = v; }
        public Boolean getHasTrustDialogAccepted() { return hasTrustDialogAccepted; }
        public void setHasTrustDialogAccepted(Boolean v) { hasTrustDialogAccepted = v; }
        public String getLastCompactAt() { return lastCompactAt; }
        public void setLastCompactAt(String v) { lastCompactAt = v; }
    
        private Long lastCost;
        public Long getLastCost() { return lastCost; }
        public void setLastCost(Long v) { lastCost = v; }
    
        private Long lastDuration;
        public Long getLastDuration() { return lastDuration; }
        public void setLastDuration(Long v) { lastDuration = v; }
    }
}
