package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.GlobalConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Memory path management service for auto-memory directories.
 * Translated from src/utils/memdirPaths.ts and related utilities.
 *
 * Manages paths for automatically-extracted memories and checks whether
 * auto-memory is enabled.
 */
@Slf4j
@Service
public class MemoryPathService {



    /** Directory name used under ~/.claude/ for automatic memories. */
    private static final String AUTO_MEM_DIR = "memories";

    private final GlobalConfigService globalConfigService;

    @Autowired
    public MemoryPathService(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
    }

    /**
     * Check whether the auto-memory feature is enabled.
     * Translated from isAutoMemoryEnabled() in memdirPaths.ts
     *
     * @return true when auto-memory writes are allowed
     */
    public boolean isAutoMemoryEnabled() {
        try {
            GlobalConfig config = globalConfigService.getGlobalConfig();
            // Auto-memory is enabled by default; only off when explicitly disabled.
            return !Boolean.TRUE.equals(config.getAutoMemoryDisabled());
        } catch (Exception e) {
            log.debug("[MemoryPathService] Could not read config: {}", e.getMessage());
            return true; // default enabled
        }
    }

    /**
     * Return the absolute path to the auto-memory directory.
     * Translated from getAutoMemPath() in memdirPaths.ts
     */
    public String getAutoMemPath() {
        return getClaudeConfigDir() + File.separator + AUTO_MEM_DIR;
    }

    /**
     * Return true if the given path is inside the auto-memory directory.
     * Translated from isAutoMemPath() in memdirPaths.ts
     *
     * @param path an absolute or relative path string
     * @return true if the resolved path is under the auto-memory directory
     */
    public boolean isAutoMemPath(String path) {
        if (path == null) return false;
        try {
            Path resolved = Path.of(path).toAbsolutePath().normalize();
            Path memDir = Path.of(getAutoMemPath()).toAbsolutePath().normalize();
            return resolved.startsWith(memDir);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensure the auto-memory directory exists.
     */
    public void ensureAutoMemDir() {
        try {
            Files.createDirectories(Path.of(getAutoMemPath()));
        } catch (Exception e) {
            log.debug("[MemoryPathService] Could not create auto-mem dir: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String getClaudeConfigDir() {
        String dir = System.getenv("CLAUDE_CONFIG_HOME");
        if (dir != null && !dir.isBlank()) return dir;
        return System.getProperty("user.home") + File.separator + ".claude";
    }
}
