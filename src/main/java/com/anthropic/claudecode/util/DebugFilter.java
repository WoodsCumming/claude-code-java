package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Debug filter utilities.
 * Translated from src/utils/debugFilter.ts
 *
 * Provides parsing and matching for debug message category filters.
 * Supports inclusive ("api,hooks") and exclusive ("!1p,!file") filter patterns.
 */
@Slf4j
public class DebugFilter {



    /**
     * Represents a parsed debug filter configuration.
     * Translated from the DebugFilter type in debugFilter.ts
     */
    @Data
    public static class DebugFilterConfig {
        private List<String> include;
        private List<String> exclude;
        private boolean exclusive;

        public DebugFilterConfig(List<String> include, List<String> exclude, boolean exclusive) {
            this.include = include;
            this.exclude = exclude;
            this.exclusive = exclusive;
        }

        public List<String> getInclude() { return include; }
        public void setInclude(List<String> v) { include = v; }
        public List<String> getExclude() { return exclude; }
        public void setExclude(List<String> v) { exclude = v; }
        public boolean isExclusive() { return exclusive; }
        public void setExclusive(boolean v) { exclusive = v; }
    }

    // Memoization cache — keyed by filter string
    private static final Map<String, DebugFilterConfig> parseCache = new ConcurrentHashMap<>();

    private static final Pattern MCP_PATTERN =
            Pattern.compile("^MCP server [\"']([^\"']+)[\"']");
    private static final Pattern PREFIX_PATTERN =
            Pattern.compile("^([^:\\[]+):");
    private static final Pattern BRACKET_PATTERN =
            Pattern.compile("^\\[([^\\]]+)]");
    private static final Pattern SECONDARY_PATTERN =
            Pattern.compile(":\\s*([^:]+?)(?:\\s+(?:type|mode|status|event))?:");

    /**
     * Parse a debug filter string into a filter configuration.
     * Results are memoized.
     * Translated from parseDebugFilter() in debugFilter.ts
     *
     * Examples:
     *   "api,hooks"  -> include only api and hooks categories
     *   "!1p,!file"  -> exclude logging and file categories
     *   null / ""    -> no filtering (returns null)
     */
    public static DebugFilterConfig parseDebugFilter(String filterString) {
        if (filterString == null || filterString.trim().isEmpty()) {
            return null;
        }

        return parseCache.computeIfAbsent(filterString, DebugFilter::doParse);
    }

    private static DebugFilterConfig doParse(String filterString) {
        List<String> filters = Arrays.stream(filterString.split(","))
                .map(String::trim)
                .filter(f -> !f.isEmpty())
                .collect(Collectors.toList());

        if (filters.isEmpty()) {
            return null;
        }

        boolean hasExclusive = filters.stream().anyMatch(f -> f.startsWith("!"));
        boolean hasInclusive = filters.stream().anyMatch(f -> !f.startsWith("!"));

        // Mixed inclusive/exclusive — treat as error, show all messages
        if (hasExclusive && hasInclusive) {
            return null;
        }

        List<String> cleanFilters = filters.stream()
                .map(f -> f.replaceFirst("^!", "").toLowerCase())
                .collect(Collectors.toList());

        return new DebugFilterConfig(
                hasExclusive ? List.of() : cleanFilters,
                hasExclusive ? cleanFilters : List.of(),
                hasExclusive
        );
    }

    /**
     * Extract debug categories from a message.
     * Translated from extractDebugCategories() in debugFilter.ts
     *
     * Supports multiple patterns:
     *   "category: message"             -> ["category"]
     *   "[CATEGORY] message"            -> ["category"]
     *   "MCP server \"name\": message"  -> ["mcp", "name"]
     *   "[ANT-ONLY] 1P event: timer"    -> ["ant-only", "1p"]
     */
    public static List<String> extractDebugCategories(String message) {
        Set<String> categories = new LinkedHashSet<>();

        // Pattern 3: MCP server "servername" — check first to avoid false positives
        Matcher mcpMatcher = MCP_PATTERN.matcher(message);
        if (mcpMatcher.find()) {
            categories.add("mcp");
            categories.add(mcpMatcher.group(1).toLowerCase());
        } else {
            // Pattern 1: "category: message" (simple prefix)
            Matcher prefixMatcher = PREFIX_PATTERN.matcher(message);
            if (prefixMatcher.find()) {
                String cat = prefixMatcher.group(1).trim();
                if (!cat.isEmpty()) {
                    categories.add(cat.toLowerCase());
                }
            }
        }

        // Pattern 2: [CATEGORY] at the start
        Matcher bracketMatcher = BRACKET_PATTERN.matcher(message);
        if (bracketMatcher.find()) {
            categories.add(bracketMatcher.group(1).trim().toLowerCase());
        }

        // Pattern 4: "1p event:" detection
        if (message.toLowerCase().contains("1p event:")) {
            categories.add("1p");
        }

        // Pattern 5: secondary category after the first colon
        Matcher secondaryMatcher = SECONDARY_PATTERN.matcher(message);
        if (secondaryMatcher.find()) {
            String secondary = secondaryMatcher.group(1).trim().toLowerCase();
            if (secondary.length() < 30 && !secondary.contains(" ")) {
                categories.add(secondary);
            }
        }

        return new ArrayList<>(categories);
    }

    /**
     * Check if a debug message should be shown based on its categories and the filter.
     * Translated from shouldShowDebugCategories() in debugFilter.ts
     */
    public static boolean shouldShowDebugCategories(List<String> categories, DebugFilterConfig filter) {
        if (filter == null) {
            return true;
        }
        if (categories.isEmpty()) {
            return false;
        }
        if (filter.isExclusive()) {
            return categories.stream().noneMatch(cat -> filter.getExclude().contains(cat));
        } else {
            return categories.stream().anyMatch(cat -> filter.getInclude().contains(cat));
        }
    }

    /**
     * Main function to check if a debug message should be shown.
     * Combines extraction and filtering.
     * Translated from shouldShowDebugMessage() in debugFilter.ts
     */
    public static boolean shouldShowDebugMessage(String message, DebugFilterConfig filter) {
        if (filter == null) {
            return true;
        }
        List<String> categories = extractDebugCategories(message);
        return shouldShowDebugCategories(categories, filter);
    }

    private DebugFilter() {}
}
