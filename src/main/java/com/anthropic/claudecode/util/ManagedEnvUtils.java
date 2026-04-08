package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Managed environment variable utilities.
 * Translated from src/utils/managedEnv.ts
 *
 * Handles the multi-phase application of environment variables from settings
 * to the running process, respecting SSH-tunnel, host-managed-provider, and
 * CCD spawn-env isolation rules.
 */
@Slf4j
public class ManagedEnvUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ManagedEnvUtils.class);


    // =========================================================================
    // SSH tunnel vars — must not be overridden when ANTHROPIC_UNIX_SOCKET is set
    // =========================================================================

    private static final Set<String> SSH_TUNNEL_VARS = Set.of(
            "ANTHROPIC_UNIX_SOCKET",
            "ANTHROPIC_BASE_URL",
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN",
            "CLAUDE_CODE_OAUTH_TOKEN"
    );

    /**
     * Strip SSH-tunnel auth vars from the settings env when ANTHROPIC_UNIX_SOCKET
     * is present in the process environment.
     * Translated from withoutSSHTunnelVars() in managedEnv.ts
     */
    public static Map<String, String> withoutSSHTunnelVars(Map<String, String> env) {
        if (env == null) return Map.of();
        if (System.getenv("ANTHROPIC_UNIX_SOCKET") == null) return env;

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!SSH_TUNNEL_VARS.contains(e.getKey())) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    // =========================================================================
    // Host-managed provider vars
    // =========================================================================

    /**
     * Strip provider-selection / model-default vars from settings env when
     * CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST is set in the process environment.
     * Translated from withoutHostManagedProviderVars() in managedEnv.ts
     */
    public static Map<String, String> withoutHostManagedProviderVars(Map<String, String> env) {
        if (env == null) return Map.of();
        if (!isEnvTruthy("CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST")) return env;

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!isProviderManagedEnvVar(e.getKey())) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    // =========================================================================
    // CCD spawn-env key filtering
    // =========================================================================

    /**
     * Snapshot of env keys present at JVM start (analogous to the CCD spawn-env
     * in the Node.js version). Only populated when running as claude-desktop.
     * Keys in this set are never overridden by settings.env.
     */
    private static volatile Set<String> ccdSpawnEnvKeys = null;
    private static volatile boolean ccdSpawnEnvCaptured = false;

    /**
     * Capture the CCD spawn-env key snapshot on first call.
     * Translated from the lazy-capture logic in applySafeConfigEnvironmentVariables().
     */
    public static void captureCcdSpawnEnvIfNeeded() {
        if (ccdSpawnEnvCaptured) return;
        ccdSpawnEnvCaptured = true;
        String entrypoint = System.getenv("CLAUDE_CODE_ENTRYPOINT");
        if ("claude-desktop".equals(entrypoint)) {
            // Capture all keys currently present in the process environment
            ccdSpawnEnvKeys = Set.copyOf(System.getenv().keySet());
        } else {
            ccdSpawnEnvKeys = null;
        }
    }

    /**
     * Strip keys that were in the CCD spawn-env snapshot from a settings env map.
     * Translated from withoutCcdSpawnEnvKeys() in managedEnv.ts
     */
    public static Map<String, String> withoutCcdSpawnEnvKeys(Map<String, String> env) {
        if (env == null) return Map.of();
        Set<String> spawnKeys = ccdSpawnEnvKeys;
        if (spawnKeys == null || spawnKeys.isEmpty()) return env;

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!spawnKeys.contains(e.getKey())) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    // =========================================================================
    // Composed filter
    // =========================================================================

    /**
     * Apply all strip filters to a settings-sourced env map.
     * Translated from filterSettingsEnv() in managedEnv.ts
     */
    public static Map<String, String> filterSettingsEnv(Map<String, String> env) {
        return withoutCcdSpawnEnvKeys(
                withoutHostManagedProviderVars(
                        withoutSSHTunnelVars(env)));
    }

    // =========================================================================
    // Trusted setting sources
    // =========================================================================

    /**
     * Setting sources whose env vars may be applied before trust is established.
     * Translated from TRUSTED_SETTING_SOURCES in managedEnv.ts
     */
    public enum SettingSource {
        USER_SETTINGS,
        FLAG_SETTINGS,
        POLICY_SETTINGS,
        PROJECT_SETTINGS,
        LOCAL_SETTINGS
    }

    // =========================================================================
    // Apply helpers
    // =========================================================================

    /**
     * Apply a filtered settings env map on top of a base environment map,
     * returning a new map (does not mutate the system environment).
     *
     * Use applyConfigEnvironmentVariables() for the safe/trusted phase,
     * or applyAllConfigEnvironmentVariables() for the full (post-trust) phase.
     */
    public static Map<String, String> applyFilteredEnv(Map<String, String> base,
                                                        Map<String, String> settingsEnv) {
        if (settingsEnv == null || settingsEnv.isEmpty()) return base;
        Map<String, String> result = new LinkedHashMap<>(base);
        result.putAll(filterSettingsEnv(settingsEnv));
        return result;
    }

    /**
     * Apply only safe (allowlisted) env vars from a settings env map.
     * Translated from the safe-env loop inside applySafeConfigEnvironmentVariables().
     *
     * @param base       starting environment
     * @param settingsEnv raw env from the merged settings object
     * @param safeVars   upper-cased allowlist of safe variable names
     */
    public static Map<String, String> applySafeEnvVars(Map<String, String> base,
                                                        Map<String, String> settingsEnv,
                                                        Set<String> safeVars) {
        if (settingsEnv == null || settingsEnv.isEmpty()) return base;
        Map<String, String> filtered = filterSettingsEnv(settingsEnv);
        Map<String, String> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, String> e : filtered.entrySet()) {
            if (safeVars.contains(e.getKey().toUpperCase(Locale.ROOT))) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    // =========================================================================
    // Provider-managed env var check
    // =========================================================================

    /**
     * Returns true if the variable name is controlled by the host provider and
     * should not be overridden by user/project settings when host-managed mode
     * is active.
     * Translated from isProviderManagedEnvVar() in managedEnvConstants.ts (referenced here).
     */
    public static boolean isProviderManagedEnvVar(String varName) {
        if (varName == null) return false;
        String upper = varName.toUpperCase(Locale.ROOT);
        // The TS implementation uses a dedicated constant set; we replicate the
        // intent: vars that select the provider or default model are host-managed.
        return upper.startsWith("ANTHROPIC_")
                || upper.startsWith("CLAUDE_CODE_USE_")
                || upper.startsWith("AWS_BEDROCK_")
                || upper.startsWith("VERTEX_");
    }

    // =========================================================================
    // Env truthiness check
    // =========================================================================

    /**
     * Returns true when the named environment variable is set to a truthy value
     * (non-empty, not "0", not "false").
     * Translated from isEnvTruthy() in envUtils.ts (used here).
     */
    public static boolean isEnvTruthy(String varName) {
        String val = System.getenv(varName);
        if (val == null || val.isBlank()) return false;
        return !"0".equals(val) && !"false".equalsIgnoreCase(val);
    }

    private ManagedEnvUtils() {}
}
