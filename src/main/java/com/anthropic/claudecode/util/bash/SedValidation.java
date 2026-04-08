package com.anthropic.claudecode.util.bash;

import com.anthropic.claudecode.model.PermissionMode;
import com.anthropic.claudecode.model.PermissionResult;
import com.anthropic.claudecode.model.ToolPermissionContext;
import com.anthropic.claudecode.util.BashUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation logic for sed commands.
 *
 * Contains an allowlist approach (only safe, well-understood sed operations are
 * permitted) plus a defence-in-depth denylist for dangerous operations such as
 * {@code w}/{@code W} (file-write) and {@code e}/{@code E} (execute).
 *
 * Translated from src/tools/BashTool/sedValidation.ts
 */
@Slf4j
public final class SedValidation {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SedValidation.class);


    private static final Pattern SED_START = Pattern.compile("^\\s*sed\\s+");

    // -----------------------------------------------------------------------
    // Flag allowlists
    // -----------------------------------------------------------------------

    private static final List<String> LINE_PRINT_ALLOWED_FLAGS = List.of(
            "-n", "--quiet", "--silent",
            "-E", "--regexp-extended",
            "-r", "-z", "--zero-terminated", "--posix"
    );

    private static final List<String> SUBST_BASE_ALLOWED_FLAGS = List.of(
            "-E", "--regexp-extended", "-r", "--posix"
    );

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Validate flags against an allowlist.
     * Handles both single flags and combined flags (e.g., {@code -nE}).
     */
    private static boolean validateFlagsAgainstAllowlist(List<String> flags,
                                                          List<String> allowedFlags) {
        for (String flag : flags) {
            if (flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                // Combined flags like -nE or -Er
                for (int i = 1; i < flag.length(); i++) {
                    String singleFlag = "-" + flag.charAt(i);
                    if (!allowedFlags.contains(singleFlag)) {
                        return false;
                    }
                }
            } else {
                if (!allowedFlags.contains(flag)) {
                    return false;
                }
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Pattern 1 — line printing command
    // -----------------------------------------------------------------------

    /**
     * Pattern 1: Check if this is a line printing command with -n flag.
     * Allows: {@code sed -n 'N'} | {@code sed -n 'N,M'} with optional -E, -r, -z flags.
     * Allows semicolon-separated print commands like: {@code sed -n '1p;2p;3p'}.
     * File arguments are ALLOWED for this pattern.
     */
    static boolean isLinePrintingCommand(String command, List<String> expressions) {
        Matcher sedMatch = SED_START.matcher(command);
        if (!sedMatch.find()) return false;

        String withoutSed = command.substring(sedMatch.end());
        ShellQuoteUtils.ShellParseResult parseResult = ShellQuoteUtils.tryParseShellCommand(withoutSed);
        if (!parseResult.isSuccess()) return false;
        List<String> parsed = parseResult.tokens();

        // Extract all flags
        List<String> flags = new ArrayList<>();
        for (String arg : parsed) {
            if (arg.startsWith("-") && !"--".equals(arg)) {
                flags.add(arg);
            }
        }

        if (!validateFlagsAgainstAllowlist(flags, LINE_PRINT_ALLOWED_FLAGS)) {
            return false;
        }

        // Check if -n flag is present (required for Pattern 1)
        boolean hasNFlag = false;
        for (String flag : flags) {
            if ("-n".equals(flag) || "--quiet".equals(flag) || "--silent".equals(flag)) {
                hasNFlag = true;
                break;
            }
            if (flag.startsWith("-") && !flag.startsWith("--") && flag.contains("n")) {
                hasNFlag = true;
                break;
            }
        }
        if (!hasNFlag) return false;

        if (expressions.isEmpty()) return false;

        // All expressions must be print commands (strict allowlist)
        for (String expr : expressions) {
            for (String cmd : expr.split(";")) {
                if (!isPrintCommand(cmd.trim())) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Check if a single command is a valid print command.
     * STRICT ALLOWLIST: only these exact forms are allowed:
     * <ul>
     *   <li>{@code p} — print all</li>
     *   <li>{@code Np} — print line N, where N is digits</li>
     *   <li>{@code N,Mp} — print lines N through M</li>
     * </ul>
     */
    static boolean isPrintCommand(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        return Pattern.matches("^(?:\\d+|\\d+,\\d+)?p$", cmd);
    }

    // -----------------------------------------------------------------------
    // Pattern 2 — substitution command
    // -----------------------------------------------------------------------

    /**
     * Pattern 2: Check if this is a substitution command.
     * Allows: {@code sed 's/pattern/replacement/flags'} where flags are only:
     * g, p, i, I, m, M, 1-9.
     * When {@code allowFileWrites} is {@code true}, allows -i flag and file arguments
     * for in-place editing.
     */
    private static boolean isSubstitutionCommand(String command, List<String> expressions,
                                                  boolean hasFileArguments, boolean allowFileWrites) {
        if (!allowFileWrites && hasFileArguments) {
            return false;
        }

        Matcher sedMatch = SED_START.matcher(command);
        if (!sedMatch.find()) return false;

        String withoutSed = command.substring(sedMatch.end());
        ShellQuoteUtils.ShellParseResult parseResult = ShellQuoteUtils.tryParseShellCommand(withoutSed);
        if (!parseResult.isSuccess()) return false;
        List<String> parsed = parseResult.tokens();

        // Extract all flags
        List<String> flags = new ArrayList<>();
        for (String arg : parsed) {
            if (arg.startsWith("-") && !"--".equals(arg)) {
                flags.add(arg);
            }
        }

        List<String> allowedFlags = new ArrayList<>(SUBST_BASE_ALLOWED_FLAGS);
        if (allowFileWrites) {
            allowedFlags.add("-i");
            allowedFlags.add("--in-place");
        }

        if (!validateFlagsAgainstAllowlist(flags, allowedFlags)) {
            return false;
        }

        // Must have exactly one expression
        if (expressions.size() != 1) return false;

        String expr = expressions.get(0).trim();

        // STRICT ALLOWLIST: Must be exactly a substitution command starting with 's'
        if (!expr.startsWith("s")) return false;

        // Parse substitution: s/pattern/replacement/flags — only / as delimiter
        Pattern substPattern = Pattern.compile("^s/(.*?)$", Pattern.DOTALL);
        Matcher substMatch = substPattern.matcher(expr);
        if (!substMatch.find()) return false;

        String rest = substMatch.group(1);

        int delimiterCount = 0;
        int lastDelimiterPos = -1;
        int idx = 0;
        while (idx < rest.length()) {
            if (rest.charAt(idx) == '\\') {
                idx += 2;
                continue;
            }
            if (rest.charAt(idx) == '/') {
                delimiterCount++;
                lastDelimiterPos = idx;
            }
            idx++;
        }

        // Must have found exactly 2 delimiters
        if (delimiterCount != 2) return false;

        String exprFlags = rest.substring(lastDelimiterPos + 1);

        // Validate flags: only allow g, p, i, I, m, M, and optionally ONE digit 1-9
        if (!exprFlags.matches("^[gpimIM]*[1-9]?[gpimIM]*$")) {
            return false;
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Checks if a sed command is allowed by the allowlist.
     *
     * @param command        the sed command to check
     * @param allowFileWrites when {@code true}, allows -i flag and file arguments
     *                        for substitution commands
     * @return {@code true} if the command is allowed, {@code false} otherwise
     */
    public static boolean sedCommandIsAllowedByAllowlist(String command, boolean allowFileWrites) {
        List<String> expressions;
        try {
            expressions = extractSedExpressions(command);
        } catch (Exception e) {
            return false;
        }

        boolean hasFileArguments = hasFileArgs(command);

        boolean isPattern1 = false;
        boolean isPattern2 = false;

        if (allowFileWrites) {
            isPattern2 = isSubstitutionCommand(command, expressions, hasFileArguments, true);
        } else {
            isPattern1 = isLinePrintingCommand(command, expressions);
            isPattern2 = isSubstitutionCommand(command, expressions, hasFileArguments, false);
        }

        if (!isPattern1 && !isPattern2) {
            return false;
        }

        // Pattern 2 does not allow semicolons (command separators)
        for (String expr : expressions) {
            if (isPattern2 && expr.contains(";")) {
                return false;
            }
        }

        // Defence-in-depth denylist
        for (String expr : expressions) {
            if (containsDangerousOperations(expr)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Convenience overload — no file writes allowed by default.
     */
    public static boolean sedCommandIsAllowedByAllowlist(String command) {
        return sedCommandIsAllowedByAllowlist(command, false);
    }

    /**
     * Check if a sed command has file arguments (not just stdin).
     */
    static boolean hasFileArgs(String command) {
        Matcher sedMatch = SED_START.matcher(command);
        if (!sedMatch.find()) return false;

        String withoutSed = command.substring(sedMatch.end());
        ShellQuoteUtils.ShellParseResult parseResult = ShellQuoteUtils.tryParseShellCommand(withoutSed);
        if (!parseResult.isSuccess()) return true; // Assume dangerous if parsing fails
        List<String> parsed = parseResult.tokens();

        try {
            int argCount = 0;
            boolean hasEFlag = false;

            for (int i = 0; i < parsed.size(); i++) {
                String arg = parsed.get(i);

                // Shell operators → treat as glob/complex — file arg present
                if (arg.length() == 1 && "|&;<>".indexOf(arg.charAt(0)) >= 0) {
                    return true;
                }

                // Handle -e flag followed by expression
                if (("-e".equals(arg) || "--expression".equals(arg)) && i + 1 < parsed.size()) {
                    hasEFlag = true;
                    i++; // Skip next arg (the expression)
                    continue;
                }

                // Handle --expression=value format
                if (arg.startsWith("--expression=")) {
                    hasEFlag = true;
                    continue;
                }

                // Handle -e=value format
                if (arg.startsWith("-e=")) {
                    hasEFlag = true;
                    continue;
                }

                // Skip other flags
                if (arg.startsWith("-")) continue;

                argCount++;

                if (hasEFlag) return true;

                // Without -e, the first non-flag arg is the sed expression;
                // a second means there are file arguments
                if (argCount > 1) return true;
            }

            return false;
        } catch (Exception e) {
            return true; // Assume dangerous if parsing fails
        }
    }

    /**
     * Extract sed expressions from command, ignoring flags and filenames.
     *
     * @param command full sed command
     * @return list of sed expressions
     * @throws IllegalArgumentException if parsing fails or dangerous flag combinations are detected
     */
    static List<String> extractSedExpressions(String command) {
        List<String> expressions = new ArrayList<>();

        Matcher sedMatch = SED_START.matcher(command);
        if (!sedMatch.find()) return expressions;

        String withoutSed = command.substring(sedMatch.end());

        // Reject dangerous flag combinations like -ew, -eW, -ee, -we
        if (withoutSed.contains("-e") || withoutSed.contains("-w")) {
            if (Pattern.compile("-e[wWe]").matcher(withoutSed).find()
                    || Pattern.compile("-w[eE]").matcher(withoutSed).find()) {
                throw new IllegalArgumentException("Dangerous flag combination detected");
            }
        }

        ShellQuoteUtils.ShellParseResult parseResult = ShellQuoteUtils.tryParseShellCommand(withoutSed);
        if (!parseResult.isSuccess()) {
            throw new IllegalArgumentException("Malformed shell syntax: "
                    + ((ShellQuoteUtils.ShellParseResult.Failure) parseResult).error());
        }
        List<String> parsed = parseResult.tokens();

        try {
            boolean foundEFlag = false;
            boolean foundExpression = false;

            for (int i = 0; i < parsed.size(); i++) {
                String arg = parsed.get(i);

                // Shell operators — stop processing
                if (arg.length() == 1 && "|&;<>".indexOf(arg.charAt(0)) >= 0) {
                    break;
                }

                // Handle -e flag followed by expression
                if (("-e".equals(arg) || "--expression".equals(arg)) && i + 1 < parsed.size()) {
                    foundEFlag = true;
                    String nextArg = parsed.get(i + 1);
                    expressions.add(nextArg);
                    i++;
                    continue;
                }

                // Handle --expression=value format
                if (arg.startsWith("--expression=")) {
                    foundEFlag = true;
                    expressions.add(arg.substring("--expression=".length()));
                    continue;
                }

                // Handle -e=value format
                if (arg.startsWith("-e=")) {
                    foundEFlag = true;
                    expressions.add(arg.substring("-e=".length()));
                    continue;
                }

                // Skip other flags
                if (arg.startsWith("-")) continue;

                // If no -e flags yet, the first non-flag arg is the sed expression
                if (!foundEFlag && !foundExpression) {
                    expressions.add(arg);
                    foundExpression = true;
                    continue;
                }

                // Remaining non-flag arguments are filenames — stop
                break;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse sed command: " + e.getMessage(), e);
        }

        return expressions;
    }

    // -----------------------------------------------------------------------
    // Denylist
    // -----------------------------------------------------------------------

    /**
     * Check if a sed expression contains dangerous operations.
     *
     * @param expression single sed expression (without quotes)
     * @return {@code true} if dangerous, {@code false} if safe
     */
    private static boolean containsDangerousOperations(String expression) {
        String cmd = expression == null ? "" : expression.trim();
        if (cmd.isEmpty()) return false;

        // Reject non-ASCII characters (Unicode homoglyphs, etc.)
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c < 0x01 || c > 0x7F) return true;
        }

        // Reject curly braces (blocks)
        if (cmd.contains("{") || cmd.contains("}")) return true;

        // Reject newlines
        if (cmd.contains("\n")) return true;

        // Reject comments (# not immediately after s command)
        int hashIndex = cmd.indexOf('#');
        if (hashIndex != -1 && !(hashIndex > 0 && cmd.charAt(hashIndex - 1) == 's')) {
            return true;
        }

        // Reject negation operator
        if (cmd.startsWith("!") || Pattern.compile("[/\\d$]!").matcher(cmd).find()) {
            return true;
        }

        // Reject tilde in GNU step address format
        if (Pattern.compile("\\d\\s*~\\s*\\d|,\\s*~\\s*\\d|\\$\\s*~\\s*\\d").matcher(cmd).find()) {
            return true;
        }

        // Reject comma at start
        if (cmd.startsWith(",")) return true;

        // Reject comma followed by +/-  (GNU offset addresses)
        if (Pattern.compile(",\\s*[+-]").matcher(cmd).find()) return true;

        // Reject backslash tricks
        if (cmd.contains("s\\") || Pattern.compile("\\\\[|#%@]").matcher(cmd).find()) {
            return true;
        }

        // Reject escaped slashes followed by w/W
        if (Pattern.compile("\\\\/.*[wW]").matcher(cmd).find()) return true;

        // Reject /pattern whitespace dangerous commands
        if (Pattern.compile("/[^/]*\\s+[wWeE]").matcher(cmd).find()) return true;

        // Reject malformed substitution commands
        if (cmd.startsWith("s/") && !cmd.matches("^s/[^/]*/[^/]*/[^/]*$")) {
            return true;
        }

        // Reject s command ending with dangerous chars without a proper form
        if (cmd.matches("^s..*") && cmd.matches(".*[wWeE]$")) {
            // Check if it's a properly formed substitution
            boolean properSubst = Pattern.compile("^s([^\\\\\\n]).*?\\1.*?\\1[^wWeE]*$")
                    .matcher(cmd).find();
            if (!properSubst) return true;
        }

        // Check for dangerous write commands
        if (Pattern.compile("^[wW]\\s*\\S+").matcher(cmd).find()
                || Pattern.compile("^\\d+\\s*[wW]\\s*\\S+").matcher(cmd).find()
                || Pattern.compile("^\\$\\s*[wW]\\s*\\S+").matcher(cmd).find()
                || Pattern.compile("^/[^/]*/[IMim]*\\s*[wW]\\s*\\S+").matcher(cmd).find()
                || Pattern.compile("^\\d+,\\d+\\s*[wW]\\s*\\S+").matcher(cmd).find()
                || Pattern.compile("^\\d+,\\$\\s*[wW]\\s*\\S+").matcher(cmd).find()
                || Pattern.compile("^/[^/]*/[IMim]*,/[^/]*/[IMim]*\\s*[wW]\\s*\\S+").matcher(cmd).find()) {
            return true;
        }

        // Check for dangerous execute commands
        if (Pattern.compile("^e").matcher(cmd).find()
                || Pattern.compile("^\\d+\\s*e").matcher(cmd).find()
                || Pattern.compile("^\\$\\s*e").matcher(cmd).find()
                || Pattern.compile("^/[^/]*/[IMim]*\\s*e").matcher(cmd).find()
                || Pattern.compile("^\\d+,\\d+\\s*e").matcher(cmd).find()
                || Pattern.compile("^\\d+,\\$\\s*e").matcher(cmd).find()
                || Pattern.compile("^/[^/]*/[IMim]*,/[^/]*/[IMim]*\\s*e").matcher(cmd).find()) {
            return true;
        }

        // Check for substitution commands with dangerous flags (w, W, e, E)
        Matcher substitutionMatch = Pattern.compile("s([^\\\\\\n]).*?\\1.*?\\1(.*?)$").matcher(cmd);
        if (substitutionMatch.find()) {
            String sFlags = substitutionMatch.group(2) != null ? substitutionMatch.group(2) : "";
            if (sFlags.contains("w") || sFlags.contains("W")) return true;
            if (sFlags.contains("e") || sFlags.contains("E")) return true;
        }

        // Check for y command with dangerous chars
        Matcher yMatch = Pattern.compile("y([^\\\\\\n])").matcher(cmd);
        if (yMatch.find()) {
            if (Pattern.compile("[wWeE]").matcher(cmd).find()) return true;
        }

        return false;
    }

    // -----------------------------------------------------------------------
    // Cross-cutting constraint check
    // -----------------------------------------------------------------------

    /**
     * Cross-cutting validation step for sed commands.
     *
     * Returns {@link PermissionResult.PassthroughDecision} for non-sed commands or
     * safe sed commands, and {@link PermissionResult.AskDecision} for dangerous sed
     * operations.
     *
     * @param command               the full bash command string
     * @param toolPermissionContext context containing mode and permissions
     * @return permission result
     */
    public static PermissionResult checkSedConstraints(
            String command, ToolPermissionContext toolPermissionContext) {

        List<String> commands = BashUtils.splitCommand(command);

        for (String cmd : commands) {
            String trimmed = cmd.trim();
            String[] parts = trimmed.split("\\s+", 2);
            String baseCmd = parts.length > 0 ? parts[0] : "";

            if (!"sed".equals(baseCmd)) {
                continue;
            }

            boolean allowFileWrites =
                    toolPermissionContext.getMode() == PermissionMode.ACCEPT_EDITS;

            boolean isAllowed = sedCommandIsAllowedByAllowlist(trimmed, allowFileWrites);

            if (!isAllowed) {
                return PermissionResult.AskDecision.builder()
                        .message("sed command requires approval (contains potentially dangerous operations)")
                        .decisionReason(new PermissionResult.PermissionDecisionReason.OtherReason(
                                "sed command contains operations that require explicit approval "
                                + "(e.g., write commands, execute commands)"))
                        .build();
            }
        }

        return PermissionResult.PassthroughDecision.builder()
                .message("No dangerous sed operations detected")
                .build();
    }

    private SedValidation() {}
}
