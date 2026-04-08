package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.PermissionModeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Permission setup service.
 * Translated from src/utils/permissions/permissionSetup.ts
 *
 * Initializes the permission context from settings and CLI arguments.
 */
@Slf4j
@Service
public class PermissionSetupService {



    private final SettingsService settingsService;

    @Autowired
    public PermissionSetupService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Create the initial tool permission context.
     * Translated from createToolPermissionContext() in permissionSetup.ts
     */
    public ToolPermissionContext createToolPermissionContext(
            String projectPath,
            String permissionModeStr,
            List<String> allowedTools,
            List<String> disallowedTools) {

        PermissionMode mode = PermissionModeUtils.permissionModeFromString(permissionModeStr);

        // Load settings
        Map<String, Object> settings = settingsService.getMergedSettings(projectPath);

        // Build permission rules from settings
        Map<String, List<String>> alwaysAllowRules = new HashMap<>();
        Map<String, List<String>> alwaysDenyRules = new HashMap<>();
        Map<String, List<String>> alwaysAskRules = new HashMap<>();

        // Apply allowed tools
        if (allowedTools != null && !allowedTools.isEmpty()) {
            alwaysAllowRules.put("cliArg", new ArrayList<>(allowedTools));
        }

        // Apply disallowed tools
        if (disallowedTools != null && !disallowedTools.isEmpty()) {
            alwaysDenyRules.put("cliArg", new ArrayList<>(disallowedTools));
        }

        return ToolPermissionContext.builder()
            .mode(mode)
            .additionalWorkingDirectories(Map.of())
            .alwaysAllowRules(alwaysAllowRules)
            .alwaysDenyRules(alwaysDenyRules)
            .alwaysAskRules(alwaysAskRules)
            .isBypassPermissionsModeAvailable(mode == PermissionMode.BYPASS_PERMISSIONS)
            .build();
    }

    /**
     * Parse tool list from CLI argument.
     * Translated from parseToolListFromCLI() in permissionSetup.ts
     */
    public List<String> parseToolListFromCLI(String toolListStr) {
        if (toolListStr == null || toolListStr.isBlank()) return List.of();
        return Arrays.asList(toolListStr.split(","));
    }
}
