package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PluginDirectories;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plugin flagging service.
 * Translated from src/utils/plugins/pluginFlagging.ts
 *
 * Tracks plugins that were auto-removed because they were delisted.
 */
@Slf4j
@Service
public class PluginFlaggingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginFlaggingService.class);


    private static final String FLAGGED_PLUGINS_FILE = "flagged-plugins.json";

    private final ObjectMapper objectMapper;
    private final List<FlaggedPlugin> cache = new CopyOnWriteArrayList<>();
    private volatile boolean cacheLoaded = false;

    @Autowired
    public PluginFlaggingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get all flagged plugins.
     * Translated from getFlaggedPlugins() in pluginFlagging.ts
     */
    public List<FlaggedPlugin> getFlaggedPlugins() {
        if (!cacheLoaded) {
            loadFlaggedPlugins();
        }
        return Collections.unmodifiableList(cache);
    }

    /**
     * Add a flagged plugin.
     * Translated from addFlaggedPlugin() in pluginFlagging.ts
     */
    public void addFlaggedPlugin(String pluginId, String reason) {
        FlaggedPlugin flagged = new FlaggedPlugin(pluginId, reason, System.currentTimeMillis());
        cache.add(flagged);
        saveFlaggedPlugins();
    }

    /**
     * Dismiss a flagged plugin.
     * Translated from dismissFlaggedPlugin() in pluginFlagging.ts
     */
    public void dismissFlaggedPlugin(String pluginId) {
        cache.removeIf(p -> pluginId.equals(p.getPluginId()));
        saveFlaggedPlugins();
    }

    private void loadFlaggedPlugins() {
        String path = PluginDirectories.getPluginsBaseDir() + "/" + FLAGGED_PLUGINS_FILE;
        try {
            File file = new File(path);
            if (!file.exists()) {
                cacheLoaded = true;
                return;
            }
            List<FlaggedPlugin> plugins = objectMapper.readValue(file,
                objectMapper.getTypeFactory().constructCollectionType(List.class, FlaggedPlugin.class));
            cache.clear();
            cache.addAll(plugins);
        } catch (Exception e) {
            log.debug("Could not load flagged plugins: {}", e.getMessage());
        }
        cacheLoaded = true;
    }

    private void saveFlaggedPlugins() {
        String path = PluginDirectories.getPluginsBaseDir() + "/" + FLAGGED_PLUGINS_FILE;
        try {
            new File(path).getParentFile().mkdirs();
            objectMapper.writeValue(new File(path), cache);
        } catch (Exception e) {
            log.debug("Could not save flagged plugins: {}", e.getMessage());
        }
    }

    @Data
    public static class FlaggedPlugin {
        private String pluginId;
        private String reason;
        private long timestamp;

        public FlaggedPlugin() {}
        public FlaggedPlugin(String pluginId, String reason, long timestamp) {
            this.pluginId = pluginId; this.reason = reason; this.timestamp = timestamp;
        }
        public String getPluginId() { return pluginId; }
        public void setPluginId(String v) { pluginId = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
    }
}
