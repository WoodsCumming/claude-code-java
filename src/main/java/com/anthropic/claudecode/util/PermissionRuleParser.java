package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Permission rule parsing utilities.
 * Translated from src/utils/permissions/permissionRuleParser.ts
 */
public class PermissionRuleParser {

    // Legacy tool name aliases for backwards compatibility
    private static final Map<String, String> LEGACY_TOOL_NAME_ALIASES = Map.of(
        "Task", "Agent",
        "KillShell", "TaskStop",
        "AgentOutputTool", "TaskOutput",
        "BashOutputTool", "TaskOutput"
    );

    /**
     * Normalize a legacy tool name to its canonical name.
     * Translated from normalizeLegacyToolName() in permissionRuleParser.ts
     */
    public static String normalizeLegacyToolName(String name) {
        return LEGACY_TOOL_NAME_ALIASES.getOrDefault(name, name);
    }

    /**
     * Get legacy names for a canonical tool name.
     * Translated from getLegacyToolNames() in permissionRuleParser.ts
     */
    public static List<String> getLegacyToolNames(String canonicalName) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : LEGACY_TOOL_NAME_ALIASES.entrySet()) {
            if (entry.getValue().equals(canonicalName)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Escape special characters in rule content.
     * Translated from escapeRuleContent() in permissionRuleParser.ts
     */
    public static String escapeRuleContent(String content) {
        if (content == null) return "";
        return content
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)");
    }

    /**
     * Unescape rule content.
     * Translated from unescapeRuleContent() in permissionRuleParser.ts
     */
    public static String unescapeRuleContent(String content) {
        if (content == null) return "";
        return content
            .replace("\\)", ")")
            .replace("\\(", "(")
            .replace("\\\\", "\\");
    }

    /**
     * Parse a permission rule value from a string.
     * Format: "ToolName(content)" or just "ToolName"
     */
    public static PermissionRuleValue parsePermissionRuleValue(String ruleStr) {
        if (ruleStr == null || ruleStr.isEmpty()) return null;

        int parenIdx = ruleStr.indexOf('(');
        if (parenIdx < 0) {
            return new PermissionRuleValue(normalizeLegacyToolName(ruleStr), null);
        }

        String toolName = ruleStr.substring(0, parenIdx);
        String content = ruleStr.substring(parenIdx + 1, ruleStr.length() - 1);

        return new PermissionRuleValue(
            normalizeLegacyToolName(toolName),
            unescapeRuleContent(content)
        );
    }

    /**
     * Convert a permission rule value to string.
     */
    public static String permissionRuleValueToString(PermissionRuleValue value) {
        if (value == null) return "";
        if (value.ruleContent() == null || value.ruleContent().isEmpty()) {
            return value.toolName();
        }
        return value.toolName() + "(" + escapeRuleContent(value.ruleContent()) + ")";
    }

    public record PermissionRuleValue(String toolName, String ruleContent) {}

    private PermissionRuleParser() {}
}
