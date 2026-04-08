package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;

/**
 * Add-dir plugin settings service.
 * Translated from src/utils/plugins/addDirPluginSettings.ts
 *
 * Reads plugin settings from --add-dir directories.
 */
@Slf4j
@Service
public class AddDirPluginSettingsService {



    private final ObjectMapper objectMapper;
    private final WorkingDirectoryService workingDirectoryService;

    @Autowired
    public AddDirPluginSettingsService(ObjectMapper objectMapper,
                                        WorkingDirectoryService workingDirectoryService) {
        this.objectMapper = objectMapper;
        this.workingDirectoryService = workingDirectoryService;
    }

    /**
     * Get enabled plugins from add-dir directories.
     * Translated from getAddDirEnabledPlugins() in addDirPluginSettings.ts
     */
    public Map<String, Boolean> getAddDirEnabledPlugins() {
        Map<String, Boolean> result = new LinkedHashMap<>();

        for (String dir : workingDirectoryService.getAllWorkingDirectories()) {
            Map<String, Object> settings = loadSettingsFromDir(dir);
            if (settings == null) continue;

            Object enabledPlugins = settings.get("enabledPlugins");
            if (enabledPlugins instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) enabledPlugins).entrySet()) {
                    if (entry.getValue() instanceof Boolean) {
                        result.put(String.valueOf(entry.getKey()), (Boolean) entry.getValue());
                    }
                }
            }
        }

        return result;
    }

    private Map<String, Object> loadSettingsFromDir(String dir) {
        File settingsFile = new File(dir + "/.claude/settings.json");
        if (!settingsFile.exists()) return null;

        try {
            return objectMapper.readValue(settingsFile, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
