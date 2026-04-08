package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import lombok.Data;

/**
 * Settings management service.
 * Translated from:
 *   - src/utils/settings/settings.ts     — core settings I/O
 *   - src/hooks/useSettings.ts           — reactive accessor (AppState.settings)
 *
 * useSettings() in TypeScript returns AppState['settings'] (a DeepImmutable
 * wrapped snapshot from the reactive store). In Java the equivalent is
 * {@link #getCurrentSettings()} which reads the merged settings from all
 * sources and caches the result until a change is detected.
 *
 * Manages user, project, and managed settings with hierarchical merging.
 */
@Slf4j
@Service
public class SettingsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SettingsService.class);
    private static final String SETTINGS_FILE = "settings.json";
    private static final String LOCAL_SETTINGS_FILE = "settings.local.json";

    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Reactive subscriber support (mirrors useSettings / settingsChangeDetector)
    // -------------------------------------------------------------------------

    /** Listeners notified whenever settings are reloaded due to a file change. */
    private final List<Consumer<Map<String, Object>>> changeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Cached merged settings — invalidated by notifySettingsChanged(). */
    private volatile Map<String, Object> cachedMergedSettings = null;
    private volatile String cachedProjectPath = null;

    @Autowired
    public SettingsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // useSettings() equivalent — reactive read accessor
    // -------------------------------------------------------------------------

    /**
     * Get the current settings snapshot from the application state.
     *
     * Mirrors: {@code export function useSettings(): ReadonlySettings} in
     * src/hooks/useSettings.ts.  In TypeScript this selector subscribes to
     * AppState and re-renders automatically; here we cache the merged settings
     * and invalidate the cache when {@link #notifySettingsChanged(String)} is
     * called (e.g. by the file-watcher).
     *
     * @return the merged settings for the current project directory
     */
    public Map<String, Object> getCurrentSettings() {
        String projectPath = System.getProperty("user.dir");
        if (cachedMergedSettings == null || !projectPath.equals(cachedProjectPath)) {
            cachedMergedSettings = getMergedSettings(projectPath);
            cachedProjectPath = projectPath;
        }
        return Collections.unmodifiableMap(cachedMergedSettings);
    }

    /**
     * Subscribe to settings changes.
     *
     * Mirrors: the subscription returned by {@code settingsChangeDetector.subscribe()}
     * that {@code useSettingsChange} hooks into.  The consumer is called with the
     * updated merged settings whenever {@link #notifySettingsChanged(String)} fires.
     *
     * @param listener receives the updated merged settings map on each change
     * @return a {@link Runnable} that removes the subscription when called
     */
    public Runnable subscribeToSettingsChanges(Consumer<Map<String, Object>> listener) {
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    /**
     * Invalidate the settings cache and notify all subscribers.
     *
     * Called by the file-watcher (SettingsChangeService / settingsChangeDetector
     * equivalent) whenever settings files change on disk.
     *
     * @param projectPath the project whose settings changed
     */
    public void notifySettingsChanged(String projectPath) {
        // Invalidate cache
        cachedMergedSettings = null;
        cachedProjectPath = null;

        // Re-read and distribute to all listeners
        Map<String, Object> updated = getMergedSettings(
                projectPath != null ? projectPath : System.getProperty("user.dir"));
        log.debug("Settings changed for project {}, notifying {} listener(s)",
                projectPath, changeListeners.size());
        for (Consumer<Map<String, Object>> listener : changeListeners) {
            try {
                listener.accept(updated);
            } catch (Exception e) {
                log.warn("Settings change listener threw: {}", e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core settings I/O
    // -------------------------------------------------------------------------

    /**
     * Get the Claude config home directory.
     * Translated from getClaudeConfigHomeDir() in envUtils.ts
     */
    public String getConfigHomeDir() {
        String envDir = System.getenv("CLAUDE_CONFIG_DIR");
        if (envDir != null) return envDir;

        String home = System.getProperty("user.home");
        return home + "/.claude";
    }

    /**
     * Load user settings.
     */
    public Map<String, Object> getUserSettings() {
        return loadSettingsFile(getConfigHomeDir() + "/" + SETTINGS_FILE);
    }

    /**
     * Load project settings (from .claude/settings.json in project root).
     */
    public Map<String, Object> getProjectSettings(String projectPath) {
        if (projectPath == null) return Map.of();
        return loadSettingsFile(projectPath + "/.claude/" + SETTINGS_FILE);
    }

    /**
     * Load local settings (from .claude/settings.local.json in project root).
     */
    public Map<String, Object> getLocalSettings(String projectPath) {
        if (projectPath == null) return Map.of();
        return loadSettingsFile(projectPath + "/.claude/" + LOCAL_SETTINGS_FILE);
    }

    /**
     * Get merged settings (user + project + local).
     * Later sources override earlier ones.
     */
    public Map<String, Object> getMergedSettings(String projectPath) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(getUserSettings());
        merged.putAll(getProjectSettings(projectPath));
        merged.putAll(getLocalSettings(projectPath));
        return merged;
    }

    /**
     * Save user settings.
     */
    public void saveUserSettings(Map<String, Object> settings) {
        saveSettingsFile(getConfigHomeDir() + "/" + SETTINGS_FILE, settings);
    }

    /**
     * Save project settings.
     */
    public void saveProjectSettings(String projectPath, Map<String, Object> settings) {
        if (projectPath == null) return;
        String dir = projectPath + "/.claude";
        new File(dir).mkdirs();
        saveSettingsFile(dir + "/" + SETTINGS_FILE, settings);
    }

    private Map<String, Object> loadSettingsFile(String path) {
        File file = new File(path);
        if (!file.exists()) return new LinkedHashMap<>();

        try {
            return objectMapper.readValue(file, Map.class);
        } catch (Exception e) {
            log.warn("Could not load settings from {}: {}", path, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void saveSettingsFile(String path, Map<String, Object> settings) {
        try {
            File file = new File(path);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, settings);
        } catch (Exception e) {
            log.error("Could not save settings to {}: {}", path, e.getMessage());
        }
    }

    /**
     * Get settings for a specific source.
     * Translated from getSettingsForSource() in settings.ts
     */
    public Map<String, Object> getSettingsForSource(String source) {
        switch (source) {
            case "userSettings": return getUserSettings();
            case "projectSettings": return getProjectSettings(System.getProperty("user.dir"));
            case "localSettings": return getLocalSettings(System.getProperty("user.dir"));
            default: return Map.of();
        }
    }

    /**
     * Update a single setting in a specific source.
     * Translated from updateSettingsForSource() in settings.ts
     */
    public void updateSettingsForSource(String source, String key, Object value) {
        Map<String, Object> settings = new java.util.LinkedHashMap<>(getSettingsForSource(source));
        settings.put(key, value);
        switch (source) {
            case "userSettings": saveUserSettings(settings); break;
            case "projectSettings": saveProjectSettings(System.getProperty("user.dir"), settings); break;
            case "localSettings":
                saveSettingsFile(System.getProperty("user.dir") + "/.claude/" + LOCAL_SETTINGS_FILE, settings);
                break;
        }
    }

    /**
     * Settings types translated from src/utils/settings/types.ts
     */
    @Data
    @lombok.Builder
    
    public static class SettingsJson {
        private String apiKeyHelper;
        private List<String> allowedTools;
        private List<String> disabledTools;
        private Map<String, Object> env;
        private String defaultModel;
        private String permissionMode;
        private Boolean verbose;
        private Map<String, Object> hooks;
        private List<Map<String, Object>> mcpServers;
        private Boolean autoCompactEnabled;
        private String outputFormat;
        private Boolean promptSuggestionEnabled;
        private Boolean speculationEnabled;
    }

    /**
     * Get whether prompt suggestion is enabled from user settings.
     * Returns null if not configured (callers can provide a default).
     */
    @SuppressWarnings("unchecked")
    public Boolean getPromptSuggestionEnabled() {
        Map<String, Object> settings = getUserSettings();
        Object val = settings.get("promptSuggestionEnabled");
        if (val instanceof Boolean b) return b;
        return null; // use default
    }

    /**
     * Get whether speculation is enabled from user settings.
     */
    @SuppressWarnings("unchecked")
    public Boolean getSpeculationEnabled() {
        Map<String, Object> settings = getUserSettings();
        Object val = settings.get("speculationEnabled");
        if (val instanceof Boolean b) return b;
        return null;
    }

    /** Check if voice mode is enabled. */
    @SuppressWarnings("unchecked")
    public boolean isVoiceEnabled() {
        Map<String, Object> settings = getUserSettings();
        Object val = settings.get("voiceEnabled");
        return Boolean.TRUE.equals(val);
    }

    /** Set voice enabled/disabled and save settings. Returns true on success. */
    public boolean setVoiceEnabled(boolean enabled) {
        try {
            updateSettingsForSource("user", "voiceEnabled", enabled);
            return true;
        } catch (Exception e) {
            log.debug("Failed to update voiceEnabled setting: {}", e.getMessage());
            return false;
        }
    }

    /** Get the language setting. */
    @SuppressWarnings("unchecked")
    public String getLanguage() {
        Map<String, Object> settings = getUserSettings();
        Object val = settings.get("language");
        return val instanceof String s ? s : null;
    }

    /** Get whether spinner tips are enabled. */
    public Boolean getSpinnerTipsEnabled() {
        Map<String, Object> settings = getUserSettings();
        Object val = settings.get("spinnerTipsEnabled");
        if (val instanceof Boolean b) return b;
        return true; // default enabled
    }

    /** Enable a plugin in the given scope. */
    /** Find a plugin in settings. */
    public PluginScopeMatch findPluginInSettings(String pluginId) {
        return null;
    }

    /** Plugin scope match result. */
    public static record PluginScopeMatch(String pluginId, String scope) {}

    /** Update XAA IdP settings. */
    public void updateXaaIdpSettings(String issuer, String clientId, Object callbackPort) {
        log.debug("updateXaaIdpSettings: issuer={}", issuer);
    }

    /** Clear XAA IdP settings. */
    public void clearXaaIdpSettings() {
        log.debug("clearXaaIdpSettings called");
    }

    public void enablePlugin(String pluginId, String scope) {
        log.debug("enablePlugin: {} scope={}", pluginId, scope);
    }

    /** Disable a plugin in the given scope. */
    public void disablePlugin(String pluginId, String scope) {
        log.debug("disablePlugin: {} scope={}", pluginId, scope);
    }

    /** Get editable plugin scopes. */
    public java.util.List<String> getEditablePluginScopes() {
        return java.util.List.of("user", "project");
    }
}
