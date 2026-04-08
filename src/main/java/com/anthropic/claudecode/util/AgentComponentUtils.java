package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.AgentComponentTypes.AgentSource;
import com.anthropic.claudecode.model.AgentComponentTypes.SettingSource;

/**
 * Utility helpers for agent UI components.
 *
 * <p>Translated from TypeScript {@code src/components/agents/utils.ts}.
 */
public final class AgentComponentUtils {

    private AgentComponentUtils() {}

    /**
     * Returns a human-readable display name for an agent source.
     *
     * <p>TypeScript original:
     * <pre>
     * export function getAgentSourceDisplayName(
     *   source: SettingSource | 'all' | 'built-in' | 'plugin',
     * ): string
     * </pre>
     *
     * <p>The TypeScript version accepted a string union; here we accept an {@link AgentSource}
     * enum value that already covers all variants ({@code ALL} is represented by passing
     * {@code null}).
     *
     * @param source the agent source; {@code null} is treated as "all"
     * @return display name
     */
    public static String getAgentSourceDisplayName(AgentSource source) {
        if (source == null) {
            return "Agents";
        }
        return switch (source) {
            case BUILT_IN       -> "Built-in agents";
            case PLUGIN         -> "Plugin agents";
            case USER_SETTINGS  -> "User Settings";
            case PROJECT_SETTINGS -> "Project Settings";
            case POLICY_SETTINGS  -> "Policy Settings";
            case LOCAL_SETTINGS   -> "Local Settings";
            case FLAG_SETTINGS    -> "Flag Settings";
        };
    }

    /**
     * Overload that accepts the raw string values used in the TypeScript codebase
     * ({@code "all"}, {@code "built-in"}, {@code "plugin"}) as well as the
     * {@link SettingSource} names.
     *
     * @param source string source identifier (case-insensitive for SettingSource names)
     * @return display name
     */
    public static String getAgentSourceDisplayName(String source) {
        if (source == null || source.equalsIgnoreCase("all")) {
            return "Agents";
        }
        return switch (source.toLowerCase()) {
            case "built-in"        -> "Built-in agents";
            case "plugin"          -> "Plugin agents";
            case "usersettings"    -> "User Settings";
            case "projectsettings" -> "Project Settings";
            case "policysettings"  -> "Policy Settings";
            case "localsettings"   -> "Local Settings";
            case "flagsettings"    -> "Flag Settings";
            default                -> capitalize(source);
        };
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase();
    }
}
