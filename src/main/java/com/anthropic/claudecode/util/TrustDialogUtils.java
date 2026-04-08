package com.anthropic.claudecode.util;

import java.util.List;
import java.util.Set;

/**
 * Trust dialog utilities.
 * Translated from src/components/TrustDialog/utils.ts
 *
 * Helpers for inspecting settings files to determine what security-relevant
 * configuration is present, used by the Trust Dialog UI to show which
 * sources have hooks, bash permissions, env vars, and external helpers.
 */
public class TrustDialogUtils {

    // -------------------------------------------------------------------------
    // Safe environment variable constants
    // -------------------------------------------------------------------------

    /**
     * Environment variable names considered safe (not shown as "dangerous").
     * Mirrors SAFE_ENV_VARS from managedEnvConstants.ts
     */
    private static final Set<String> SAFE_ENV_VARS = Set.of(
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_AUTH_TOKEN",
        "CLAUDE_CODE_API_KEY_HELPER",
        "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_VERTEX",
        "CLAUDE_CODE_SKIP_BEDROCK_TLS_VERIFY",
        "AWS_REGION",
        "AWS_ACCESS_KEY_ID",
        "AWS_SECRET_ACCESS_KEY",
        "AWS_SESSION_TOKEN",
        "GOOGLE_CLOUD_PROJECT",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
        "CLAUDE_CODE_DISABLE_TELEMETRY",
        "NO_COLOR",
        "FORCE_COLOR",
        "TERM",
        "PATH"
    );

    private static final String BASH_TOOL_NAME = "Bash";

    // -------------------------------------------------------------------------
    // Hooks presence check
    // -------------------------------------------------------------------------

