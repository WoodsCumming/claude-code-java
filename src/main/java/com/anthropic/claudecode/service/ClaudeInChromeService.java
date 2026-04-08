package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PlatformUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Claude in Chrome integration service.
 * Translated from src/utils/claudeInChrome/
 *
 * Manages the Claude in Chrome browser extension integration.
 */
@Slf4j
@Service
public class ClaudeInChromeService {



    public static final String CLAUDE_IN_CHROME_MCP_SERVER_NAME = "claude-in-chrome";

    /**
     * Check if a Chromium-based browser is available.
     * Translated from findChromiumBrowser() in common.ts
     */
    public Optional<String> findChromiumBrowser() {
        List<String> browsers = List.of(
            "google-chrome", "chromium", "chromium-browser",
            "google-chrome-stable", "chrome"
        );

        for (String browser : browsers) {
            try {
                ProcessBuilder pb = new ProcessBuilder("which", browser);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    String path = new String(process.getInputStream().readAllBytes()).trim();
                    if (!path.isEmpty()) return Optional.of(path);
                }
            } catch (Exception ignored) {}
        }

        return Optional.empty();
    }

    /**
     * Check if Claude in Chrome should be auto-enabled.
     * Translated from shouldAutoEnableClaudeInChrome() in setup.ts
     */
    public boolean shouldAutoEnableClaudeInChrome() {
        if (!PlatformUtils.isMacOS() && !PlatformUtils.isLinux()) return false;
        return findChromiumBrowser().isPresent();
    }

    /** Open the Claude-in-Chrome settings page. */
    public void openChromeSettings() {
        // Stub: open chrome settings for Claude integration.
    }
}
