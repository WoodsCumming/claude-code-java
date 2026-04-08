package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PlatformUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Claude Desktop integration service.
 * Translated from src/utils/claudeDesktop.ts
 *
 * Manages integration with the Claude Desktop app.
 */
@Slf4j
@Service
public class ClaudeDesktopService {



    private final ObjectMapper objectMapper;

    @Autowired
    public ClaudeDesktopService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get the Claude Desktop config path.
     * Translated from getClaudeDesktopConfigPath() in claudeDesktop.ts
     */
    public String getClaudeDesktopConfigPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");

        if (os.contains("mac")) {
            return home + "/Library/Application Support/Claude/claude_desktop_config.json";
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                return appData + "/Claude/claude_desktop_config.json";
            }
        } else if (os.contains("linux")) {
            // WSL or Linux
            String wslHome = System.getenv("WSLENV");
            if (wslHome != null) {
                // WSL - try Windows path
                return "/mnt/c/Users/" + System.getenv("USER") + "/AppData/Roaming/Claude/claude_desktop_config.json";
            }
        }

        throw new IllegalStateException("Unsupported platform for Claude Desktop integration");
    }

    /**
     * Read MCP servers from Claude Desktop config.
     * Translated from getMcpServersFromClaudeDesktop() in claudeDesktop.ts
     */
    public CompletableFuture<Map<String, Map<String, Object>>> getMcpServersFromClaudeDesktop() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String configPath = getClaudeDesktopConfigPath();
                File configFile = new File(configPath);
                if (!configFile.exists()) return Map.of();

                Map<String, Object> config = objectMapper.readValue(configFile, Map.class);
                Object mcpServers = config.get("mcpServers");
                if (!(mcpServers instanceof Map)) return Map.of();

                return (Map<String, Map<String, Object>>) mcpServers;
            } catch (Exception e) {
                log.debug("Could not read Claude Desktop config: {}", e.getMessage());
                return Map.of();
            }
        });
    }

    /**
     * Open Claude Desktop application.
     */
    public void openInDesktop() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                new ProcessBuilder("open", "-a", "Claude").start();
            } else if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "claude://").start();
            } else {
                log.warn("openInDesktop not supported on {}", os);
            }
        } catch (Exception e) {
            log.error("Failed to open Claude Desktop: {}", e.getMessage());
        }
    }
}
