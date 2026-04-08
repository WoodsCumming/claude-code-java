package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Chrome command service — models the '/chrome' slash-command metadata and
 * delegates to {@link ClaudeInChromeService}.
 * Translated from src/commands/chrome/index.ts
 *
 * <p>The TypeScript source registers a lazy-loaded local-jsx command with the
 * name "chrome", available only on the 'claude-ai' surface, and disabled when
 * the session is non-interactive. It exposes Claude in Chrome (Beta) settings.
 */
@Slf4j
@Service
public class ChromeCommandService {



    /** Command name. Translated from: name: 'chrome' in index.ts */
    public static final String NAME = "chrome";

    /** Command description. Translated from: description in index.ts */
    public static final String DESCRIPTION = "Claude in Chrome (Beta) settings";

    /**
     * Availability surfaces.
     * Translated from: availability: ['claude-ai'] in index.ts
     */
    public static final String[] AVAILABILITY = {"claude-ai"};

    /** Command type. Translated from: type: 'local-jsx' in index.ts */
    public static final String TYPE = "local-jsx";

    private final ClaudeInChromeService claudeInChromeService;
    private final BootstrapStateService bootstrapStateService;

    @Autowired
    public ChromeCommandService(ClaudeInChromeService claudeInChromeService,
                                 BootstrapStateService bootstrapStateService) {
        this.claudeInChromeService = claudeInChromeService;
        this.bootstrapStateService = bootstrapStateService;
    }

    /**
     * Determine whether the command should be enabled.
     * Translated from: isEnabled: () => !getIsNonInteractiveSession() in index.ts
     *
     * @return true when the current session is interactive
     */
    public boolean isEnabled() {
        return !bootstrapStateService.isNonInteractiveSession();
    }

    /**
     * Open the Claude in Chrome settings UI.
     * Translated from the lazy load of chrome.js in index.ts
     */
    public void openSettings() {
        if (!isEnabled()) {
            log.warn("Chrome command called in a non-interactive session — skipping");
            return;
        }
        log.info("Opening Claude in Chrome settings");
        claudeInChromeService.openChromeSettings();
    }
}
