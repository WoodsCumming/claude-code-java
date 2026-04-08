package com.anthropic.claudecode.util.bash;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for sed edit commands (-i flag substitutions).
 * Extracts file paths and substitution patterns to enable file-edit-style rendering.
 *
 * Translated from src/tools/BashTool/sedEditParser.ts
 */
@Slf4j
public final class SedEditParser {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SedEditParser.class);


    // -----------------------------------------------------------------------
    // BRE→ERE conversion placeholders (null-byte sentinels)
    // -----------------------------------------------------------------------

    private static final String BACKSLASH_PLACEHOLDER = "\u0000BACKSLASH\u0000";
    private static final String PLUS_PLACEHOLDER      = "\u0000PLUS\u0000";
    private static final String QUESTION_PLACEHOLDER  = "\u0000QUESTION\u0000";
    private static final String PIPE_PLACEHOLDER      = "\u0000PIPE\u0000";
    private static final String LPAREN_PLACEHOLDER    = "\u0000LPAREN\u0000";
    private static final String RPAREN_PLACEHOLDER    = "\u0000RPAREN\u0000";

    private static final Pattern SED_START_PATTERN = Pattern.compile("^\\s*sed\\s+");
    private static final Pattern SUBST_START_PATTERN = Pattern.compile("^s/");
    private static final Pattern VALID_FLAGS_PATTERN = Pattern.compile("^[gpimIM1-9]*$");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // -----------------------------------------------------------------------
    // SedEditInfo record
    // -----------------------------------------------------------------------

    /**
     * Information extracted from a {@code sed -i 's/pattern/replacement/flags' file} command.
     */
    public record SedEditInfo(
            /** The file path being edited. */
            String filePath,
            /** The search pattern (regex). */
            String pattern,
            /** The replacement string. */
            String replacement,
            /** Substitution flags (g, i, etc.). */
            String flags,
            /** Whether to use extended regex (-E or -r flag). */
            boolean extendedRegex
    ) {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Check if a command is a sed in-place edit command.
     * Returns {@code true} only for simple {@code sed -i 's/pattern/replacement/flags' file}
     * commands.
     */
    public static boolean isSedInPlaceEdit(String command) {
        return parseSedEditCommand(command) != null;
    }

    /**
     * Parse a sed edit command and extract the edit information.
     * Returns {@code null} if the command is not a valid sed in-place edit.
     */
    public static SedEditInfo parseSedEditCommand(String command) {
        if (command == null) return null;
        String trimmed = command.trim();

        // Must start with sed
        Matcher sedMatch = SED_START_PATTERN.matcher(trimmed);
        if (!sedMatch.find()) return null;

        String withoutSed = trimmed.substring(sedMatch.end());
        ShellQuoteUtils.ShellParseResult parseResult = ShellQuoteUtils.tryParseShellCommand(withoutSed);
        if (!parseResult.isSuccess()) return null;
        List<String> tokens = parseResult.tokens();

        // Collect string tokens only (reject glob patterns)
        List<String> args = new ArrayList<>();
        for (String token : tokens) {
            // ShellQuoteUtils returns plain strings; control operators like |, &, ; are also strings
            // If a token is a shell operator (single special char), treat it as a glob/complex pattern
            if (token.length() == 1 && "|&;<>".indexOf(token.charAt(0)) >= 0) {
                return null;
            }
            args.add(token);
        }

        // Parse flags and arguments
        boolean hasInPlaceFlag = false;
        boolean extendedRegex = false;
        String expression = null;
        String filePath = null;

        int i = 0;
        while (i < args.size()) {
            String arg = args.get(i);

            // -i / --in-place flag (with optional backup suffix on macOS)
            if ("-i".equals(arg) || "--in-place".equals(arg)) {
                hasInPlaceFlag = true;
                i++;
                if (i < args.size()) {
                    String nextArg = args.get(i);
                    if (!nextArg.startsWith("-") &&
                            (nextArg.isEmpty() || nextArg.startsWith("."))) {
                        i++; // Skip backup suffix
                    }
                }
                continue;
            }
            if (arg.startsWith("-i")) {
                // -i.bak or similar (inline suffix)
                hasInPlaceFlag = true;
                i++;
                continue;
            }

            // Extended regex flags
            if ("-E".equals(arg) || "-r".equals(arg) || "--regexp-extended".equals(arg)) {
                extendedRegex = true;
                i++;
                continue;
            }

            // -e / --expression flag
            if ("-e".equals(arg) || "--expression".equals(arg)) {
                if (i + 1 < args.size()) {
                    if (expression != null) return null; // Only single expression supported
                    expression = args.get(i + 1);
                    i += 2;
                    continue;
                }
                return null;
            }
            if (arg.startsWith("--expression=")) {
                if (expression != null) return null;
                expression = arg.substring("--expression=".length());
                i++;
                continue;
            }

            // Unknown flag
            if (arg.startsWith("-")) {
                return null;
            }

            // Non-flag argument
            if (expression == null) {
                expression = arg;
            } else if (filePath == null) {
                filePath = arg;
            } else {
                return null; // More than one file — not supported
            }
            i++;
        }

        // Must have -i flag, expression, and file path
        if (!hasInPlaceFlag || expression == null || expression.isEmpty() || filePath == null) {
            return null;
        }

        // Parse the substitution expression: s/pattern/replacement/flags
        Matcher substMatch = SUBST_START_PATTERN.matcher(expression);
        if (!substMatch.find()) {
            return null;
        }

        String rest = expression.substring(2); // Skip 's/'

        // Find pattern and replacement by tracking escaped characters
        StringBuilder patternBuf     = new StringBuilder();
        StringBuilder replacementBuf = new StringBuilder();
        StringBuilder flagsBuf       = new StringBuilder();

        enum State { PATTERN, REPLACEMENT, FLAGS }
        State state = State.PATTERN;
        int j = 0;

        while (j < rest.length()) {
            char ch = rest.charAt(j);

            if (ch == '\\' && j + 1 < rest.length()) {
                // Escaped character
                String escaped = "\\" + rest.charAt(j + 1);
                switch (state) {
                    case PATTERN     -> patternBuf.append(escaped);
                    case REPLACEMENT -> replacementBuf.append(escaped);
                    case FLAGS       -> flagsBuf.append(escaped);
                }
                j += 2;
                continue;
            }

            if (ch == '/') {
                switch (state) {
                    case PATTERN     -> state = State.REPLACEMENT;
                    case REPLACEMENT -> state = State.FLAGS;
                    case FLAGS       -> { return null; } // Extra delimiter in flags
                }
                j++;
                continue;
            }

            switch (state) {
                case PATTERN     -> patternBuf.append(ch);
                case REPLACEMENT -> replacementBuf.append(ch);
                case FLAGS       -> flagsBuf.append(ch);
            }
            j++;
        }

        // Must have reached the FLAGS state (i.e. found all three parts)
        if (state != State.FLAGS) {
            return null;
        }

        String flagsStr = flagsBuf.toString();
        if (!VALID_FLAGS_PATTERN.matcher(flagsStr).matches()) {
            return null;
        }

        return new SedEditInfo(filePath, patternBuf.toString(),
                replacementBuf.toString(), flagsStr, extendedRegex);
    }

    /**
     * Apply a sed substitution to file content.
     * Returns the new content after applying the substitution.
     *
     * @param content file content to transform
     * @param sedInfo parsed sed edit information
     * @return transformed content
     */
    public static String applySedSubstitution(String content, SedEditInfo sedInfo) {
        // Build Java regex flags
        int javaFlags = 0;
        String flagsStr = sedInfo.flags();

        if (flagsStr.contains("i") || flagsStr.contains("I")) {
            javaFlags |= Pattern.CASE_INSENSITIVE;
        }
        if (flagsStr.contains("m") || flagsStr.contains("M")) {
            javaFlags |= Pattern.MULTILINE;
        }

        boolean globalReplace = flagsStr.contains("g");

        // Convert sed pattern to Java regex pattern
        String jsPattern = sedInfo.pattern()
                .replace("\\/", "/");

        if (!sedInfo.extendedRegex()) {
            // BRE → ERE conversion:
            // Step 1: Protect literal backslashes
            jsPattern = jsPattern.replace("\\\\", BACKSLASH_PLACEHOLDER);
            // Step 2: Replace escaped BRE metacharacters with placeholders
            jsPattern = jsPattern
                    .replace("\\+", PLUS_PLACEHOLDER)
                    .replace("\\?", QUESTION_PLACEHOLDER)
                    .replace("\\|", PIPE_PLACEHOLDER)
                    .replace("\\(", LPAREN_PLACEHOLDER)
                    .replace("\\)", RPAREN_PLACEHOLDER);
            // Step 3: Escape unescaped metacharacters (literal in BRE)
            jsPattern = jsPattern
                    .replace("+", "\\+")
                    .replace("?", "\\?")
                    .replace("|", "\\|")
                    .replace("(", "\\(")
                    .replace(")", "\\)");
            // Step 4: Restore placeholders to their ERE/Java equivalents
            jsPattern = jsPattern
                    .replace(BACKSLASH_PLACEHOLDER, "\\\\")
                    .replace(PLUS_PLACEHOLDER,      "+")
                    .replace(QUESTION_PLACEHOLDER,  "?")
                    .replace(PIPE_PLACEHOLDER,      "|")
                    .replace(LPAREN_PLACEHOLDER,    "(")
                    .replace(RPAREN_PLACEHOLDER,    ")");
        }

        // Build Java replacement string
        // Use a random salt placeholder to prevent injection
        byte[] saltBytes = new byte[8];
        SECURE_RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        String escapedAmpPlaceholder = "___ESCAPED_AMPERSAND_" + salt + "___";

        String javaReplacement = sedInfo.replacement()
                .replace("\\/", "/")
                .replace("\\&", escapedAmpPlaceholder)
                // In Java Matcher.replaceAll, $0 is the full match (equivalent to & in sed)
                .replace("&", "\\$0")
                .replace(escapedAmpPlaceholder, "&");

        try {
            Pattern regex = Pattern.compile(jsPattern, javaFlags);
            Matcher matcher = regex.matcher(content);
            if (globalReplace) {
                return matcher.replaceAll(javaReplacement);
            } else {
                return matcher.replaceFirst(javaReplacement);
            }
        } catch (Exception e) {
            log.debug("Invalid regex in sed substitution, returning original content", e);
            return content;
        }
    }

    private SedEditParser() {}
}
