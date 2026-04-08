package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import java.util.*;

/**
 * Global Claude Code configuration.
 * Translated from GlobalConfig type in src/utils/config.ts
 */
public class GlobalConfig {

    @JsonProperty("numStartups")
    private int numStartups;

    @JsonProperty("installMethod")
    private String installMethod;

    @JsonProperty("autoUpdates")
    private Boolean autoUpdates;

    @JsonProperty("userID")
    private String userId;

    @JsonProperty("theme")
    private String theme;

    @JsonProperty("hasCompletedOnboarding")
    private Boolean hasCompletedOnboarding;

    @JsonProperty("mcpServers")
    private Map<String, Object> mcpServers;

    @JsonProperty("preferredNotifChannel")
    private String preferredNotifChannel;

    @JsonProperty("verbose")
    private boolean verbose;

    @JsonProperty("primaryApiKey")
    private String primaryApiKey;

    @JsonProperty("oauthAccount")
    private AccountInfo oauthAccount;

    @JsonProperty("editorMode")
    private String editorMode;

    @JsonProperty("bypassPermissionsModeAccepted")
    private Boolean bypassPermissionsModeAccepted;

    @JsonProperty("autoCompactEnabled")
    private boolean autoCompactEnabled;

    @JsonProperty("showTurnDuration")
    private boolean showTurnDuration;

    @JsonProperty("env")
    private Map<String, String> env;

    @JsonProperty("tipsHistory")
    private Map<String, Integer> tipsHistory;

    @JsonProperty("memoryUsageCount")
    private int memoryUsageCount;

    @JsonProperty("diffTool")
    private String diffTool;

    @JsonProperty("cachedExtraUsageDisabledReason")
    private String cachedExtraUsageDisabledReason;

    @JsonProperty("autoConnectIde")
    private Boolean autoConnectIde;

    @JsonProperty("appleTerminalSetupInProgress")
    private Boolean appleTerminalSetupInProgress;

    @JsonProperty("appleTerminalBackupPath")
    private String appleTerminalBackupPath;

    @JsonProperty("lspRecommendationDisabled")
    private Boolean lspRecommendationDisabled;

    @JsonProperty("lspRecommendationIgnoredCount")
    private Integer lspRecommendationIgnoredCount;

    @JsonProperty("lspRecommendationNeverPlugins")
    private List<String> lspRecommendationNeverPlugins;

    @JsonProperty("autoMemoryDisabled")
    private Boolean autoMemoryDisabled;

    @JsonProperty("githubActionSetupCount")
    private Integer githubActionSetupCount;

    @JsonProperty("iterm2SetupInProgress")
    private Boolean iterm2SetupInProgress;

    @JsonProperty("iterm2BackupPath")
    private String iterm2BackupPath;

    @JsonProperty("clientDataCache")
    private Map<String, Object> clientDataCache;

    @JsonProperty("additionalModelOptionsCache")
    private java.util.List<Object> additionalModelOptionsCache;

    public static class AccountInfo {
        private String accountUuid;
        private String emailAddress;
        private String organizationUuid;
        private String organizationName;
        private String organizationRole;
        private String workspaceRole;
        private String displayName;
        private Boolean hasExtraUsageEnabled;
        private String billingType;
        public AccountInfo() {}
        public String getAccountUuid() { return accountUuid; }
        public void setAccountUuid(String v) { this.accountUuid = v; }
        public String getEmailAddress() { return emailAddress; }
        public void setEmailAddress(String v) { this.emailAddress = v; }
        public String getOrganizationUuid() { return organizationUuid; }
        public void setOrganizationUuid(String v) { this.organizationUuid = v; }
        public String getOrganizationName() { return organizationName; }
        public void setOrganizationName(String v) { this.organizationName = v; }
        public String getOrganizationRole() { return organizationRole; }
        public void setOrganizationRole(String v) { this.organizationRole = v; }
        public String getWorkspaceRole() { return workspaceRole; }
        public void setWorkspaceRole(String v) { this.workspaceRole = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public Boolean getHasExtraUsageEnabled() { return hasExtraUsageEnabled; }
        public void setHasExtraUsageEnabled(Boolean v) { this.hasExtraUsageEnabled = v; }
        public String getBillingType() { return billingType; }
        public void setBillingType(String v) { this.billingType = v; }
    }

    @JsonProperty("speculationEnabled")
    private Boolean speculationEnabled;

    /** Grove cache: accountId -> GroveCacheEntry. */
    @JsonProperty("groveCache")
    private java.util.Map<String, GroveCacheEntry> groveCache;

    /** Get a grove cache entry for the given account. */
    public GroveCacheEntry getGroveCacheEntry(String accountId) {
        return groveCache != null ? groveCache.get(accountId) : null;
    }

    /** Grove cache entry. */
    public static class GroveCacheEntry {
        private boolean groveEnabled;
        private long timestamp;
        public GroveCacheEntry() {}
        public GroveCacheEntry(boolean groveEnabled, long timestamp) {
            this.groveEnabled = groveEnabled;
            this.timestamp = timestamp;
        }
        public boolean isGroveEnabled() { return groveEnabled; }
        public void setGroveEnabled(boolean v) { this.groveEnabled = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { this.timestamp = v; }
    }

