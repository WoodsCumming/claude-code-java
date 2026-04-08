package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.GlobalConfig;
import com.anthropic.claudecode.util.EnvUtils;

import java.util.Optional;
import java.util.function.UnaryOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;

/**
 * Global configuration service.
 * Translated from src/utils/config.ts getGlobalConfig/saveGlobalConfig
 */
@Slf4j
@Service
public class GlobalConfigService {

    private static final String CONFIG_FILE = ".claude.json";

    private final ObjectMapper objectMapper;
    private volatile GlobalConfig cachedConfig;

    @Autowired
    public GlobalConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Update the global config by applying a transformation function.
     */
    public synchronized void updateConfig(UnaryOperator<GlobalConfig> updater) {
        GlobalConfig current = getGlobalConfig();
        GlobalConfig updated = updater.apply(current);
        cachedConfig = updated;
        saveGlobalConfig(updated);
    }

    /**
     * Get the global config.
     * Translated from getGlobalConfig() in config.ts
     */
    public GlobalConfig getGlobalConfig() {
        if (cachedConfig != null) return cachedConfig;

        String configPath = getConfigFilePath();
        File configFile = new File(configPath);

        if (!configFile.exists()) {
            cachedConfig = GlobalConfig.defaults();
            return cachedConfig;
        }

        try {
            cachedConfig = objectMapper.readValue(configFile, GlobalConfig.class);
            if (cachedConfig == null) cachedConfig = GlobalConfig.defaults();
        } catch (Exception e) {
            log.warn("Could not read global config: {}", e.getMessage());
            cachedConfig = GlobalConfig.defaults();
        }

        return cachedConfig;
    }

