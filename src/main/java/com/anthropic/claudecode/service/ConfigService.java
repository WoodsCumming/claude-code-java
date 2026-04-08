package com.anthropic.claudecode.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Configuration service managing project-level and global Claude configuration.
 *
 * Translated from src/utils/config.ts
 *
 * Handles reading and writing both the global ~/.claude.json config and per-project
 * .claude/settings.json, with caching, BOM-stripping, and file-watch support.
 */
@Slf4j
@Service
public class ConfigService {



    private static final String GLOBAL_CONFIG_FILENAME = ".claude.json";
    private static final String PROJECT_CONFIG_DIR = ".claude";

    private final ObjectMapper objectMapper;

    @Autowired
    public ConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Nested types — mirrors key TypeScript types from config.ts
    // ------------------------------------------------------------------

    public enum ReleaseChannel {
        STABLE("stable"),
        LATEST("latest");

        private final String value;
        ReleaseChannel(String v) { this.value = v; }
        public String getValue() { return value; }
    }

    public enum InstallMethod {
        LOCAL("local"),
        NATIVE("native"),
        GLOBAL("global"),
        UNKNOWN("unknown");

        private final String value;
        InstallMethod(String v) { this.value = v; }
    }

    public enum DiffTool {
        TERMINAL("terminal"),
        AUTO("auto");

        private final String value;
        DiffTool(String v) { this.value = v; }
    }

