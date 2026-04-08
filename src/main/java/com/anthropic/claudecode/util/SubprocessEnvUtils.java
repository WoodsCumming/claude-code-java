package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Subprocess environment utilities.
 * Translated from src/utils/subprocessEnv.ts
 *
 * Manages environment variables for subprocess execution,
 * scrubbing sensitive variables in GitHub Actions.
 */
public class SubprocessEnvUtils {

    // Env vars to scrub from subprocess environments in GitHub Actions
    private static final Set<String> GHA_SUBPROCESS_SCRUB = Set.of(
        "ANTHROPIC_API_KEY",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_FOUNDRY_API_KEY",
        "ANTHROPIC_CUSTOM_HEADERS",
        "OTEL_EXPORTER_OTLP_HEADERS",
        "OTEL_EXPORTER_OTLP_LOGS_HEADERS",
        "OTEL_EXPORTER_OTLP_METRICS_HEADERS",
        "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
        "AWS_SECRET_ACCESS_KEY",
        "AWS_SESSION_TOKEN",
        "AWS_BEARER_TOKEN_BEDROCK",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "AZURE_CLIENT_SECRET",
        "AZURE_CLIENT_CERTIFICATE_PATH",
        "ACTIONS_ID_TOKEN_REQUEST_TOKEN",
        "ACTIONS_ID_TOKEN_REQUEST_URL"
    );

    /**
     * Get the subprocess environment.
     * Translated from subprocessEnv() in subprocessEnv.ts
     *
     * Returns the current environment, optionally scrubbing sensitive vars.
     */
    public static Map<String, String> getSubprocessEnv() {
        Map<String, String> env = new HashMap<>(System.getenv());

        // Scrub sensitive vars in GitHub Actions
        if (isRunningInGitHubActions()) {
            for (String var : GHA_SUBPROCESS_SCRUB) {
                env.remove(var);
            }
        }

        return env;
    }

    /**
     * Check if running in GitHub Actions.
     */
    public static boolean isRunningInGitHubActions() {
        return "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
    }

    private SubprocessEnvUtils() {}
}