    // Explicit setters/getters since Lombok @Data doesn't work in Java 22 context
    public int getNumStartups() { return numStartups; }
    public void setNumStartups(int v) { this.numStartups = v; }
    public String getInstallMethod() { return installMethod; }
    public void setInstallMethod(String v) { this.installMethod = v; }
    public Boolean getAutoUpdates() { return autoUpdates; }
    public void setAutoUpdates(Boolean v) { this.autoUpdates = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getTheme() { return theme; }
    public void setTheme(String v) { this.theme = v; }
    public Boolean getHasCompletedOnboarding() { return hasCompletedOnboarding; }
    public void setHasCompletedOnboarding(Boolean v) { this.hasCompletedOnboarding = v; }
    public Map<String, Object> getMcpServers() { return mcpServers; }
    public void setMcpServers(Map<String, Object> v) { this.mcpServers = v; }
    public String getPreferredNotifChannel() { return preferredNotifChannel; }
    public void setPreferredNotifChannel(String v) { this.preferredNotifChannel = v; }
    public boolean isVerbose() { return verbose; }
    public void setVerbose(boolean v) { this.verbose = v; }
    public String getPrimaryApiKey() { return primaryApiKey; }
    public void setPrimaryApiKey(String v) { this.primaryApiKey = v; }
    public AccountInfo getOauthAccount() { return oauthAccount; }
    public void setOauthAccount(AccountInfo v) { this.oauthAccount = v; }
    public String getEditorMode() { return editorMode; }
    public void setEditorMode(String v) { this.editorMode = v; }
    public Boolean getBypassPermissionsModeAccepted() { return bypassPermissionsModeAccepted; }
    public void setBypassPermissionsModeAccepted(Boolean v) { this.bypassPermissionsModeAccepted = v; }
    public boolean isAutoCompactEnabled() { return autoCompactEnabled; }
    public void setAutoCompactEnabled(boolean v) { this.autoCompactEnabled = v; }
    public boolean isShowTurnDuration() { return showTurnDuration; }
    public void setShowTurnDuration(boolean v) { this.showTurnDuration = v; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> v) { this.env = v; }
    public Map<String, Integer> getTipsHistory() { return tipsHistory; }
    public void setTipsHistory(Map<String, Integer> v) { this.tipsHistory = v; }
    public int getMemoryUsageCount() { return memoryUsageCount; }
    public void setMemoryUsageCount(int v) { this.memoryUsageCount = v; }
    public String getDiffTool() { return diffTool; }
    public void setDiffTool(String v) { this.diffTool = v; }
    public String getCachedExtraUsageDisabledReason() { return cachedExtraUsageDisabledReason; }
    public void setCachedExtraUsageDisabledReason(String v) { this.cachedExtraUsageDisabledReason = v; }
    public Boolean getAutoConnectIde() { return autoConnectIde; }
    public void setAutoConnectIde(Boolean v) { this.autoConnectIde = v; }
    public Boolean getAppleTerminalSetupInProgress() { return appleTerminalSetupInProgress; }
    public void setAppleTerminalSetupInProgress(Boolean v) { this.appleTerminalSetupInProgress = v; }
    public String getAppleTerminalBackupPath() { return appleTerminalBackupPath; }
    public void setAppleTerminalBackupPath(String v) { this.appleTerminalBackupPath = v; }
    public Boolean getLspRecommendationDisabled() { return lspRecommendationDisabled; }
    public void setLspRecommendationDisabled(Boolean v) { this.lspRecommendationDisabled = v; }
    public Integer getLspRecommendationIgnoredCount() { return lspRecommendationIgnoredCount; }
    public void setLspRecommendationIgnoredCount(Integer v) { this.lspRecommendationIgnoredCount = v; }
    public List<String> getLspRecommendationNeverPlugins() { return lspRecommendationNeverPlugins; }
    public void setLspRecommendationNeverPlugins(List<String> v) { this.lspRecommendationNeverPlugins = v; }
    public Boolean getAutoMemoryDisabled() { return autoMemoryDisabled; }
    public void setAutoMemoryDisabled(Boolean v) { this.autoMemoryDisabled = v; }
    public Integer getGithubActionSetupCount() { return githubActionSetupCount; }
    public void setGithubActionSetupCount(Integer v) { this.githubActionSetupCount = v; }
    public Boolean getIterm2SetupInProgress() { return iterm2SetupInProgress; }
    public void setIterm2SetupInProgress(Boolean v) { this.iterm2SetupInProgress = v; }
    public String getIterm2BackupPath() { return iterm2BackupPath; }
    public void setIterm2BackupPath(String v) { this.iterm2BackupPath = v; }
    public Map<String, Object> getClientDataCache() { return clientDataCache; }
    public void setClientDataCache(Map<String, Object> v) { this.clientDataCache = v; }
    public java.util.List<Object> getAdditionalModelOptionsCache() { return additionalModelOptionsCache; }
    public void setAdditionalModelOptionsCache(java.util.List<Object> v) { this.additionalModelOptionsCache = v; }
    public Boolean getSpeculationEnabled() { return speculationEnabled; }
    public void setSpeculationEnabled(Boolean v) { this.speculationEnabled = v; }
    public java.util.Map<String, GroveCacheEntry> getGroveCache() { return groveCache; }
    public void setGroveCache(java.util.Map<String, GroveCacheEntry> v) { this.groveCache = v; }

    private java.util.List<String> claudeAiMcpEverConnected;
    public java.util.List<String> getClaudeAiMcpEverConnected() { return claudeAiMcpEverConnected; }
    public void setClaudeAiMcpEverConnected(java.util.List<String> v) { claudeAiMcpEverConnected = v; }
    public GlobalConfig withClaudeAiMcpEverConnected(java.util.List<String> v) {
        setClaudeAiMcpEverConnected(v);
        return this;
    }

    public static GlobalConfig defaults() {
        GlobalConfig c = new GlobalConfig();
        c.setNumStartups(0);
        c.setVerbose(false);
        c.setAutoCompactEnabled(true);
        c.setShowTurnDuration(false);
        c.setPreferredNotifChannel("auto");
        c.setEnv(new HashMap<>());
        c.setTipsHistory(new HashMap<>());
        c.setMemoryUsageCount(0);
        return c;
    }
}
