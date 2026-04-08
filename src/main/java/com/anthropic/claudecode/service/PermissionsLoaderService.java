package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Permission rules loader service.
 * Translated from src/utils/permissions/permissionsLoader.ts
 *
 * Loads permission rules from settings files.
 */
@Slf4j
@Service
public class PermissionsLoaderService {



    private final SettingsService settingsService;

    @Autowired
    public PermissionsLoaderService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Check if only managed permission rules should be respected.
     * Translated from shouldAllowManagedPermissionRulesOnly() in permissionsLoader.ts
     */
    public boolean shouldAllowManagedPermissionRulesOnly() {
        Map<String, Object> policySettings = settingsService.getUserSettings();
        Object value = policySettings.get("allowManagedPermissionRulesOnly");
        return Boolean.TRUE.equals(value);
    }

    /**
     * Check if "always allow" options should be shown.
     * Translated from shouldShowAlwaysAllowOptions() in permissionsLoader.ts
     */
    public boolean shouldShowAlwaysAllowOptions() {
        return !shouldAllowManagedPermissionRulesOnly();
    }

    /**
     * Load all permission rules from disk.
     * Translated from loadAllPermissionRulesFromDisk() in permissionsLoader.ts
     */
    public PermissionRules loadAllPermissionRulesFromDisk(String projectPath) {
        Map<String, List<String>> alwaysAllow = new HashMap<>();
        Map<String, List<String>> alwaysDeny = new HashMap<>();
        Map<String, List<String>> alwaysAsk = new HashMap<>();

        // Load from each settings source
        for (SettingSource source : SettingSource.ALL_SOURCES) {
            loadRulesFromSource(source, projectPath, alwaysAllow, alwaysDeny, alwaysAsk);
        }

        return new PermissionRules(alwaysAllow, alwaysDeny, alwaysAsk);
    }

    private void loadRulesFromSource(
            SettingSource source,
            String projectPath,
            Map<String, List<String>> alwaysAllow,
            Map<String, List<String>> alwaysDeny,
            Map<String, List<String>> alwaysAsk) {

        Map<String, Object> settings = switch (source) {
            case USER_SETTINGS -> settingsService.getUserSettings();
            case PROJECT_SETTINGS -> settingsService.getProjectSettings(projectPath);
            case LOCAL_SETTINGS -> settingsService.getLocalSettings(projectPath);
            default -> Map.of();
        };

        Object permissions = settings.get("permissions");
        if (!(permissions instanceof Map)) return;

        Map<String, Object> perms = (Map<String, Object>) permissions;

        loadRuleList(perms, "allow", source.getValue(), alwaysAllow);
        loadRuleList(perms, "deny", source.getValue(), alwaysDeny);
        loadRuleList(perms, "ask", source.getValue(), alwaysAsk);
    }

    private void loadRuleList(
            Map<String, Object> perms,
            String key,
            String source,
            Map<String, List<String>> target) {

        Object rules = perms.get(key);
        if (!(rules instanceof List)) return;

        List<String> ruleList = new ArrayList<>();
        for (Object rule : (List<?>) rules) {
            if (rule instanceof String) {
                ruleList.add((String) rule);
            }
        }

        if (!ruleList.isEmpty()) {
            target.put(source, ruleList);
        }
    }

    public record PermissionRules(
        Map<String, List<String>> alwaysAllow,
        Map<String, List<String>> alwaysDeny,
        Map<String, List<String>> alwaysAsk
    ) {}
}
