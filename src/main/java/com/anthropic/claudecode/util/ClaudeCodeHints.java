package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.regex.*;

/**
 * Claude Code hints protocol.
 * Translated from src/utils/claudeCodeHints.ts
 *
 * Parses <claude-code-hint /> tags from tool output.
 */
@Slf4j
public class ClaudeCodeHints {



    private static final Pattern HINT_PATTERN =
        Pattern.compile("<claude-code-hint\\s+([^/]+)/>", Pattern.DOTALL);

    /**
     * Extract Claude Code hints from tool output.
     * Translated from extractClaudeCodeHints() in claudeCodeHints.ts
     */
    public static List<ClaudeCodeHint> extractClaudeCodeHints(String output, String sourceCommand) {
        if (output == null) return List.of();

        List<ClaudeCodeHint> hints = new ArrayList<>();
        Matcher m = HINT_PATTERN.matcher(output);

        while (m.find()) {
            String attrs = m.group(1);
            try {
                ClaudeCodeHint hint = parseHintAttributes(attrs, sourceCommand);
                if (hint != null) {
                    hints.add(hint);
                }
            } catch (Exception e) {
                log.debug("Could not parse hint: {}", e.getMessage());
            }
        }

        return hints;
    }

    /**
     * Strip hints from output.
     */
    public static String stripHints(String output) {
        if (output == null) return "";
        return HINT_PATTERN.matcher(output).replaceAll("");
    }

    private static ClaudeCodeHint parseHintAttributes(String attrs, String sourceCommand) {
        // Simple attribute parsing
        Map<String, String> parsed = new LinkedHashMap<>();
        Pattern attrPattern = Pattern.compile("(\\w+)=\"([^\"]*)\"");
        Matcher m = attrPattern.matcher(attrs);
        while (m.find()) {
            parsed.put(m.group(1), m.group(2));
        }

        String vStr = parsed.get("v");
        String type = parsed.get("type");
        String value = parsed.get("value");

        if (vStr == null || type == null || value == null) return null;

        int v;
        try {
            v = Integer.parseInt(vStr);
        } catch (NumberFormatException e) {
            return null;
        }

        if (v != 1) return null; // Only v1 supported
        if (!"plugin".equals(type)) return null;

        return new ClaudeCodeHint(v, type, value, sourceCommand);
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ClaudeCodeHint {
        private int v;
        private String type;
        private String value;
        private String sourceCommand;

        public int getV() { return v; }
        public void setV(int v) { v = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getValue() { return value; }
        public void setValue(String v) { value = v; }
        public String getSourceCommand() { return sourceCommand; }
        public void setSourceCommand(String v) { sourceCommand = v; }
    }

    private ClaudeCodeHints() {}
}
