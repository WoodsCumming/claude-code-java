package com.anthropic.claudecode.util.bash;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Safe wrappers for shell-quote library functions that handle errors gracefully.
 *
 * Translated from src/utils/bash/shellQuote.ts
 *
 * These are drop-in replacements for the original functions.
 * The underlying "shell-quote" npm library does not exist in Java; this class
 * reimplements the required quoting and parsing behaviour directly.
 */
@Slf4j
public final class ShellQuoteUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShellQuoteUtils.class);


    // -----------------------------------------------------------------------
    // Result types  (sealed interfaces replace TypeScript union types)
    // -----------------------------------------------------------------------

    public sealed interface ShellParseResult
            permits ShellParseResult.Success, ShellParseResult.Failure {

        record Success(List<String> tokens) implements ShellParseResult {}
        record Failure(String error) implements ShellParseResult {}

        default boolean isSuccess() { return this instanceof Success; }
        default List<String> tokens() {
            if (this instanceof Success s) return s.tokens();
            throw new IllegalStateException("parse failed: " + ((Failure) this).error());
        }
    }

    public sealed interface ShellQuoteResult
            permits ShellQuoteResult.Success, ShellQuoteResult.Failure {

        record Success(String quoted) implements ShellQuoteResult {}
        record Failure(String error) implements ShellQuoteResult {}

        default boolean isSuccess() { return this instanceof Success; }
        default String quoted() {
            if (this instanceof Success s) return s.quoted();
            throw new IllegalStateException("quote failed: " + ((Failure) this).error());
        }
    }

    // -----------------------------------------------------------------------
    // tryParseShellCommand
    // -----------------------------------------------------------------------

    /**
     * Parses a shell command into tokens, optionally expanding environment variables.
     *
     * @param cmd the shell command string
     * @param env optional environment variable provider (unused in this implementation)
     */
    public static ShellParseResult tryParseShellCommand(String cmd,
                                                         Function<String, String> env) {
        try {
            List<String> tokens = parseShellTokens(cmd);
            return new ShellParseResult.Success(tokens);
        } catch (Exception e) {
            log.error("Shell command parse error", e);
            return new ShellParseResult.Failure(e.getMessage() != null
                    ? e.getMessage() : "Unknown parse error");
        }
    }

    public static ShellParseResult tryParseShellCommand(String cmd) {
        return tryParseShellCommand(cmd, (Function<String, String>) null);
    }

    public static ShellParseResult tryParseShellCommand(String cmd,
                                                         Map<String, String> env) {
        return tryParseShellCommand(cmd, env != null ? env::get : null);
    }

    // -----------------------------------------------------------------------
    // tryQuoteShellArgs
    // -----------------------------------------------------------------------

    /**
     * Safely quotes a list of arguments for use in a shell command.
     * Objects, symbols and functions are rejected; primitives are stringified.
     */
    public static ShellQuoteResult tryQuoteShellArgs(List<?> args) {
        try {
            List<String> validated = new ArrayList<>(args.size());
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.get(i);
                if (arg == null) {
                    validated.add("null");
                    continue;
                }
                if (arg instanceof String s) {
                    validated.add(s);
                } else if (arg instanceof Number || arg instanceof Boolean) {
                    validated.add(arg.toString());
                } else {
                    throw new IllegalArgumentException(
                            "Cannot quote argument at index " + i +
                            ": " + arg.getClass().getSimpleName() + " values are not supported");
                }
            }
            String quoted = quoteArgs(validated);
            return new ShellQuoteResult.Success(quoted);
        } catch (Exception e) {
            log.error("Shell quote error", e);
            return new ShellQuoteResult.Failure(e.getMessage() != null
                    ? e.getMessage() : "Unknown quote error");
        }
    }

    // -----------------------------------------------------------------------
    // hasMalformedTokens
    // -----------------------------------------------------------------------

    /**
     * Checks if parsed tokens contain malformed entries that suggest shell-quote
     * misinterpreted the command.
     *
     * Security: Prevents command injection via HackerOne #3482049.
     */
    public static boolean hasMalformedTokens(String command, List<String> parsed) {
        // Check for unterminated quotes in the original command.
        boolean inSingle = false;
        boolean inDouble = false;
        int doubleCount = 0;
        int singleCount = 0;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\\' && !inSingle) {
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                doubleCount++;
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                singleCount++;
                inSingle = !inSingle;
            }
        }
        if (doubleCount % 2 != 0 || singleCount % 2 != 0) return true;

        for (String entry : parsed) {
            // Unbalanced curly braces
            long openBraces  = entry.chars().filter(c -> c == '{').count();
            long closeBraces = entry.chars().filter(c -> c == '}').count();
            if (openBraces != closeBraces) return true;

            // Unbalanced parentheses
            long openParens  = entry.chars().filter(c -> c == '(').count();
            long closeParens = entry.chars().filter(c -> c == ')').count();
            if (openParens != closeParens) return true;

            // Unbalanced square brackets
            long openBrackets  = entry.chars().filter(c -> c == '[').count();
            long closeBrackets = entry.chars().filter(c -> c == ']').count();
            if (openBrackets != closeBrackets) return true;

            // Count unescaped double quotes (must be even)
            int unescapedDouble = countUnescapedQuotes(entry, '"');
            if (unescapedDouble % 2 != 0) return true;

            // Count unescaped single quotes (must be even)
            int unescapedSingle = countUnescapedQuotes(entry, '\'');
            if (unescapedSingle % 2 != 0) return true;
        }
        return false;
    }

    private static int countUnescapedQuotes(String s, char quoteChar) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == quoteChar) {
                // Count preceding backslashes
                int bs = 0;
                for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) bs++;
                if (bs % 2 == 0) count++; // not escaped
            }
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // hasShellQuoteSingleQuoteBug
    // -----------------------------------------------------------------------

    /**
     * Detects commands containing '\' patterns that exploit the shell-quote
     * library's incorrect handling of backslashes inside single quotes.
     *
     * In bash, single quotes preserve ALL characters literally — backslash has
     * no special meaning. So '\' is just the string \ (quote opens, contains \,
     * next ' closes it). shell-quote incorrectly treats \ as an escape inside
     * single quotes.
     */
    public static boolean hasShellQuoteSingleQuoteBug(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);

            // Handle backslash escaping outside of single quotes
            if (ch == '\\' && !inSingleQuote) {
                i++; // skip the next character (it's escaped)
                continue;
            }

            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;

                if (!inSingleQuote) {
                    // We just closed a single-quoted string.
                    int backslashCount = 0;
                    int j = i - 1;
                    while (j >= 0 && command.charAt(j) == '\\') {
                        backslashCount++;
                        j--;
                    }

                    // Odd trailing backslashes → always a bug
                    if (backslashCount > 0 && backslashCount % 2 == 1) {
                        return true;
                    }
                    // Even trailing backslashes → bug only when a later ' exists
                    if (backslashCount > 0 && backslashCount % 2 == 0
                            && command.indexOf('\'', i + 1) != -1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // quote  (public convenience method)
    // -----------------------------------------------------------------------

    /**
     * Quotes a list of arguments for safe use in a shell command.
     * Falls back to a lenient stringification for unsupported types.
     *
     * @throws IllegalStateException if quoting cannot be done safely
     */
    public static String quote(List<?> args) {
        ShellQuoteResult result = tryQuoteShellArgs(args);

        if (result.isSuccess()) {
            return result.quoted();
        }

        // Lenient fallback: convert all args to strings
        try {
            List<String> stringArgs = new ArrayList<>(args.size());
            for (Object arg : args) {
                if (arg == null) {
                    stringArgs.add("null");
                } else {
                    stringArgs.add(arg.toString());
                }
            }
            return quoteArgs(stringArgs);
        } catch (Exception e) {
            // SECURITY: Never fall through to an unquoted representation.
            log.error("Failed to quote shell arguments safely", e);
            throw new IllegalStateException("Failed to quote shell arguments safely", e);
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Quotes a list of pre-validated string arguments.
     * Uses single-quote wrapping, escaping embedded single quotes as '"'"'.
     */
    private static String quoteArgs(List<String> args) {
        if (args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(quoteSingleArg(args.get(i)));
        }
        return sb.toString();
    }

    /**
     * Quotes a single shell argument.
     * Safe characters are passed through unquoted; everything else is
     * single-quoted with embedded single-quotes escaped as '"'"'.
     */
    static String quoteSingleArg(String arg) {
        if (arg == null) return "''";
        if (arg.isEmpty()) return "''";
        // Characters safe without quoting
        if (arg.matches("[a-zA-Z0-9._/\\-]+")) return arg;
        // Redirect operators used by quoteShellCommand
        if ("<".equals(arg) || ">".equals(arg)) return arg;
        return "'" + arg.replace("'", "'\"'\"'") + "'";
    }

    /**
     * Minimal shell tokeniser used by tryParseShellCommand.
     * Handles single-quoted strings, double-quoted strings, and backslash escapes.
     * Shell operators (|, &, ;, <, >) are treated as token separators when unquoted.
     */
    private static List<String> parseShellTokens(String cmd) {
        if (cmd == null || cmd.isBlank()) return List.of();

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);

            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (inDouble) {
                if (c == '\\' && i + 1 < cmd.length()) {
                    char next = cmd.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') {
                        current.append(next);
                        i++;
                    } else {
                        current.append(c);
                    }
                    continue;
                }
                if (c == '"') {
                    inDouble = false;
                } else {
                    current.append(c);
                }
                continue;
            }

            // Unquoted context
            if (c == '\\') {
                if (i + 1 < cmd.length()) {
                    current.append(cmd.charAt(++i));
                }
                continue;
            }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"')  { inDouble = true; continue; }

            if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            // Shell operators become their own tokens
            if (c == '|' || c == '&' || c == ';' || c == '<' || c == '>') {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                // Peek for two-character operators
                if (i + 1 < cmd.length()) {
                    char next = cmd.charAt(i + 1);
                    if ((c == '|' && next == '|') ||
                        (c == '&' && next == '&') ||
                        (c == '>' && next == '>')) {
                        tokens.add(String.valueOf(c) + next);
                        i++;
                        continue;
                    }
                }
                tokens.add(String.valueOf(c));
                continue;
            }

            current.append(c);
        }

        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private ShellQuoteUtils() {}
}