    /**
     * Returns true when the given settings have at least one active hook
     * configured.
     *
     * @param settings settings JSON representation; null treated as empty
     */
    private static boolean hasHooks(SettingsView settings) {
        if (settings == null || settings.disableAllHooks()) return false;
        if (settings.statusLine() != null) return true;
        if (settings.fileSuggestion() != null) return true;
        if (settings.hooks() == null || settings.hooks().isEmpty()) return false;
        for (List<?> hookConfig : settings.hooks().values()) {
            if (!hookConfig.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Returns the settings-file paths that have hooks configured.
     * Translated from getHooksSources() in utils.ts
     *
     * @param projectSettings settings from {@code .claude/settings.json}
     * @param localSettings   settings from {@code .claude/settings.local.json}
     * @return list of file paths that have hooks
     */
    public static List<String> getHooksSources(SettingsView projectSettings, SettingsView localSettings) {
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        if (hasHooks(projectSettings)) sources.add(".claude/settings.json");
        if (hasHooks(localSettings))   sources.add(".claude/settings.local.json");
        return List.copyOf(sources);
    }

    // -------------------------------------------------------------------------
    // Bash permission check
    // -------------------------------------------------------------------------

    /**
     * Returns true when any rule in {@code rules} is an allow rule for the
     * Bash tool (or a Bash tool with arguments).
     */
    private static boolean hasBashPermission(List<PermissionRuleView> rules) {
        if (rules == null) return false;
        return rules.stream().anyMatch(rule ->
            "allow".equals(rule.ruleBehavior())
            && (BASH_TOOL_NAME.equals(rule.toolName())
                || rule.toolName().startsWith(BASH_TOOL_NAME + "("))
        );
    }

    /**
     * Returns the settings-file paths that have a Bash allow rule.
     * Translated from getBashPermissionSources() in utils.ts
     *
     * @param projectRules permission rules from project settings
     * @param localRules   permission rules from local settings
     * @return list of file paths that have bash permissions
     */
    public static List<String> getBashPermissionSources(
            List<PermissionRuleView> projectRules,
            List<PermissionRuleView> localRules) {
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        if (hasBashPermission(projectRules)) sources.add(".claude/settings.json");
        if (hasBashPermission(localRules))   sources.add(".claude/settings.local.json");
        return List.copyOf(sources);
    }

    // -------------------------------------------------------------------------
    // formatListWithAnd
    // -------------------------------------------------------------------------

    /**
     * Formats a list of items with a natural-language "and" conjunction.
     * When {@code limit} is non-null and positive, items beyond the limit are
     * collapsed into "N more".
     *
     * Translated from formatListWithAnd() in utils.ts
     *
     * @param items list of string items
     * @param limit optional display limit (null or 0 = no limit)
     * @return formatted string
     */
    public static String formatListWithAnd(List<String> items, Integer limit) {
        if (items == null || items.isEmpty()) return "";

        // limit == 0 is treated as "no limit" in the TypeScript source
        Integer effectiveLimit = (limit == null || limit == 0) ? null : limit;

        List<String> display;
        int remaining = 0;

        if (effectiveLimit == null || items.size() <= effectiveLimit) {
            display = items;
        } else {
            display = items.subList(0, effectiveLimit);
            remaining = items.size() - effectiveLimit;
        }

        String joined;
        if (display.size() == 1) {
            joined = display.get(0);
        } else if (display.size() == 2) {
            joined = display.get(0) + " and " + display.get(1);
        } else {
            String allButLast = String.join(", ", display.subList(0, display.size() - 1));
            joined = allButLast + ", and " + display.get(display.size() - 1);
        }

        if (remaining > 0) {
            if (display.size() == 1) {
                return joined + " and " + remaining + " more";
            }
            return joined.replaceAll(", and [^,]+$", "") + ", and " + remaining + " more";
        }
        return joined;
    }

    /** Overload with no limit. */
    public static String formatListWithAnd(List<String> items) {
        return formatListWithAnd(items, null);
    }

    // -------------------------------------------------------------------------
    // otelHeadersHelper / apiKeyHelper / AWS / GCP / dangerous env vars
    // -------------------------------------------------------------------------

    /**
     * Returns the settings-file paths that have {@code otelHeadersHelper} configured.
     */
    public static List<String> getOtelHeadersHelperSources(SettingsView projectSettings, SettingsView localSettings) {
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        if (projectSettings != null && projectSettings.otelHeadersHelper() != null) sources.add(".claude/settings.json");
        if (localSettings   != null && localSettings.otelHeadersHelper()   != null) sources.add(".claude/settings.local.json");
        return List.copyOf(sources);
    }

    /**
     * Returns the settings-file paths that have {@code apiKeyHelper} configured.
     */
    public static List<String> getApiKeyHelperSources(SettingsView projectSettings, SettingsView localSettings) {
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        if (projectSettings != null && projectSettings.apiKeyHelper() != null) sources.add(".claude/settings.json");
        if (localSettings   != null && localSettings.apiKeyHelper()   != null) sources.add(".claude/settings.local.json");
        return List.copyOf(sources);
    }

    /**
     * Returns the settings-file paths that have AWS commands configured
     * ({@code awsAuthRefresh} or {@code awsCredentialExport}).
     */
    public static List<String> getAwsCommandsSources(SettingsView projectSettings, SettingsView localSettings) {
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        if (hasAwsCommands(projectSettings)) sources.add(".claude/settings.json");
        if (hasAwsCommands(localSettings))   sources.add(".claude/settings.local.json");
        return List.copyOf(sources);
    }

    private static boolean hasAwsCommands(SettingsView s) {
        return s != null && (s.awsAuthRefresh() != null || s.awsCredentialExport() != null);
    }

    /**
     * Returns the settings-file paths that have {@code gcpAuthRefresh} configured.
     */
    public static List<String> getGcpCommandsSources(SettingsView projectSettings, SettingsView localSettings) {
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        if (projectSettings != null && projectSettings.gcpAuthRefresh() != null) sources.add(".claude/settings.json");
        if (localSettings   != null && localSettings.gcpAuthRefresh()   != null) sources.add(".claude/settings.local.json");
        return List.copyOf(sources);
    }

    /**
     * Returns the settings-file paths that have dangerous env vars configured.
     * An env var is dangerous when its upper-cased name is not in
     * {@link #SAFE_ENV_VARS}.
     */
    public static List<String> getDangerousEnvVarsSources(SettingsView projectSettings, SettingsView localSettings) {
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        if (hasDangerousEnvVars(projectSettings)) sources.add(".claude/settings.json");
        if (hasDangerousEnvVars(localSettings))   sources.add(".claude/settings.local.json");
        return List.copyOf(sources);
    }

    private static boolean hasDangerousEnvVars(SettingsView s) {
        if (s == null || s.env() == null || s.env().isEmpty()) return false;
        return s.env().keySet().stream().anyMatch(k -> !SAFE_ENV_VARS.contains(k.toUpperCase()));
    }

    // -------------------------------------------------------------------------
    // View interfaces (lightweight projections used by callers)
    // -------------------------------------------------------------------------

    /**
     * Read-only projection of a settings JSON object used by the trust-dialog
     * helpers. Corresponds to SettingsJson in the TypeScript source.
     */
    public interface SettingsView {
        Boolean disableAllHooks();
        Object statusLine();
        Object fileSuggestion();
        java.util.Map<String, List<?>> hooks();
        String otelHeadersHelper();
        String apiKeyHelper();
        String awsAuthRefresh();
        String awsCredentialExport();
        String gcpAuthRefresh();
        java.util.Map<String, String> env();
    }

    /**
     * Read-only projection of a permission rule used by hasBashPermission.
     * Corresponds to PermissionRule in the TypeScript source.
     */
    public interface PermissionRuleView {
        String ruleBehavior();
        String toolName();
    }

    private TrustDialogUtils() {}
}
