package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Command prefix extraction utilities.
 * Translated from src/utils/shell/specPrefix.ts
 *
 * Extracts meaningful command prefixes from shell commands.
 */
public class SpecPrefix {

    /**
     * Depth rules for specific commands.
     * Translated from DEPTH_RULES in specPrefix.ts
     */
    public static final Map<String, Integer> DEPTH_RULES = Map.ofEntries(
        Map.entry("rg", 2),
        Map.entry("pre-commit", 2),
        Map.entry("gcloud", 4),
        Map.entry("gcloud compute", 6),
        Map.entry("gcloud beta", 6),
        Map.entry("aws", 4),
        Map.entry("az", 4),
        Map.entry("kubectl", 3),
        Map.entry("docker", 3),
        Map.entry("dotnet", 3),
        Map.entry("git push", 2)
    );

    /**
     * Extract a command prefix from a command string.
     * Simplified version of the prefix extraction logic.
     */
    public static String extractCommandPrefix(String command) {
        if (command == null || command.isBlank()) return "";

        List<String> tokens = ShellQuoteUtils.parseShellCommand(command);
        if (tokens.isEmpty()) return "";

        String baseCommand = tokens.get(0);
        int depth = DEPTH_RULES.getOrDefault(baseCommand, 2);

        StringBuilder prefix = new StringBuilder(baseCommand);
        for (int i = 1; i < Math.min(depth, tokens.size()); i++) {
            String token = tokens.get(i);
            // Skip flags and their arguments
            if (token.startsWith("-")) continue;
            // Skip URL protocols
            if (token.startsWith("http://") || token.startsWith("https://") || token.startsWith("ftp://")) continue;
            prefix.append(" ").append(token);
        }

        return prefix.toString();
    }

    private SpecPrefix() {}
}
