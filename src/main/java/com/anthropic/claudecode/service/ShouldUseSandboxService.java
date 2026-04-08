package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Service to determine if sandbox should be used for bash commands.
 * Translated from src/tools/BashTool/shouldUseSandbox.ts
 *
 * Determines whether a bash command should run in a sandbox.
 */
@Slf4j
@Service
public class ShouldUseSandboxService {



    private final SandboxService sandboxService;
    private final SettingsService settingsService;

    @Autowired
    public ShouldUseSandboxService(SandboxService sandboxService,
                                    SettingsService settingsService) {
        this.sandboxService = sandboxService;
        this.settingsService = settingsService;
    }

    /**
     * Determine if sandbox should be used for a command.
     * Translated from shouldUseSandbox() in shouldUseSandbox.ts
     */
    public boolean shouldUseSandbox(String command, boolean dangerouslyDisableSandbox) {
        if (dangerouslyDisableSandbox) return false;
        if (!sandboxService.isSandboxEnabled()) return false;

        // Check if command is excluded
        if (containsExcludedCommand(command)) return false;

        return true;
    }

    private boolean containsExcludedCommand(String command) {
        if (command == null) return false;

        // Get excluded commands from settings
        Map<String, Object> settings = settingsService.getMergedSettings(null);
        Object excludedObj = settings.get("sandboxExcludedCommands");
        if (!(excludedObj instanceof List)) return false;

        List<String> excluded = (List<String>) excludedObj;
        for (String exc : excluded) {
            if (command.contains(exc)) return true;
        }
        return false;
    }
}
