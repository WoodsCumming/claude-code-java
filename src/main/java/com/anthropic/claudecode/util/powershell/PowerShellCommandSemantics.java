package com.anthropic.claudecode.util.powershell;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Command semantics configuration for interpreting exit codes in PowerShell.
 *
 * PowerShell-native cmdlets do NOT need exit-code semantics:
 *   - Select-String (grep equivalent) exits 0 on no-match (returns $null)
 *   - Compare-Object (diff equivalent) exits 0 regardless
 *   - Test-Path exits 0 regardless (returns bool via pipeline)
 * Native cmdlets signal failure via terminating errors ($?), not exit codes.
 *
 * However, EXTERNAL executables invoked from PowerShell DO set $LASTEXITCODE,
 * and many use non-zero codes to convey information rather than failure:
 *   - grep.exe / rg.exe (Git for Windows, scoop, etc.): 1 = no match
 *   - findstr.exe (Windows native): 1 = no match
 *   - robocopy.exe (Windows native): 0-7 = success, 8+ = error (notorious!)
 *
 * Without this module, PowerShellTool throws ShellError on any non-zero exit,
 * so {@code robocopy} reporting "files copied successfully" (exit 1) shows as an error.
 *
 * Translated from src/tools/PowerShellTool/commandSemantics.ts
 */
@Slf4j
public final class PowerShellCommandSemantics {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PowerShellCommandSemantics.class);


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
     * Takes (exitCode, stdout, stderr) and returns a {@link CommandResult}.
     */
    @FunctionalInterface
    public interface CommandSemantic
            extends java.util.function.Function<int[], CommandResult> {

        default CommandResult apply(int exitCode, String stdout, String stderr) {
            return apply(new int[]{exitCode});
        }
    }

    // -------------------------------------------------------------------------
    // Default semantic: treat only 0 as success, everything else as error
    // -------------------------------------------------------------------------

    private static final CommandSemantic DEFAULT_SEMANTIC = args -> {
        int exitCode = args[0];
        if (exitCode != 0) {
            return new CommandResult(true, "Command failed with exit code " + exitCode);
        }
        return new CommandResult(false);
    };

    // -------------------------------------------------------------------------
    // grep / ripgrep: 0 = matches found, 1 = no matches, 2+ = error
    // -------------------------------------------------------------------------

    private static final CommandSemantic GREP_SEMANTIC = args -> {
        int exitCode = args[0];
        return new CommandResult(exitCode >= 2, exitCode == 1 ? "No matches found" : null);
    };

    // -------------------------------------------------------------------------
    // robocopy.exe: exit codes are a BITFIELD — 0-7 are success, 8+ is failure
    //   0 = no files copied (already in sync)
    //   1 = files copied successfully
    //   2 = extra files/dirs detected (no copy)
    //   4 = mismatched files/dirs detected
    //   8 = some files/dirs could not be copied (copy errors)
    //  16 = serious error (robocopy did not copy any files)
    // -------------------------------------------------------------------------

    private static final CommandSemantic ROBOCOPY_SEMANTIC = args -> {
        int exitCode = args[0];
        if (exitCode >= 8) {
            return new CommandResult(true, null);
        }
        if (exitCode == 0) {
            return new CommandResult(false, "No files copied (already in sync)");
        }
        // 1..7 are success
        String msg = (exitCode & 1) != 0 ? "Files copied successfully" : "Robocopy completed (no errors)";
        return new CommandResult(false, msg);
    };

    /**
     * Command-specific semantics for external executables.
     * Keys are lowercase command names WITHOUT .exe suffix.
     *
     * Deliberately omitted:
     *   - 'diff': Ambiguous — PS aliases diff → Compare-Object vs diff.exe.
     *   - 'fc':   Ambiguous — PS aliases fc → Format-Custom vs fc.exe.
     *   - 'find': Ambiguous — Windows find.exe vs Unix find.exe semantics.
     *   - 'test', '[': Not PowerShell constructs.
     *   - 'select-string', 'compare-object', 'test-path': Native cmdlets exit 0.
     */
    private static final Map<String, CommandSemantic> COMMAND_SEMANTICS = Map.of(
            // External grep/ripgrep (Git for Windows, scoop, choco)
            "grep",     GREP_SEMANTIC,
            "rg",       GREP_SEMANTIC,
            // findstr.exe: Windows native text search (0=match, 1=no match, 2=error)
            "findstr",  GREP_SEMANTIC,
            // robocopy.exe: Windows native robust file copy (bitfield exit codes)
            "robocopy", ROBOCOPY_SEMANTIC
    );

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Interpret command result based on semantic rules.
     *
     * @param command  the full PowerShell command string (may contain pipes / operators)
     * @param exitCode the exit code returned by the command
     * @param stdout   standard output
     * @param stderr   standard error
     * @return a {@link CommandResult} indicating whether the exit constitutes an error
     */
    public static CommandResult interpretCommandResult(
            String command, int exitCode, String stdout, String stderr) {
        String baseCommand = heuristicallyExtractBaseCommand(command);
        CommandSemantic semantic = Optional.ofNullable(COMMAND_SEMANTICS.get(baseCommand))
                .orElse(DEFAULT_SEMANTIC);
        return semantic.apply(exitCode, stdout, stderr);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Extract the command name from a single pipeline segment.
     * Strips leading {@code &} / {@code .} call operators and {@code .exe} suffix, lowercases.
     */
    static String extractBaseCommand(String segment) {
        if (segment == null || segment.isBlank()) return "";
        // Strip PowerShell call operators: & "cmd", . "cmd"
        String stripped = segment.trim().replaceAll("^[&.]\\s+", "");
        String[] tokens = stripped.split("\\s+", 2);
        String firstToken = tokens.length > 0 ? tokens[0] : "";
        // Strip surrounding quotes if command was invoked as & "grep.exe"
        String unquoted = firstToken.replaceAll("^[\"']|[\"']$", "");
        // Strip path: C:\bin\grep.exe → grep.exe, .\rg.exe → rg.exe
        String[] parts = unquoted.split("[/\\\\]");
        String basename = parts.length > 0 ? parts[parts.length - 1] : unquoted;
        // Strip .exe suffix (Windows is case-insensitive)
        return basename.toLowerCase().replaceAll("\\.exe$", "");
    }

    /**
     * Extract the primary command from a PowerShell command line.
     * Takes the LAST pipeline segment since that determines the exit code.
     *
     * Heuristic split on {@code ;} and {@code |} — may get it wrong for quoted strings or
     * complex constructs. Do NOT depend on this for security; it's only used
     * for exit-code interpretation (false negatives just fall back to default).
     */
    static String heuristicallyExtractBaseCommand(String command) {
        if (command == null || command.isBlank()) return "";
        String[] segments = command.split("[;|]");
        List<String> nonEmpty = Arrays.stream(segments)
                .filter(s -> !s.isBlank())
                .toList();
        String last = nonEmpty.isEmpty() ? command : nonEmpty.get(nonEmpty.size() - 1);
        return extractBaseCommand(last);
    }

    private PowerShellCommandSemantics() {}
}
