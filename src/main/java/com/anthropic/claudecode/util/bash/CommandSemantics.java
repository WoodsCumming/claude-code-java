package com.anthropic.claudecode.util.bash;

import com.anthropic.claudecode.util.BashUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Command semantics configuration for interpreting exit codes in different contexts.
 *
 * Many commands use exit codes to convey information other than just success/failure.
 * For example, grep returns 1 when no matches are found, which is not an error condition.
 *
 * Translated from src/tools/BashTool/commandSemantics.ts
 */
@Slf4j
public final class CommandSemantics {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CommandSemantics.class);


    /**
     * Result of interpreting a command's exit code.
     */
    public record CommandResult(boolean isError, String message) {
        public CommandResult(boolean isError) {
            this(isError, null);
        }
    }

    /**
     * Functional interface representing command-specific exit-code semantics.
     */
    @FunctionalInterface
    public interface CommandSemantic extends Function<int[], CommandResult> {
        /**
         * Interpret exit code (args[0]), stdout, stderr using triple arguments.
         * In practice callers use the three-arg helper below.
         */
        @Override
        CommandResult apply(int[] exitCode);

        default CommandResult apply(int exitCode, String stdout, String stderr) {
            return apply(new int[]{exitCode});
        }
    }

    // -----------------------------------------------------------------------
    // Default semantic: treat only 0 as success
    // -----------------------------------------------------------------------

    private static final CommandSemantic DEFAULT_SEMANTIC = exitCodeArr -> {
        int exitCode = exitCodeArr[0];
        if (exitCode != 0) {
            return new CommandResult(true, "Command failed with exit code " + exitCode);
        }
        return new CommandResult(false);
    };

    // -----------------------------------------------------------------------
    // Command-specific semantics
    // -----------------------------------------------------------------------

    /**
     * grep / rg: 0=matches found, 1=no matches, 2+=error
     */
    private static final CommandSemantic GREP_SEMANTIC = exitCodeArr -> {
        int exitCode = exitCodeArr[0];
        return new CommandResult(exitCode >= 2, exitCode == 1 ? "No matches found" : null);
    };

    /**
     * find: 0=success, 1=partial success (some dirs inaccessible), 2+=error
     */
    private static final CommandSemantic FIND_SEMANTIC = exitCodeArr -> {
        int exitCode = exitCodeArr[0];
        return new CommandResult(exitCode >= 2,
                exitCode == 1 ? "Some directories were inaccessible" : null);
    };

    /**
     * diff: 0=no differences, 1=differences found, 2+=error
     */
    private static final CommandSemantic DIFF_SEMANTIC = exitCodeArr -> {
        int exitCode = exitCodeArr[0];
        return new CommandResult(exitCode >= 2, exitCode == 1 ? "Files differ" : null);
    };

    /**
     * test / [: 0=condition true, 1=condition false, 2+=error
     */
    private static final CommandSemantic TEST_SEMANTIC = exitCodeArr -> {
        int exitCode = exitCodeArr[0];
        return new CommandResult(exitCode >= 2, exitCode == 1 ? "Condition is false" : null);
    };

    private static final Map<String, CommandSemantic> COMMAND_SEMANTICS = Map.of(
            "grep",  GREP_SEMANTIC,
            "rg",    GREP_SEMANTIC,
            "find",  FIND_SEMANTIC,
            "diff",  DIFF_SEMANTIC,
            "test",  TEST_SEMANTIC,
            "[",     TEST_SEMANTIC
    );

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Interpret command result based on semantic rules.
     *
     * @param command  the full command string (may contain pipes / operators)
     * @param exitCode the exit code returned by the command
     * @param stdout   standard output
     * @param stderr   standard error
     * @return a {@link CommandResult} indicating whether the exit constitutes an error
     */
    public static CommandResult interpretCommandResult(
            String command, int exitCode, String stdout, String stderr) {
        CommandSemantic semantic = getCommandSemantic(command);
        return semantic.apply(exitCode, stdout, stderr);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Get the semantic interpretation for a command.
     */
    private static CommandSemantic getCommandSemantic(String command) {
        String baseCommand = heuristicallyExtractBaseCommand(command);
        return Optional.ofNullable(COMMAND_SEMANTICS.get(baseCommand))
                .orElse(DEFAULT_SEMANTIC);
    }

    /**
     * Extract just the command name (first word) from a single command string.
     */
    private static String extractBaseCommand(String command) {
        if (command == null || command.isBlank()) return "";
        String trimmed = command.trim();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) return trimmed;
        String[] parts = trimmed.split("\\s+", 2);
        return parts.length > 0 ? parts[0] : "";
    }

    /**
     * Extract the primary command from a complex command line heuristically.
     * May get it super wrong — do not depend on this for security.
     * Translated from heuristicallyExtractBaseCommand() in commandSemantics.ts
     */
    private static String heuristicallyExtractBaseCommand(String command) {
        List<String> segments = BashUtils.splitCommand(command);
        String lastCommand = segments.isEmpty() ? command : segments.get(segments.size() - 1);
        return extractBaseCommand(lastCommand);
    }

    private CommandSemantics() {}
}