    /**
     * Per-project configuration stored in .claude/settings.local.json.
     * Mirrors ProjectConfig in config.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectConfig {
        @JsonProperty("allowedTools")
        private List<String> allowedTools = new ArrayList<>();

        @JsonProperty("mcpContextUris")
        private List<String> mcpContextUris = new ArrayList<>();

        @JsonProperty("hasTrustDialogAccepted")
        private Boolean hasTrustDialogAccepted = false;

        @JsonProperty("projectOnboardingSeenCount")
        private int projectOnboardingSeenCount = 0;

        @JsonProperty("hasClaudeMdExternalIncludesApproved")
        private Boolean hasClaudeMdExternalIncludesApproved = false;

        @JsonProperty("hasClaudeMdExternalIncludesWarningShown")
        private Boolean hasClaudeMdExternalIncludesWarningShown = false;

        @JsonProperty("enabledMcpjsonServers")
        private List<String> enabledMcpjsonServers = new ArrayList<>();

        @JsonProperty("disabledMcpjsonServers")
        private List<String> disabledMcpjsonServers = new ArrayList<>();

        @JsonProperty("disabledMcpServers")
        private List<String> disabledMcpServers = new ArrayList<>();

        @JsonProperty("enabledMcpServers")
        private List<String> enabledMcpServers = new ArrayList<>();

        @JsonProperty("lastSessionId")
        private String lastSessionId;

        @JsonProperty("lastCost")
        private Double lastCost;

        @JsonProperty("lastDuration")
        private Long lastDuration;

        @JsonProperty("lastTotalInputTokens")
        private Long lastTotalInputTokens;

        @JsonProperty("lastTotalOutputTokens")
        private Long lastTotalOutputTokens;

        @JsonProperty("hasCompletedProjectOnboarding")
        private Boolean hasCompletedProjectOnboarding;

        @JsonProperty("remoteControlSpawnMode")
        private String remoteControlSpawnMode;

        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> v) { allowedTools = v; }
        public List<String> getMcpContextUris() { return mcpContextUris; }
        public void setMcpContextUris(List<String> v) { mcpContextUris = v; }
        public boolean isHasTrustDialogAccepted() { return hasTrustDialogAccepted; }
        public void setHasTrustDialogAccepted(Boolean v) { hasTrustDialogAccepted = v; }
        public int getProjectOnboardingSeenCount() { return projectOnboardingSeenCount; }
        public void setProjectOnboardingSeenCount(int v) { projectOnboardingSeenCount = v; }
        public boolean isHasClaudeMdExternalIncludesApproved() { return hasClaudeMdExternalIncludesApproved; }
        public void setHasClaudeMdExternalIncludesApproved(Boolean v) { hasClaudeMdExternalIncludesApproved = v; }
        public boolean isHasClaudeMdExternalIncludesWarningShown() { return hasClaudeMdExternalIncludesWarningShown; }
        public void setHasClaudeMdExternalIncludesWarningShown(Boolean v) { hasClaudeMdExternalIncludesWarningShown = v; }
        public List<String> getEnabledMcpjsonServers() { return enabledMcpjsonServers; }
        public void setEnabledMcpjsonServers(List<String> v) { enabledMcpjsonServers = v; }
        public List<String> getDisabledMcpjsonServers() { return disabledMcpjsonServers; }
        public void setDisabledMcpjsonServers(List<String> v) { disabledMcpjsonServers = v; }
        public List<String> getDisabledMcpServers() { return disabledMcpServers; }
        public void setDisabledMcpServers(List<String> v) { disabledMcpServers = v; }
        public List<String> getEnabledMcpServers() { return enabledMcpServers; }
        public void setEnabledMcpServers(List<String> v) { enabledMcpServers = v; }
        public String getLastSessionId() { return lastSessionId; }
        public void setLastSessionId(String v) { lastSessionId = v; }
        public Double getLastCost() { return lastCost; }
        public void setLastCost(Double v) { lastCost = v; }
        public Long getLastDuration() { return lastDuration; }
        public void setLastDuration(Long v) { lastDuration = v; }
        public Long getLastTotalInputTokens() { return lastTotalInputTokens; }
        public void setLastTotalInputTokens(Long v) { lastTotalInputTokens = v; }
        public Long getLastTotalOutputTokens() { return lastTotalOutputTokens; }
        public void setLastTotalOutputTokens(Long v) { lastTotalOutputTokens = v; }
        public boolean isHasCompletedProjectOnboarding() { return hasCompletedProjectOnboarding; }
        public void setHasCompletedProjectOnboarding(Boolean v) { hasCompletedProjectOnboarding = v; }
        public String getRemoteControlSpawnMode() { return remoteControlSpawnMode; }
        public void setRemoteControlSpawnMode(String v) { remoteControlSpawnMode = v; }
    

    }

    /**
     * Global configuration stored in ~/.claude.json.
     * Mirrors GlobalConfig in config.ts — includes only the most commonly accessed fields.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GlobalConfig {
        @JsonProperty("numStartups")
        private int numStartups = 0;

        @JsonProperty("autoUpdates")
        private Boolean autoUpdates;

        @JsonProperty("verbose")
        private boolean verbose = false;

        @JsonProperty("autoCompactEnabled")
        private boolean autoCompactEnabled = true;

        @JsonProperty("showTurnDuration")
        private boolean showTurnDuration = false;

        @JsonProperty("theme")
        private String theme = "dark";

        @JsonProperty("userID")
        private String userID;

        @JsonProperty("preferredNotifChannel")
        private String preferredNotifChannel = "terminal";

        @JsonProperty("hasCompletedOnboarding")
        private Boolean hasCompletedOnboarding;

        @JsonProperty("installMethod")
        private String installMethod;

        @JsonProperty("primaryApiKey")
        private String primaryApiKey;

        @JsonProperty("editorMode")
        private String editorMode;

        @JsonProperty("bypassPermissionsModeAccepted")
        private Boolean bypassPermissionsModeAccepted;

        @JsonProperty("todoFeatureEnabled")
        private boolean todoFeatureEnabled = true;

        @JsonProperty("memoryUsageCount")
        private int memoryUsageCount = 0;

        @JsonProperty("promptQueueUseCount")
        private int promptQueueUseCount = 0;

        @JsonProperty("btwUseCount")
        private int btwUseCount = 0;

        @JsonProperty("messageIdleNotifThresholdMs")
        private int messageIdleNotifThresholdMs = 30_000;

        @JsonProperty("fileCheckpointingEnabled")
        private boolean fileCheckpointingEnabled = false;

        @JsonProperty("terminalProgressBarEnabled")
        private boolean terminalProgressBarEnabled = true;

        @JsonProperty("respectGitignore")
        private boolean respectGitignore = true;

        @JsonProperty("copyFullResponse")
        private boolean copyFullResponse = false;

        @JsonProperty("projects")
        private Map<String, ProjectConfig> projects = new HashMap<>();

        @JsonProperty("env")
        private Map<String, String> env = new HashMap<>();

        @JsonProperty("cachedStatsigGates")
        private Map<String, Boolean> cachedStatsigGates = new HashMap<>();

        @JsonProperty("tipsHistory")
        private Map<String, Long> tipsHistory = new HashMap<>();

        @JsonProperty("oauthAccount")
        private AccountInfo oauthAccount;

        @JsonProperty("claudeAiMcpEverConnected")
        private List<String> claudeAiMcpEverConnected;
        public List<String> getClaudeAiMcpEverConnected() { return claudeAiMcpEverConnected; }
        public void setClaudeAiMcpEverConnected(List<String> v) { claudeAiMcpEverConnected = v; }
        public GlobalConfig withClaudeAiMcpEverConnected(List<String> v) {
            GlobalConfig c = this;
            c.claudeAiMcpEverConnected = v;
            return c;
        }

        public static GlobalConfig defaults() {
            return new GlobalConfig();
        }
    }

    /**
     * Account info stored inside GlobalConfig.
     * Mirrors AccountInfo in config.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccountInfo {
        @JsonProperty("accountUuid")
        private String accountUuid;

        @JsonProperty("emailAddress")
        private String emailAddress;

        @JsonProperty("organizationUuid")
        private String organizationUuid;

        @JsonProperty("organizationName")
        private String organizationName;

        @JsonProperty("organizationRole")
        private String organizationRole;

        @JsonProperty("workspaceRole")
        private String workspaceRole;

        @JsonProperty("displayName")
        private String displayName;

        @JsonProperty("billingType")
        private String billingType;

        @JsonProperty("hasExtraUsageEnabled")
        private Boolean hasExtraUsageEnabled;

        public String getAccountUuid() { return accountUuid; }
        public void setAccountUuid(String v) { accountUuid = v; }
        public String getEmailAddress() { return emailAddress; }
        public void setEmailAddress(String v) { emailAddress = v; }
        public String getOrganizationUuid() { return organizationUuid; }
        public void setOrganizationUuid(String v) { organizationUuid = v; }
        public String getOrganizationName() { return organizationName; }
        public void setOrganizationName(String v) { organizationName = v; }
        public String getOrganizationRole() { return organizationRole; }
        public void setOrganizationRole(String v) { organizationRole = v; }
        public String getWorkspaceRole() { return workspaceRole; }
        public void setWorkspaceRole(String v) { workspaceRole = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { displayName = v; }
        public String getBillingType() { return billingType; }
        public void setBillingType(String v) { billingType = v; }
        public boolean isHasExtraUsageEnabled() { return hasExtraUsageEnabled; }
        public void setHasExtraUsageEnabled(Boolean v) { hasExtraUsageEnabled = v; }
    }

    // ------------------------------------------------------------------
    // Global config
    // ------------------------------------------------------------------

    private volatile GlobalConfig cachedGlobalConfig;

    /**
     * Update the global config by applying a transformation function.
     */
    public synchronized void updateConfig(java.util.function.UnaryOperator<GlobalConfig> updater) {
        GlobalConfig current = getGlobalConfig();
        GlobalConfig updated = updater.apply(current);
        cachedGlobalConfig = updated;
        saveGlobalConfig(updated);
    }

