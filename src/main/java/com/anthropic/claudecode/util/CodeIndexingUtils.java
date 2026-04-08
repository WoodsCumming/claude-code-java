package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Code indexing tool detection utilities.
 * Translated from src/utils/codeIndexing.ts
 */
public class CodeIndexingUtils {

    // Known code indexing tool identifiers
    private static final Set<String> CODE_SEARCH_COMMANDS = Set.of(
        "sg", "sourcegraph", "hound", "seagoat", "bloop"
    );

    private static final Set<String> CODE_INDEXING_MCP_SERVERS = Set.of(
        "claude-context", "code-index-mcp", "local-code-search",
        "autodev-codebase", "codebase-search"
    );

    /**
     * Detect code indexing tools from a bash command.
     * Translated from detectCodeIndexingFromCommand() in codeIndexing.ts
     */
    public static Optional<String> detectCodeIndexingFromCommand(String command) {
        if (command == null) return Optional.empty();

        String baseCmd = BashUtils.getBaseCommand(command).toLowerCase();

        for (String tool : CODE_SEARCH_COMMANDS) {
            if (baseCmd.equals(tool) || baseCmd.startsWith(tool + " ")) {
                return Optional.of(tool);
            }
        }

        return Optional.empty();
    }

    /**
     * Detect code indexing from an MCP server name.
     * Translated from detectCodeIndexingFromMcpServerName() in codeIndexing.ts
     */
    public static Optional<String> detectCodeIndexingFromMcpServerName(String serverName) {
        if (serverName == null) return Optional.empty();

        String lower = serverName.toLowerCase();
        for (String tool : CODE_INDEXING_MCP_SERVERS) {
            if (lower.contains(tool)) {
                return Optional.of(tool);
            }
        }

        return Optional.empty();
    }

    private CodeIndexingUtils() {}
}
