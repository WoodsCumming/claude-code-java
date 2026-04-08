package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.MdmSettingsConstants;
import com.anthropic.claudecode.util.PlatformUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MDM settings service.
 * Translated from src/utils/settings/mdm/settings.ts
 *
 * Reads enterprise settings from OS-level MDM configuration.
 */
@Slf4j
@Service
public class MdmSettingsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MdmSettingsService.class);


    private final ObjectMapper objectMapper;
    private volatile Map<String, Object> cachedSettings;

    @Autowired
    public MdmSettingsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get MDM settings.
     * Translated from getMdmSettings() in settings.ts
     */
    public Map<String, Object> getMdmSettings() {
        if (cachedSettings != null) return cachedSettings;

        Map<String, Object> settings = loadMdmSettings();
        cachedSettings = settings;
        return settings;
    }

    /**
     * Get HKCU settings (Windows user registry).
     * Translated from getHkcuSettings() in settings.ts
     */
    public Map<String, Object> getHkcuSettings() {
        if (!PlatformUtils.isWindows()) return Map.of();
        // In a full implementation, this would read from Windows registry
        return Map.of();
    }

    private Map<String, Object> loadMdmSettings() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            return loadMacOsMdmSettings();
        } else if (os.contains("win")) {
            return loadWindowsMdmSettings();
        } else {
            return loadLinuxMdmSettings();
        }
    }

    private Map<String, Object> loadMacOsMdmSettings() {
        // Try managed preferences plist
        File plist = new File(MdmSettingsConstants.MACOS_PLIST_PATH);
        if (!plist.exists()) return Map.of();

        // In a full implementation, this would parse the plist file
        log.debug("Found macOS MDM plist: {}", MdmSettingsConstants.MACOS_PLIST_PATH);
        return Map.of();
    }

    private Map<String, Object> loadWindowsMdmSettings() {
        // In a full implementation, this would read from Windows registry
        return Map.of();
    }

    private Map<String, Object> loadLinuxMdmSettings() {
        // Try /etc/claude-code/managed-settings.json
        File settingsFile = new File(MdmSettingsConstants.LINUX_MANAGED_SETTINGS_PATH);
        if (!settingsFile.exists()) return Map.of();

        try {
            return objectMapper.readValue(settingsFile, Map.class);
        } catch (Exception e) {
            log.debug("Could not read MDM settings from {}: {}", settingsFile, e.getMessage());
            return Map.of();
        }
    }
}