    /**
     * Reads the global config from ~/.claude.json.
     * Returns defaults on any parse error.
     * Mirrors getGlobalConfig() in config.ts
     */
    public GlobalConfig getGlobalConfig() {
        if (cachedGlobalConfig != null) return cachedGlobalConfig;

        Path configPath = getGlobalConfigPath();
        if (!Files.exists(configPath)) {
            cachedGlobalConfig = GlobalConfig.defaults();
            return cachedGlobalConfig;
        }

        try {
            String raw = Files.readString(configPath);
            String stripped = stripBOM(raw);
            GlobalConfig config = objectMapper.readValue(stripped, GlobalConfig.class);
            cachedGlobalConfig = config != null ? config : GlobalConfig.defaults();
        } catch (Exception e) {
            log.warn("[ConfigService] Could not read global config at {}: {}", configPath, e.getMessage());
            cachedGlobalConfig = GlobalConfig.defaults();
        }
        return cachedGlobalConfig;
    }

    /**
     * Saves the global config to ~/.claude.json.
     * Mirrors saveGlobalConfig() in config.ts
     */
    public void saveGlobalConfig(GlobalConfig config) {
        Path configPath = getGlobalConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            cachedGlobalConfig = config;
            log.debug("[ConfigService] Saved global config to {}", configPath);
        } catch (Exception e) {
            log.error("[ConfigService] Failed to save global config: {}", e.getMessage(), e);
        }
    }

    /**
     * Invalidates the in-memory cache so the next call re-reads from disk.
     */
    public void invalidateCache() {
        cachedGlobalConfig = null;
    }

    // ------------------------------------------------------------------
    // Project config
    // ------------------------------------------------------------------

    /**
     * Returns the project config for the given cwd (reads from global config's projects map).
     * Mirrors getProjectConfig() in config.ts
     */
    public ProjectConfig getProjectConfig(String cwd) {
        GlobalConfig global = getGlobalConfig();
        String key = normalizeConfigKey(cwd);
        return global.getProjects().getOrDefault(key, new ProjectConfig());
    }

    /**
     * Saves the project config for the given cwd.
     * Mirrors saveProjectConfig() in config.ts
     */
    public void saveProjectConfig(String cwd, ProjectConfig projectConfig) {
        GlobalConfig global = getGlobalConfig();
        String key = normalizeConfigKey(cwd);
        global.getProjects().put(key, projectConfig);
        saveGlobalConfig(global);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Returns the path to the global config file.
     * Mirrors getGlobalClaudeFile() in env.ts
     */
    public Path getGlobalConfigPath() {
        String home = System.getProperty("user.home");
        return Path.of(home, GLOBAL_CONFIG_FILENAME);
    }

    /**
     * Normalises a path to use as a key in the projects map.
     * Mirrors normalizePathForConfigKey() in path.ts
     */
    private String normalizeConfigKey(String path) {
        if (path == null) return "";
        return path.replace(File.separatorChar, '/');
    }

    /**
     * Strips a UTF-8 BOM character from the beginning of a string.
     * Mirrors stripBOM() in jsonRead.ts
     */
    private static String stripBOM(String s) {
        if (s != null && s.startsWith("\uFEFF")) {
            return s.substring(1);
        }
        return s;
    }
}
