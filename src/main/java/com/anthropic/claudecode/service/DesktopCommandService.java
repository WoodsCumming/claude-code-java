package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Desktop command service — models the '/desktop' slash-command metadata and
 * delegates to {@link ClaudeDesktopService}.
 * Translated from src/commands/desktop/index.ts
 *
 * <p>The TypeScript source registers a lazy-loaded local-jsx command with the
 * name "desktop" and the alias "app", available only on the 'claude-ai' surface.
 * The command is only enabled (and visible) on supported platforms: macOS and
 * 64-bit Windows. It continues the current session in Claude Desktop.
 */
@Slf4j
@Service
public class DesktopCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DesktopCommandService.class);


    /** Command name. Translated from: name: 'desktop' in index.ts */
    public static final String NAME = "desktop";

    /**
     * Command aliases.
     * Translated from: aliases: ['app'] in index.ts
     */
    public static final List<String> ALIASES = List.of("app");

    /** Command description. Translated from: description in index.ts */
    public static final String DESCRIPTION = "Continue the current session in Claude Desktop";

    /**
     * Availability surfaces.
     * Translated from: availability: ['claude-ai'] in index.ts
     */
    public static final String[] AVAILABILITY = {"claude-ai"};

    /** Command type. Translated from: type: 'local-jsx' in index.ts */
    public static final String TYPE = "local-jsx";

    private final ClaudeDesktopService claudeDesktopService;

    @Autowired
    public DesktopCommandService(ClaudeDesktopService claudeDesktopService) {
        this.claudeDesktopService = claudeDesktopService;
    }

    /**
     * Check whether the current OS/architecture combination is supported.
     * Translated from isSupportedPlatform() in index.ts
     *
     * <p>macOS (darwin) and 64-bit Windows (win32 + x64) are supported.
     * All other platforms return false.
     *
     * @return true when Claude Desktop handoff is available on this platform
     */
    public static boolean isSupportedPlatform() {
        String os   = System.getProperty("os.name",  "").toLowerCase();
        String arch = System.getProperty("os.arch",  "").toLowerCase();

        // Translated from: if (process.platform === 'darwin') return true
        if (os.contains("mac") || os.contains("darwin")) {
            return true;
        }
        // Translated from: if (process.platform === 'win32' && process.arch === 'x64') return true
        if (os.contains("win")) {
            return arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        }
        return false;
    }

    /**
     * Determine whether the command is enabled on the current platform.
     * Translated from: isEnabled: isSupportedPlatform in index.ts
     */
    public boolean isEnabled() {
        return isSupportedPlatform();
    }

    /**
     * Whether the command is hidden from the command palette.
     * Translated from: get isHidden() { return !isSupportedPlatform() } in index.ts
     */
    public boolean isHidden() {
        return !isSupportedPlatform();
    }

    /**
     * Continue the current session in Claude Desktop.
     * Translated from the lazy load of desktop.js in index.ts
     */
    public void openInDesktop() {
        if (!isEnabled()) {
            log.warn("Desktop command is not supported on this platform: os={}, arch={}",
                System.getProperty("os.name"), System.getProperty("os.arch"));
            System.err.println("Error: Claude Desktop is not supported on this platform.");
            return;
        }
        log.info("Handing off to Claude Desktop");
        claudeDesktopService.openInDesktop();
    }
}
