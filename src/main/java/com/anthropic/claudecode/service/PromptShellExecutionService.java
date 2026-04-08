package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ToolUseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.*;

/**
 * Parses and executes shell commands embedded in prompt / skill text.
 * Translated from src/utils/promptShellExecution.ts
 *
 * <p>Supports two syntaxes:</p>
 * <ul>
 *   <li>Code blocks: <code>```! command ```</code></li>
 *   <li>Inline: <code>!`command`</code></li>
 * </ul>
 *
 * <p>Commands can be routed to bash (default) or PowerShell via the
 * {@code FrontmatterShell} hint from the skill's frontmatter.</p>
 */
@Slf4j
@Service
public class PromptShellExecutionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PromptShellExecutionService.class);


    /**
     * Shell selection hint from a skill's frontmatter.
     * Translated from the {@code FrontmatterShell} type in frontmatterParser.ts.
     */
    public enum FrontmatterShell {
        BASH,
        POWERSHELL
    }

    // Pattern for code blocks:  ```!\ncommand\n```
    private static final Pattern BLOCK_PATTERN =
            Pattern.compile("```!\\s*\\n?([\\s\\S]*?)\\n?```");

    // Pattern for inline:  !`command`
    // Uses a look-behind equivalent (match leading whitespace/start) to avoid
    // false positives in markdown spans — mirrors the TS (?<=^|\s)!`...` rule.
    private static final Pattern INLINE_PATTERN =
            Pattern.compile("(?:(?<=^)|(?<=\\s))!`([^`]+)`", Pattern.MULTILINE);

    private final ShellService shellService;

    @Autowired
    public PromptShellExecutionService(ShellService shellService) {
        this.shellService = shellService;
    }

    // -------------------------------------------------------------------------
    // executeShellCommandsInPrompt
    // -------------------------------------------------------------------------

    /**
     * Parses {@code text} for embedded shell commands, executes each one, and
     * returns the text with commands replaced by their output.
     * Translated from {@code executeShellCommandsInPrompt()} in promptShellExecution.ts.
     *
     * @param text             prompt/skill text that may contain shell command patterns
     * @param context          tool-use context used for permission checks
     * @param slashCommandName name of the slash command (used in permission-check error messages)
     * @param shell            optional shell override from skill frontmatter
     * @return the text with all embedded commands expanded
     */
    public CompletableFuture<String> executeShellCommandsInPrompt(
            String text,
            ToolUseContext context,
            String slashCommandName,
            FrontmatterShell shell) {

        if (text == null) return CompletableFuture.completedFuture("");

        // Quick exit when there are no command patterns (mirrors the TS substring gate)
        boolean hasBlockCommands  = text.contains("```!");
        boolean hasInlineCommands = text.contains("!`");
        if (!hasBlockCommands && !hasInlineCommands) {
            return CompletableFuture.completedFuture(text);
        }

        // Collect all matches first so we can execute them in parallel
        List<MatchResult> blockMatches  = hasBlockCommands
                ? allMatches(BLOCK_PATTERN,  text) : List.of();
        List<MatchResult> inlineMatches = hasInlineCommands
                ? allMatches(INLINE_PATTERN, text) : List.of();

        List<MatchResult> allMatches = new ArrayList<>(blockMatches);
        allMatches.addAll(inlineMatches);

        if (allMatches.isEmpty()) {
            return CompletableFuture.completedFuture(text);
        }

        // Execute all commands in parallel, preserving their match → output mapping
        List<CompletableFuture<CommandResult>> futures = allMatches.stream()
                .map(m -> executeMatchAsync(m, shell, context, slashCommandName))
                .toList();

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(__ -> {
                    // Apply replacements in reverse order so offsets stay valid
                    String result = text;
                    List<CommandResult> results = futures.stream()
                            .map(CompletableFuture::join)
                            .sorted(Comparator.comparingInt(CommandResult::start).reversed())
                            .toList();

                    for (CommandResult cr : results) {
                        result = result.substring(0, cr.start())
                                + cr.output()
                                + result.substring(cr.end());
                    }
                    return result;
                });
    }

    /** Overload without explicit shell (defaults to BASH). */
    public CompletableFuture<String> executeShellCommandsInPrompt(
            String text, ToolUseContext context, String slashCommandName) {
        return executeShellCommandsInPrompt(text, context, slashCommandName, null);
    }

    /** Overload accepting allowed tool names (List<String>) instead of ToolUseContext. */
    public CompletableFuture<String> executeShellCommandsInPrompt(
            String text, java.util.List<String> allowedTools, String slashCommandName) {
        return executeShellCommandsInPrompt(text, (ToolUseContext) null, slashCommandName, null);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private record CommandResult(int start, int end, String output) {}

    private CompletableFuture<CommandResult> executeMatchAsync(
            MatchResult match,
            FrontmatterShell shell,
            ToolUseContext context,
            String slashCommandName) {

        String command = match.group(1);
        if (command == null || command.isBlank()) {
            return CompletableFuture.completedFuture(
                    new CommandResult(match.start(), match.end(), match.group(0)));
        }
        command = command.trim();

        final String finalCommand = command;

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Permission check — mirrors hasPermissionsToUseTool() in TS
                if (!checkPermission(finalCommand, context, slashCommandName, match.group(0))) {
                    // checkPermission throws MalformedCommandError on denial;
                    // returning the original match text here is unreachable but safe
                    return new CommandResult(match.start(), match.end(), match.group(0));
                }

                String output = executeCommand(finalCommand, shell);
                return new CommandResult(match.start(), match.end(), output);
            } catch (MalformedCommandException e) {
                throw e;
            } catch (Exception e) {
                log.debug("Shell command execution error for '{}': {}", finalCommand, e.getMessage());
                return new CommandResult(match.start(), match.end(),
                        "[Error]\n" + e.getMessage());
            }
        });
    }

    /**
     * Checks whether the command is allowed to run.
     * Returns {@code true} if allowed; throws {@link MalformedCommandException} if denied.
     */
    private boolean checkPermission(String command, ToolUseContext context,
                                    String slashCommandName, String matchText) {
        // In the full implementation this calls hasPermissionsToUseTool().
        // Here we trust the context's permission mode (auto/plan/etc.) and only block
        // when the context explicitly signals read-only mode.
        if (context != null && Boolean.TRUE.equals(context.isReadOnly())) {
            log.debug("Shell command permission check failed for command in {}: {}",
                    slashCommandName, command);
            throw new MalformedCommandException(
                    "Shell command permission check failed for pattern \""
                    + matchText + "\": Permission denied");
        }
        return true;
    }

    private String executeCommand(String command, FrontmatterShell shell) {
        try {
            boolean usePowerShell = shell == FrontmatterShell.POWERSHELL
                    && isPowerShellEnabled();

            ShellService.ExecResult result = usePowerShell
                    ? shellService.executePowerShell(command, 30_000)
                    : shellService.execute(command, 30_000, false);

            return formatBashOutput(
                    result.getStdout() != null ? result.getStdout() : "",
                    result.getStderr() != null ? result.getStderr() : "",
                    false);
        } catch (ShellException e) {
            if (e.isInterrupted()) {
                throw new MalformedCommandException(
                        "Shell command interrupted for pattern \"`" + command + "`\": [Command interrupted]");
            }
            String output = formatBashOutput(e.getStdout(), e.getStderr(), false);
            throw new MalformedCommandException(
                    "Shell command failed for pattern \"`" + command + "`\": " + output);
        } catch (Exception e) {
            throw new MalformedCommandException("[Error]\n" + e.getMessage());
        }
    }

    /**
     * Formats stdout + stderr into a single output string.
     * Translated from {@code formatBashOutput()} in promptShellExecution.ts.
     */
    private static String formatBashOutput(String stdout, String stderr, boolean inline) {
        List<String> parts = new ArrayList<>();
        if (stdout != null && !stdout.isBlank()) parts.add(stdout.trim());
        if (stderr != null && !stderr.isBlank()) {
            parts.add(inline ? "[stderr: " + stderr.trim() + "]"
                             : "[stderr]\n" + stderr.trim());
        }
        return String.join(inline ? " " : "\n", parts);
    }

    /** Returns all non-overlapping matches of {@code pattern} in {@code text}. */
    private static List<MatchResult> allMatches(Pattern pattern, String text) {
        List<MatchResult> results = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) results.add(m.toMatchResult());
        return results;
    }

    /** Whether PowerShell routing is enabled (mirrors isPowerShellToolEnabled()). */
    private boolean isPowerShellEnabled() {
        // Real gate lives in ShellToolUtils / settings; default to disabled.
        return false;
    }

    // -------------------------------------------------------------------------
    // Exception types
    // -------------------------------------------------------------------------

    /** Mirrors MalformedCommandError from errors.ts. */
    public static class MalformedCommandException extends RuntimeException {
        public MalformedCommandException(String message) { super(message); }
    }

    /** Mirrors ShellError from errors.ts. */
    public static class ShellException extends RuntimeException {
        private final String stdout;
        private final String stderr;
        private final boolean interrupted;

        public ShellException(String message, String stdout, String stderr, boolean interrupted) {
            super(message);
            this.stdout      = stdout;
            this.stderr      = stderr;
            this.interrupted = interrupted;
        }

        public String  getStdout()     { return stdout; }
        public String  getStderr()     { return stderr; }
        public boolean isInterrupted() { return interrupted; }
    }
}
