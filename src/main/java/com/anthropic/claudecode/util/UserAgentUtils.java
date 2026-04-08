package com.anthropic.claudecode.util;

/**
 * User-Agent string helpers.
 * Translated from src/utils/userAgent.ts
 *
 * Kept dependency-free so SDK-bundled or transport layer code can import
 * without pulling in auth or its transitive dependency tree.
 */
public final class UserAgentUtils {

    /**
     * Get the Claude Code User-Agent string for HTTP requests.
     * Translated from getClaudeCodeUserAgent() in userAgent.ts
     *
     * @param version The application version (e.g. "2.1.70").
     * @return User-Agent header value, e.g. {@code "claude-code/2.1.70"}.
     */
    public static String getClaudeCodeUserAgent(String version) {
        return "claude-code/" + version;
    }

    /**
     * Get the Claude Code User-Agent using the version from system properties.
     * Falls back to "unknown" if the version is not configured.
     */
    public static String getClaudeCodeUserAgent() {
        String version = System.getProperty("app.version", "unknown");
        return getClaudeCodeUserAgent(version);
    }

    private UserAgentUtils() {}
}
