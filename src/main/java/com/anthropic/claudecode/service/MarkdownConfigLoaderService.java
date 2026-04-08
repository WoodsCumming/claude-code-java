package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Markdown config loader service.
 * Loads YAML-frontmatter markdown files from .claude subdirectories across
 * all config scopes (project, user, policy, plugin).
 *
 * Translated from src/utils/loadMarkdownConfig.ts
 */
@Slf4j
@Service
public class MarkdownConfigLoaderService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarkdownConfigLoaderService.class);


    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * A parsed markdown file with extracted frontmatter and body content.
     *
     * @param filePath    absolute path to the file
     * @param frontmatter parsed YAML frontmatter key/value pairs
     * @param content     markdown body (after frontmatter block)
     * @param source      source scope: "projectSettings", "userSettings", "policySettings", "plugin"
     * @param baseDir     the .claude base directory this file was found in
     */
    public record MarkdownFileEntry(
            String filePath,
            Map<String, Object> frontmatter,
            String content,
            String source,
            String baseDir
    ) {}

    // -------------------------------------------------------------------------
    // Frontmatter parsing
    // -------------------------------------------------------------------------

    private static final Pattern FRONTMATTER_RE =
            Pattern.compile("^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?(.*)", Pattern.DOTALL);

    /**
     * Parse a markdown string into frontmatter map and body content.
     */
    public Map.Entry<Map<String, Object>, String> parseFrontmatter(String text) {
        Matcher m = FRONTMATTER_RE.matcher(text);
        if (!m.matches()) {
            return Map.entry(Map.of(), text);
        }
        String yamlBlock = m.group(1);
        String body = m.group(2) != null ? m.group(2) : "";
        return Map.entry(parseSimpleYaml(yamlBlock), body);
    }

    /**
     * Minimal YAML parser for simple key: value pairs.
     * Handles string, boolean, integer, and list values.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSimpleYaml(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) return result;

        String[] lines = yaml.split("\\r?\\n");
        String currentKey = null;
        List<String> currentList = null;

        for (String line : lines) {
            if (line.startsWith("  - ") || line.startsWith("- ")) {
                // List item
                String value = line.replaceFirst("^\\s*-\\s*", "").trim();
                if (currentKey != null) {
                    if (currentList == null) {
                        currentList = new ArrayList<>();
                        result.put(currentKey, currentList);
                    }
                    currentList.add(value);
                }
            } else if (line.contains(":")) {
                currentList = null;
                int idx = line.indexOf(':');
                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim();
                currentKey = key;

                if (val.isEmpty()) {
                    // Will be populated by subsequent list items
                } else if (val.startsWith("[") && val.endsWith("]")) {
                    // Inline list
                    String inner = val.substring(1, val.length() - 1);
                    List<String> list = new ArrayList<>();
                    for (String item : inner.split(",")) {
                        String trimmed = item.trim().replaceAll("^['\"]|['\"]$", "");
                        if (!trimmed.isEmpty()) list.add(trimmed);
                    }
                    result.put(key, list);
                } else if ("true".equalsIgnoreCase(val)) {
                    result.put(key, Boolean.TRUE);
                } else if ("false".equalsIgnoreCase(val)) {
                    result.put(key, Boolean.FALSE);
                } else {
                    // Strip surrounding quotes if present
                    val = val.replaceAll("^['\"]|['\"]$", "");
                    result.put(key, val);
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Directory loading
    // -------------------------------------------------------------------------

    /**
     * Load all markdown files from {@code .claude/<subdir>/} directories
     * across all config scopes for the given working directory.
     *
     * Translated from loadMarkdownFilesForSubdir() in loadMarkdownConfig.ts
     *
     * @param subdir e.g. "agents", "skills", "commands"
     * @param cwd    current working directory
     * @return list of parsed markdown file entries
     */
    public List<MarkdownFileEntry> loadMarkdownFilesForSubdir(String subdir, String cwd) {
        List<MarkdownFileEntry> results = new ArrayList<>();

        // Project settings: <cwd>/.claude/<subdir>/
        addFromDirectory(results, cwd + "/.claude/" + subdir, "projectSettings", cwd + "/.claude");

        // User settings: ~/.claude/<subdir>/
        String home = System.getProperty("user.home");
        if (home != null) {
            addFromDirectory(results, home + "/.claude/" + subdir, "userSettings", home + "/.claude");
        }

        return results;
    }

    private void addFromDirectory(List<MarkdownFileEntry> results,
                                   String dirPath,
                                   String source,
                                   String baseDir) {
        Path dir = Path.of(dirPath);
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".md"))
                .sorted()
                .forEach(p -> {
                    try {
                        String text = Files.readString(p);
                        Map.Entry<Map<String, Object>, String> parsed = parseFrontmatter(text);
                        results.add(new MarkdownFileEntry(
                                p.toAbsolutePath().toString(),
                                parsed.getKey(),
                                parsed.getValue(),
                                source,
                                baseDir
                        ));
                    } catch (IOException e) {
                        log.debug("Failed to read {}: {}", p, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.debug("Failed to walk {}: {}", dirPath, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Tool list helpers
    // -------------------------------------------------------------------------

    /**
     * Parse a tools list from frontmatter.
     * Accepts a List, comma-separated String, or null.
     * Translated from parseAgentToolsFromFrontmatter() in loadMarkdownConfig.ts
     */
    @SuppressWarnings("unchecked")
    public List<String> parseAgentToolsFromFrontmatter(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) result.add(item.toString().trim());
            }
            return result;
        }
        if (raw instanceof String s) {
            if (s.isBlank()) return List.of();
            List<String> result = new ArrayList<>();
            for (String item : s.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result;
        }
        return List.of();
    }

    /**
     * Parse a slash-command skills list from frontmatter.
     * Alias of {@link #parseAgentToolsFromFrontmatter} — same format.
     * Translated from parseSlashCommandToolsFromFrontmatter() in loadMarkdownConfig.ts
     */
    public List<String> parseSlashCommandToolsFromFrontmatter(Object raw) {
        return parseAgentToolsFromFrontmatter(raw);
    }
}
