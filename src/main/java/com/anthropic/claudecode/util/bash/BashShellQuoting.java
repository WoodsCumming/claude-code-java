package com.anthropic.claudecode.util.bash;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Shell command quoting utilities — preserves heredocs and multiline strings.
 *
 * Translated from src/utils/bash/shellQuoting.ts
 *
 * The shell-quote library incorrectly escapes ! to \! for heredocs and
 * multiline strings; this class works around that by using single-quote
 * wrapping for those cases.
 */
public final class BashShellQuoting {

    // -----------------------------------------------------------------------
    // Patterns
    // -----------------------------------------------------------------------

    // Bit-shift / arithmetic contexts that must NOT be treated as heredoc
    private static final Pattern NUMERIC_SHIFT_PATTERN =
            Pattern.compile("\\d\\s*<<\\s*\\d");
    private static final Pattern ARITH_BRACKET_SHIFT_PATTERN =
            Pattern.compile("\\[\\[\\s*\\d+\\s*<<\\s*\\d+\\s*\\]\\]");
    private static final Pattern ARITH_EXPR_SHIFT_PATTERN =
            Pattern.compile("\\$\\(\\(.*<<.*\\)\\)");

    /**
     * Matches heredoc start syntax:
     * <<EOF, <<'EOF', <<"EOF", <<-EOF, <<-'EOF', <<\EOF
     *
     * Equivalent to: /<<-?\s*(?:(['"]?)(\w+)\1|\\(\w+))/
     * (Note: Java regex does not support lookbehind with variable width for
     * the TS (?<!<) guard, so we handle false positives with the numeric
     * shift check above.)
     */
    private static final Pattern HEREDOC_PATTERN =
            Pattern.compile("<<-?\\s*(?:(['\"]?)(\\w+)\\1|\\\\(\\w+))");

    // Multiline single/double quoted strings containing a real newline
    private static final Pattern SINGLE_QUOTE_MULTILINE =
            Pattern.compile("'(?:[^'\\\\]|\\\\.)*\\n(?:[^'\\\\]|\\\\.)*'", Pattern.DOTALL);
    private static final Pattern DOUBLE_QUOTE_MULTILINE =
            Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\\n(?:[^\"\\\\]|\\\\.)*\"", Pattern.DOTALL);

    /**
     * Windows CMD-style >nul redirect pattern.
     *
     * Matches: >nul, > NUL, 2>nul, &>nul, >>nul (case-insensitive)
     * Does NOT match: >null, >nullable, >nul.txt
     */
    private static final Pattern NUL_REDIRECT_REGEX =
            Pattern.compile("(\\d?&?>+\\s*)[Nn][Uu][Ll](?=\\s|$|[|&;)\\n])",
                    Pattern.MULTILINE);

    // -----------------------------------------------------------------------
    // containsHeredoc (package-private, used by shouldAddStdinRedirect)
    // -----------------------------------------------------------------------

    /**
     * Detects if a command contains a heredoc pattern.
     * Matches patterns like: {@code <<EOF, <<'EOF', <<"EOF", <<-EOF, <<-'EOF', <<\EOF}.
     */
    static boolean containsHeredoc(String command) {
        // Exclude bit-shift operators first
        if (NUMERIC_SHIFT_PATTERN.matcher(command).find()) return false;
        if (ARITH_BRACKET_SHIFT_PATTERN.matcher(command).find()) return false;
        if (ARITH_EXPR_SHIFT_PATTERN.matcher(command).find()) return false;

        return HEREDOC_PATTERN.matcher(command).find();
    }

    // -----------------------------------------------------------------------
    // containsMultilineString
    // -----------------------------------------------------------------------

    /**
     * Detects if a command contains multiline strings in quotes.
     */
    static boolean containsMultilineString(String command) {
        return SINGLE_QUOTE_MULTILINE.matcher(command).find()
                || DOUBLE_QUOTE_MULTILINE.matcher(command).find();
    }

    // -----------------------------------------------------------------------
    // quoteShellCommand
    // -----------------------------------------------------------------------

    /**
     * Quotes a shell command appropriately, preserving heredocs and multiline
     * strings.
     *
     * @param command         the command to quote
     * @param addStdinRedirect whether to add {@code < /dev/null}
     * @return the properly quoted command
     */
    public static String quoteShellCommand(String command, boolean addStdinRedirect) {
        // If command contains heredoc or multiline strings, handle specially.
        // The shell-quote library incorrectly escapes ! to \! in these cases.
        if (containsHeredoc(command) || containsMultilineString(command)) {
            // Use single quotes and escape only single quotes in the command
            String escaped = command.replace("'", "'\"'\"'");
            String quoted = "'" + escaped + "'";

            // Don't add stdin redirect for heredocs as they provide their own input
            if (containsHeredoc(command)) {
                return quoted;
            }

            // For multiline strings without heredocs, add stdin redirect if needed
            return addStdinRedirect ? quoted + " < /dev/null" : quoted;
        }

        // For regular commands, use shell-quote equivalent
        if (addStdinRedirect) {
            return ShellQuoteUtils.quote(List.of(command, "<", "/dev/null"));
        }
        return ShellQuoteUtils.quote(List.of(command));
    }

    /**
     * Quotes a shell command with stdin redirect added by default.
     */
    public static String quoteShellCommand(String command) {
        return quoteShellCommand(command, true);
    }

    // -----------------------------------------------------------------------
    // hasStdinRedirect
    // -----------------------------------------------------------------------

    /**
     * Detects if a command already has a stdin redirect.
     *
     * Matches patterns like: {@code < file}, {@code </path/to/file}, {@code < /dev/null}.
     * Excludes: {@code <<EOF} (heredoc), {@code <<} (bit shift), {@code <(process substitution)}.
     */
    public static boolean hasStdinRedirect(String command) {
        // (?:^|[\s;&|])<(?![<(])\s*\S+
        return Pattern.compile("(?:^|[\\s;&|])<(?![<(])\\s*\\S+")
                .matcher(command).find();
    }

    // -----------------------------------------------------------------------
    // shouldAddStdinRedirect
    // -----------------------------------------------------------------------

    /**
     * Checks if stdin redirect should be added to a command.
     *
     * @param command the command to check
     * @return true if stdin redirect can be safely added
     */
    public static boolean shouldAddStdinRedirect(String command) {
        // Don't add stdin redirect for heredocs — interferes with heredoc terminator
        if (containsHeredoc(command)) return false;

        // Don't add if command already has one
        if (hasStdinRedirect(command)) return false;

        // For other commands, stdin redirect is generally safe
        return true;
    }

    // -----------------------------------------------------------------------
    // rewriteWindowsNullRedirect
    // -----------------------------------------------------------------------

    /**
     * Rewrites Windows CMD-style {@code >nul} redirects to POSIX {@code /dev/null}.
     *
     * The model occasionally hallucinates Windows CMD syntax (e.g. {@code ls 2>nul})
     * even though the bash shell is always POSIX. Git Bash creates a literal file
     * named {@code nul} — a Windows reserved device name that is extremely hard to
     * delete.
     *
     * @see <a href="https://github.com/anthropics/claude-code/issues/4928">Issue #4928</a>
     */
    public static String rewriteWindowsNullRedirect(String command) {
        return NUL_REDIRECT_REGEX.matcher(command).replaceAll("$1/dev/null");
    }

    private BashShellQuoting() {}
}
