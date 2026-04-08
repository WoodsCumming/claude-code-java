package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for expanding environment variables in MCP server configurations.
 * Translated from src/services/mcp/envExpansion.ts
 */
public final class EnvExpansion {

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    /**
     * Expand environment variables in a string value.
     * Handles {@code ${VAR}} and {@code ${VAR:-default}} syntax.
     * Translated from expandEnvVarsInString() in envExpansion.ts
     *
     * @param value the string to expand
     * @return an {@link EnvExpansionResult} with the expanded string and list of missing variables
     */
    public static ExpandResult expandEnvVarsInString(String value) {
        if (value == null) {
            return new ExpandResult("", List.of());
        }

        List<String> missingVars = new ArrayList<>();
        Matcher m = ENV_VAR_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String varContent = m.group(1);
            // Split on :- to support default values (limit to 2 parts to preserve :- in defaults)
            String[] parts = varContent.split(":-", 2);
            String varName = parts[0];
            String defaultValue = parts.length > 1 ? parts[1] : null;

            String envValue = System.getenv(varName);

            if (envValue != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(envValue));
            } else if (defaultValue != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(defaultValue));
            } else {
                // Track missing variable for error reporting.
                // Return original if not found (allows debugging but will be reported as error).
                missingVars.add(varName);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);

        return new ExpandResult(sb.toString(), List.copyOf(missingVars));
    }

    /**
     * Result of {@link #expandEnvVarsInString}.
     */
    public static class ExpandResult {
        private final String expanded;
        private final List<String> missingVars;
        public ExpandResult(String expanded, List<String> missingVars) {
            this.expanded = expanded; this.missingVars = missingVars;
        }
        public String getExpanded() { return expanded; }
        public List<String> getMissingVars() { return missingVars; }
        // Record-style accessors for backward compat
        public String expanded() { return expanded; }
        public List<String> missingVars() { return missingVars; }
    }

    /** Legacy alias type. */
    public record EnvExpansionResult(String expanded, List<String> missingVars) {}

    private EnvExpansion() {}
}
