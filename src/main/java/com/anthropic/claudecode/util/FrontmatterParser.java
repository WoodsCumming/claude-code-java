package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/**
 * Frontmatter parser for markdown files.
 * Translated from src/utils/frontmatterParser.ts
 */
@Slf4j
public class FrontmatterParser {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FrontmatterParser.class);


    public static class FrontmatterData {
        private List<String> allowedTools;
        private String description;
        private String type;
        private String argumentHint;
        private String whenToUse;
        private String version;
        private Boolean hideFromSlashCommandTool;
        private String model;
        private String skills;
        private Boolean userInvocable;
        private String effort;
        private String context; // "inline" | "fork"
        private String agent;
        private List<String> paths;
        private String shell;
        private Map<String, Object> extra;

        public FrontmatterData() {}

        // Getters
        public List<String> getAllowedTools() { return allowedTools; }
        public String getDescription() { return description; }
        public String getType() { return type; }
        public String getArgumentHint() { return argumentHint; }
        public String getWhenToUse() { return whenToUse; }
        public String getVersion() { return version; }
        public Boolean getHideFromSlashCommandTool() { return hideFromSlashCommandTool; }
        public String getModel() { return model; }
        public String getSkills() { return skills; }
        public Boolean getUserInvocable() { return userInvocable; }
        public String getEffort() { return effort; }
        public String getContext() { return context; }
        public String getAgent() { return agent; }
        public List<String> getPaths() { return paths; }
        public String getShell() { return shell; }
        public Map<String, Object> getExtra() { return extra; }
        // Setters
        public void setAllowedTools(List<String> v) { allowedTools = v; }
        public void setDescription(String v) { description = v; }
        public void setType(String v) { type = v; }
        public void setArgumentHint(String v) { argumentHint = v; }
        public void setWhenToUse(String v) { whenToUse = v; }
        public void setVersion(String v) { version = v; }
        public void setHideFromSlashCommandTool(Boolean v) { hideFromSlashCommandTool = v; }
        public void setModel(String v) { model = v; }
        public void setSkills(String v) { skills = v; }
        public void setUserInvocable(Boolean v) { userInvocable = v; }
        public void setEffort(String v) { effort = v; }
        public void setContext(String v) { context = v; }
        public void setAgent(String v) { agent = v; }
        public void setPaths(List<String> v) { paths = v; }
        public void setShell(String v) { shell = v; }
        public void setExtra(Map<String, Object> v) { extra = v; }

        /** Get a value by key (supports built-in fields and extra map). */
        public Object get(String key) {
            if (extra != null && extra.containsKey(key)) return extra.get(key);
            return switch (key) {
                case "name", "allowedTools" -> allowedTools;
                case "description" -> description;
                case "type" -> type;
                case "argument_hint", "argumentHint" -> argumentHint;
                case "when_to_use", "whenToUse" -> whenToUse;
                case "version" -> version;
                case "hide_from_slash_command_tool" -> hideFromSlashCommandTool;
                case "model" -> model;
                case "skills" -> skills;
                case "user_invocable" -> userInvocable;
                case "effort" -> effort;
                case "context" -> context;
                case "agent" -> agent;
                case "paths" -> paths;
                case "shell" -> shell;
                default -> null;
            };
        }

        /** Get a value by key with a default fallback. */
        public Object getOrDefault(String key, Object defaultValue) {
            Object val = get(key);
            return val != null ? val : defaultValue;
        }

        /** Check if a key exists. */
        public boolean containsKey(String key) {
            return get(key) != null || (extra != null && extra.containsKey(key));
        }
    }

    /**
     * Parse frontmatter from markdown content.
     * Translated from parseFrontmatter() in frontmatterParser.ts
     */
    public static FrontmatterData parseFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) {
            return new FrontmatterData();
        }

        int end = content.indexOf("---", 3);
        if (end < 0) return new FrontmatterData();

        String frontmatterText = content.substring(3, end).trim();
        return parseFrontmatterText(frontmatterText);
    }

    /**
     * Remove frontmatter from content.
     */
    public static String removeFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) return content;
        int end = content.indexOf("---", 3);
        if (end < 0) return content;
        return content.substring(end + 3).trim();
    }

    /**
     * Parse frontmatter text (YAML-like format).
     */
    private static FrontmatterData parseFrontmatterText(String text) {
        FrontmatterData data = new FrontmatterData();
        Map<String, Object> extra = new LinkedHashMap<>();

        for (String line : text.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;

            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            switch (key) {
                case "description" -> data.setDescription(value);
                case "type" -> data.setType(value);
                case "argument-hint" -> data.setArgumentHint(value);
                case "when_to_use" -> data.setWhenToUse(value);
                case "version" -> data.setVersion(value);
                case "model" -> data.setModel(value);
                case "skills" -> data.setSkills(value);
                case "effort" -> data.setEffort(value);
                case "context" -> data.setContext(value);
                case "agent" -> data.setAgent(value);
                case "shell" -> data.setShell(value);
                case "user-invocable" -> data.setUserInvocable("true".equalsIgnoreCase(value));
                case "hide-from-slash-command-tool" -> data.setHideFromSlashCommandTool("true".equalsIgnoreCase(value));
                case "allowed-tools" -> {
                    if (value.contains(",")) {
                        data.setAllowedTools(Arrays.asList(value.split(",\\s*")));
                    } else if (!value.isEmpty()) {
                        data.setAllowedTools(List.of(value));
                    }
                }
                default -> extra.put(key, value);
            }
        }

        data.setExtra(extra);
        return data;
    }

    private FrontmatterParser() {}

    /**
     * Coerce a frontmatter description value to a String.
     * Returns null if the value cannot be coerced.
     */
    public static String coerceDescriptionToString(Object rawDesc, String fallback) {
        if (rawDesc instanceof String s && !s.isBlank()) return s;
        if (rawDesc instanceof java.util.List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return null;
    }

    /**
     * Result of parseFrontmatter with both body and frontmatter map.
     */
    public record ParsedFrontmatter(Map<String, Object> frontmatter, String body) {}

    /**
     * Parse frontmatter and return both the frontmatter map and the body text.
     */
    public static ParsedFrontmatter parseFrontmatterWithBody(String content) {
        if (content == null || !content.startsWith("---")) {
            return new ParsedFrontmatter(Map.of(), content != null ? content : "");
        }
        int end = content.indexOf("---", 3);
        if (end < 0) return new ParsedFrontmatter(Map.of(), content);

        String frontmatterText = content.substring(3, end).trim();
        String body = content.substring(end + 3).trim();

        // Build frontmatter map from FrontmatterData
        FrontmatterData data = parseFrontmatterText(frontmatterText);
        Map<String, Object> fm = new LinkedHashMap<>();
        if (data.getDescription() != null) fm.put("description", data.getDescription());
        if (data.getType() != null) fm.put("type", data.getType());
        if (data.getModel() != null) fm.put("model", data.getModel());
        if (data.getWhenToUse() != null) fm.put("whenToUse", data.getWhenToUse());
        if (data.getExtra() != null) fm.putAll(data.getExtra());
        return new ParsedFrontmatter(fm, body);
    }
}
