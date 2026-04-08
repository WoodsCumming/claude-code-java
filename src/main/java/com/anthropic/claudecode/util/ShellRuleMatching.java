package com.anthropic.claudecode.util;

import java.util.regex.*;

/**
 * Shell permission rule matching utilities.
 * Translated from src/utils/permissions/shellRuleMatching.ts
 */
public class ShellRuleMatching {

    /**
     * Check if a pattern has wildcards.
     * Translated from hasWildcards() in shellRuleMatching.ts
     */
    public static boolean hasWildcards(String pattern) {
        if (pattern == null) return false;
        if (pattern.endsWith(":*")) return false; // Legacy prefix syntax
        // Check for unescaped * anywhere
        return Pattern.compile("(?<!\\\\)\\*").matcher(pattern).find();
    }

    /**
     * Extract prefix from legacy :* syntax.
     * Translated from permissionRuleExtractPrefix() in shellRuleMatching.ts
     */
    public static String extractLegacyPrefix(String rule) {
        if (rule == null) return null;
        Matcher m = Pattern.compile("^(.+):\\*$").matcher(rule);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * Match a command against a wildcard pattern.
     * Translated from matchWildcardPattern() in shellRuleMatching.ts
     */
    public static boolean matchWildcardPattern(String command, String pattern) {
        if (command == null || pattern == null) return false;
        if ("*".equals(pattern)) return true;
        if (pattern.equals(command)) return true;

        // Check legacy prefix syntax
        String prefix = extractLegacyPrefix(pattern);
        if (prefix != null) {
            return command.startsWith(prefix);
        }

        // Convert wildcard pattern to regex
        // Escape special regex chars except *
        String escaped = Pattern.quote(pattern)
            .replace("\\*", ".*");

        try {
            return Pattern.compile("^" + escaped + "$").matcher(command).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Parse a shell permission rule.
     * Translated from parsePermissionRule() in shellRuleMatching.ts
     */
    public static ShellPermissionRule parsePermissionRule(String rule) {
        if (rule == null || rule.isEmpty()) return null;

        // Check for legacy prefix syntax
        String prefix = extractLegacyPrefix(rule);
        if (prefix != null) {
            return new ShellPermissionRule.Prefix(prefix);
        }

        // Check for wildcards
        if (hasWildcards(rule)) {
            return new ShellPermissionRule.Wildcard(rule);
        }

        // Exact match
        return new ShellPermissionRule.Exact(rule);
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    public sealed interface ShellPermissionRule permits
            ShellPermissionRule.Exact,
            ShellPermissionRule.Prefix,
            ShellPermissionRule.Wildcard {

        String getType();
        boolean matches(String command);

        record Exact(String command) implements ShellPermissionRule {
            @Override public String getType() { return "exact"; }
            @Override public boolean matches(String cmd) { return command.equals(cmd); }
        }

        record Prefix(String prefix) implements ShellPermissionRule {
            @Override public String getType() { return "prefix"; }
            @Override public boolean matches(String cmd) {
                return cmd != null && cmd.startsWith(prefix);
            }
        }

        record Wildcard(String pattern) implements ShellPermissionRule {
            @Override public String getType() { return "wildcard"; }
            @Override public boolean matches(String cmd) {
                return matchWildcardPattern(cmd, pattern);
            }
        }
    }

    private ShellRuleMatching() {}
}
