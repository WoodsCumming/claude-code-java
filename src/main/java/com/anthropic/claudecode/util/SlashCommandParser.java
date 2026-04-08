package com.anthropic.claudecode.util;

import java.util.Optional;

/**
 * Slash command parsing utilities.
 * Translated from src/utils/slashCommandParsing.ts
 */
public class SlashCommandParser {

    public record ParsedSlashCommand(String commandName, String args, boolean isMcp) {}

    /**
     * Parse a slash command input string.
     * Translated from parseSlashCommand() in slashCommandParsing.ts
     *
     * @param input The raw input string (should start with '/')
     * @return Parsed command or empty if invalid
     */
    public static Optional<ParsedSlashCommand> parseSlashCommand(String input) {
        if (input == null) return Optional.empty();

        String trimmed = input.trim();
        if (!trimmed.startsWith("/")) return Optional.empty();

        String withoutSlash = trimmed.substring(1);
        String[] words = withoutSlash.split(" ");

        if (words.length == 0 || words[0].isEmpty()) return Optional.empty();

        String commandName = words[0];
        boolean isMcp = false;
        int argsStartIndex = 1;

        // Check for MCP commands (second word is '(MCP)')
        if (words.length > 1 && "(MCP)".equals(words[1])) {
            commandName = commandName + " (MCP)";
            isMcp = true;
            argsStartIndex = 2;
        }

        // Extract arguments
        StringBuilder args = new StringBuilder();
        for (int i = argsStartIndex; i < words.length; i++) {
            if (i > argsStartIndex) args.append(" ");
            args.append(words[i]);
        }

        return Optional.of(new ParsedSlashCommand(commandName, args.toString(), isMcp));
    }

    private SlashCommandParser() {}
}