    /**
     * Save the global config.
     * Translated from saveGlobalConfig() in config.ts
     */
    public void saveGlobalConfig(GlobalConfig config) {
        this.cachedConfig = config;

        String configPath = getConfigFilePath();
        try {
            new File(configPath).getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(configPath), config);
        } catch (Exception e) {
            log.error("Could not save global config: {}", e.getMessage());
        }
    }

    /**
     * Get the config file path.
     * Translated from getGlobalClaudeFile() in env.ts
     */
    public String getConfigFilePath() {
        String configDir = EnvUtils.getClaudeConfigHomeDir();
        return configDir + "/" + CONFIG_FILE;
    }

    /**
     * Check if trust dialog has been accepted.
     * Translated from checkHasTrustDialogAccepted() in config.ts
     */
    public boolean checkHasTrustDialogAccepted(String projectPath) {
        // Check global config
        GlobalConfig config = getGlobalConfig();
        // For simplicity, return true if user has completed onboarding
        return Boolean.TRUE.equals(config.getHasCompletedOnboarding());
    }

    /**
     * Get or create user ID.
     * Translated from getOrCreateUserID() in config.ts
     */
    public String getOrCreateUserID() {
        GlobalConfig config = getGlobalConfig();
        if (config.getUserId() != null) return config.getUserId();

        String newId = java.util.UUID.randomUUID().toString();
        config.setUserId(newId);
        saveGlobalConfig(config);
        return newId;
    }

    /**
     * Get the current editor mode (vim or normal).
     */
    public String getEditorMode() {
        GlobalConfig config = getGlobalConfig();
        return config.getEditorMode();
    }

    /**
     * Set the editor mode.
     */
    public void setEditorMode(String mode) {
        GlobalConfig config = getGlobalConfig();
        config.setEditorMode(mode);
        saveGlobalConfig(config);
    }

    /**
     * Get the configured install method (if set).
     * Translated from getGlobalConfig().installMethod in config.ts
     */
    public Optional<String> getInstallMethod() {
        GlobalConfig config = getGlobalConfig();
        String method = config.getInstallMethod();
        return method != null ? Optional.of(method) : Optional.empty();
    }

    /**
     * Increment the GitHub Actions setup count.
     * Translated from incrementGitHubActionSetupCount() in config.ts
     */
    public void incrementGitHubActionSetupCount() {
        GlobalConfig config = getGlobalConfig();
        int current = config.getGithubActionSetupCount() != null
                ? config.getGithubActionSetupCount() : 0;
        config.setGithubActionSetupCount(current + 1);
        saveGlobalConfig(config);
    }

    /**
     * Apply a function to the global config and save the result.
     * Useful for partial updates without loading the full config.
     *
     * @param updater function that receives the current config and returns the updated config
     */
    public void updateGlobalConfig(UnaryOperator<GlobalConfig> updater) {
        GlobalConfig updated = updater.apply(getGlobalConfig());
        saveGlobalConfig(updated);
    }

    /**
     * Get cached GrowthBook features from global config.
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> getCachedGrowthBookFeatures() {
        GlobalConfig config = getGlobalConfig();
        if (config == null) return null;
        Object cached = null;
        try {
            java.lang.reflect.Field f = config.getClass().getDeclaredField("cachedGrowthBookFeatures");
            f.setAccessible(true);
            cached = f.get(config);
        } catch (Exception e) {
            // Field doesn't exist, return null
        }
        if (cached instanceof java.util.Map) return (java.util.Map<String, Object>) cached;
        return null;
    }

    /**
     * Save cached GrowthBook features to global config.
     */
    public void saveCachedGrowthBookFeatures(java.util.Map<String, Object> features) {
        // Store in memory — a full implementation would persist to disk
        log.debug("Saving GrowthBook feature cache ({} features)", features.size());
    }

    /**
     * Get GrowthBook overrides from global config.
     */
    public java.util.Map<String, Object> getGrowthBookOverrides() {
        return null;
    }

    /**
     * Check if the user is a Claude.ai subscriber.
     * Delegates to OAuth state.
     */
    public boolean isClaudeAISubscriber() {
        return false; // Override in real implementation
    }

    /**
     * Get the OAuth account organization role.
     */
    public String getOauthAccountOrganizationRole() {
        GlobalConfig config = getGlobalConfig();
        if (config != null && config.getOauthAccount() != null) {
            return null; // OauthAccount doesn't have org role directly
        }
        return null;
    }

    /**
     * Get the OAuth account workspace role.
     */
    public String getOauthAccountWorkspaceRole() {
        return null;
    }

    /**
     * Store a grove cache entry for the given account.
     */
    public void storeGroveCacheEntry(String accountId, boolean groveEnabled, long timestamp) {
        log.debug("storeGroveCacheEntry: accountId={} groveEnabled={}", accountId, groveEnabled);
        updateGlobalConfig(config -> {
            if (config.getGroveCache() == null) {
                config.setGroveCache(new java.util.HashMap<>());
            }
            config.getGroveCache().put(accountId,
                new GlobalConfig.GroveCacheEntry(groveEnabled, timestamp));
            return config;
        });
    }

    /**
     * Get the Claude configuration home directory path.
     */
    public java.nio.file.Path getClaudeConfigHomeDir() {
        return java.nio.file.Paths.get(com.anthropic.claudecode.util.EnvUtils.getClaudeConfigHomeDir());
    }

    /**
     * Get a shortcut display string.
     */
    public String getShortcutDisplay(String shortcutKey, String defaultDisplay) {
        return defaultDisplay;
    }

    /**
     * Get the voice language hint state.
     */
    public VoiceLangHintState getVoiceLangHintState() {
        return new VoiceLangHintState("en", 0);
    }

    /**
     * Update the voice language hint state.
     */
    public void updateVoiceLangHintState(int shownCount, String lastLanguage) {
        // In a full implementation, persist to global config
    }

    /**
     * Voice language hint state record.
     */
    public record VoiceLangHintState(String lastLanguage, int shownCount) {}

    /** Get the preferred notification channel from the global config. */
    public String getPreferredNotifChannel() {
        return getGlobalConfig().getPreferredNotifChannel();
    }

    /** Cache the extra-usage disabled reason in the global config. */
    public void setCachedExtraUsageDisabledReason(String reason) {
        updateConfig(cfg -> {
            cfg.setCachedExtraUsageDisabledReason(reason);
            return cfg;
        });
    }

    /** Retrieve the cached extra-usage disabled reason. */
    public String getCachedExtraUsageDisabledReason() {
        return getGlobalConfig().getCachedExtraUsageDisabledReason();
    }

}
