package com.anthropic.claudecode.util.bash;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heredoc extraction and restoration utilities.
 *
 * The shell-quote library parses {@code <<} as two separate {@code <} redirect
 * operators, which breaks command splitting for heredoc syntax. This class
 * provides utilities to extract heredocs before parsing and restore them after.
 *
 * Translated from src/utils/bash/heredoc.ts
 *
 * Supported heredoc variations:
 * <ul>
 *   <li>{@code <<WORD}      — basic heredoc</li>
 *   <li>{@code <<'WORD'}    — single-quoted delimiter (no variable expansion)</li>
 *   <li>{@code <<"WORD"}    — double-quoted delimiter (with variable expansion)</li>
 *   <li>{@code <<-WORD}     — dash prefix (strips leading tabs from content)</li>
 *   <li>{@code <<-'WORD'}   — combined dash and quoted delimiter</li>
 * </ul>
 *
 * When extraction fails, the command passes through unchanged. This is safe
 * because the unextracted heredoc will either cause shell-quote parsing to fail
 * (falling back to treating the whole command as one unit) or require manual
 * approval for each apparent subcommand.
 */
@Slf4j
public final class HeredocUtils {



    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String HEREDOC_PLACEHOLDER_PREFIX = "__HEREDOC_";
    private static final String HEREDOC_PLACEHOLDER_SUFFIX = "__";

    /**
     * Regex pattern for matching heredoc start syntax.
     *
     * Two alternatives handle quoted vs unquoted delimiters:
     *
     * Alternative 1 (quoted): (['"]) (\\?\w+) \1
     *   Captures opening quote, then delimiter word (may include a leading
     *   backslash since it's literal inside quotes), then the closing quote.
     *
     * Alternative 2 (unquoted): \\?(\w+)
     *   Optionally consumes a leading backslash (escape), then captures the word.
     *
     * Note: Uses [ \t]* (not \s*) to avoid matching across newlines.
     */
    private static final Pattern HEREDOC_START_PATTERN = Pattern.compile(
            "(?<!<)<<(?!<)(-)?[ \\t]*(?:(['\"])(\\\\.?\\w+)\\2|\\\\?(\\w+))");

    private static final SecureRandom RANDOM = new SecureRandom();

    // -----------------------------------------------------------------------
    // Data types
    // -----------------------------------------------------------------------

    /**
     * Describes a single heredoc found in a command string.
     */
    public record HeredocInfo(
            /** The full heredoc text including {@code <<} operator, delimiter, content, and closing delimiter. */
            String fullText,
            /** The delimiter word (without quotes). */
            String delimiter,
            /** Start position of the {@code <<} operator in the original command. */
            int operatorStartIndex,
            /** End position of the {@code <<} operator (exclusive). */
            int operatorEndIndex,
            /** Start position of heredoc content (the newline before content). */
            int contentStartIndex,
            /** End position of heredoc content including closing delimiter (exclusive). */
            int contentEndIndex) {}

    /**
     * Result of extracting heredocs from a command string.
     */
    public record HeredocExtractionResult(
            /** The command with heredocs replaced by placeholders. */
            String processedCommand,
            /** Map of placeholder string to original heredoc info. */
            Map<String, HeredocInfo> heredocs) {}

    // -----------------------------------------------------------------------
    // generatePlaceholderSalt
    // -----------------------------------------------------------------------

    /**
     * Generates a random hex string for placeholder uniqueness.
     * Prevents collision when command text literally contains {@code __HEREDOC_N__}.
     */
    private static String generatePlaceholderSalt() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // extractHeredocs
    // -----------------------------------------------------------------------

    /**
     * Extracts heredocs from a command string and replaces them with placeholders.
     *
     * <p>This allows shell-quote to parse the command without mangling heredoc
     * syntax. After parsing, use {@link #restoreHeredocs} to replace placeholders
     * with original content.
     *
     * @param command     the shell command string potentially containing heredocs
     * @param quotedOnly  when true, only extract quoted/escaped-delimiter heredocs;
     *                    unquoted heredoc bodies remain visible to security validators
     * @return object containing the processed command and a map of placeholders
     */
    public static HeredocExtractionResult extractHeredocs(String command,
                                                           boolean quotedOnly) {
        Map<String, HeredocInfo> heredocs = new LinkedHashMap<>();

        if (command == null || !command.contains("<<")) {
            return new HeredocExtractionResult(command == null ? "" : command, heredocs);
        }

        // Security: bail if command contains $'...' or $"..." (ANSI-C quoting)
        if (command.contains("$'") || command.contains("$\"")) {
            return new HeredocExtractionResult(command, heredocs);
        }

        // Check for backticks before the first <<
        int firstHeredocPos = command.indexOf("<<");
        if (firstHeredocPos > 0 && command.substring(0, firstHeredocPos).contains("`")) {
            return new HeredocExtractionResult(command, heredocs);
        }

        // Security: bail if unbalanced (( before first <<
        if (firstHeredocPos > 0) {
            String beforeHeredoc = command.substring(0, firstHeredocPos);
            int openArith  = countOccurrences(beforeHeredoc, "((");
            int closeArith = countOccurrences(beforeHeredoc, "))");
            if (openArith > closeArith) {
                return new HeredocExtractionResult(command, heredocs);
            }
        }

        List<HeredocInfo> heredocMatches = new ArrayList<>();
        List<int[]> skippedHeredocRanges = new ArrayList<>(); // [contentStartIndex, contentEndIndex]

        // Incremental quote/comment scanner state
        int    scanPos                = 0;
        boolean scanInSingleQuote     = false;
        boolean scanInDoubleQuote     = false;
        boolean scanInComment         = false;
        boolean scanDqEscapeNext      = false;
        int    scanPendingBackslashes = 0;

        // We need a mutable wrapper for the scanner state to use inside a helper.
        // Java lambdas can't capture mutable locals, so we use a small int[] array.
        // Layout: [0]=scanPos, [1]=inSingle (0/1), [2]=inDouble (0/1),
        //         [3]=inComment (0/1), [4]=dqEscapeNext (0/1), [5]=pendingBackslashes
        int[] scan = {0, 0, 0, 0, 0, 0};

        Matcher matcher = HEREDOC_START_PATTERN.matcher(command);

        while (matcher.find()) {
            int startIndex = matcher.start();

            // Advance scanner to startIndex
            for (int i = scan[0]; i < startIndex; i++) {
                char ch = command.charAt(i);

                if (ch == '\n') { scan[3] = 0; } // clear comment state

                if (scan[1] == 1) { // inSingleQuote
                    if (ch == '\'') scan[1] = 0;
                    continue;
                }
                if (scan[2] == 1) { // inDoubleQuote
                    if (scan[4] == 1) { scan[4] = 0; continue; }
                    if (ch == '\\') { scan[4] = 1; continue; }
                    if (ch == '"')  { scan[2] = 0; }
                    continue;
                }
                // Unquoted context
                if (ch == '\\') { scan[5]++; continue; }
                boolean escaped = scan[5] % 2 == 1;
                scan[5] = 0;
                if (escaped) continue;
                if      (ch == '\'') scan[1] = 1;
                else if (ch == '"')  scan[2] = 1;
                else if (scan[3] == 0 && ch == '#') scan[3] = 1;
            }
            scan[0] = startIndex;

            // Skip if inside quoted string or comment
            if (scan[1] == 1 || scan[2] == 1) continue;
            if (scan[3] == 1) continue;
            // Skip if preceded by odd backslashes (escaped <<)
            if (scan[5] % 2 == 1) continue;

            // Skip if inside a skipped heredoc body
            boolean insideSkipped = false;
            for (int[] skipped : skippedHeredocRanges) {
                if (startIndex > skipped[0] && startIndex < skipped[1]) {
                    insideSkipped = true;
                    break;
                }
            }
            if (insideSkipped) continue;

            String fullMatch      = matcher.group(0);
            String isDashStr      = matcher.group(1);
            boolean isDash        = "-".equals(isDashStr);
            // group(3) = quoted delimiter, group(4) = unquoted delimiter
            String delimiter      = matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            if (delimiter == null) continue;

            int operatorEndIndex = startIndex + fullMatch.length();

            // Security check 1: verify closing quote was captured for quoted alternatives
            String quoteChar = matcher.group(2);
            if (quoteChar != null && !quoteChar.isEmpty()) {
                char expectedClose = quoteChar.charAt(0);
                if (operatorEndIndex == 0
                        || command.charAt(operatorEndIndex - 1) != expectedClose) {
                    continue;
                }
            }

            // Security: check whether delimiter is quoted/escaped
            boolean isEscapedDelimiter = fullMatch.contains("\\");
            boolean isQuotedOrEscaped  = (quoteChar != null && !quoteChar.isEmpty())
                    || isEscapedDelimiter;

            // Security check 2: next character must be a bash word terminator
            if (operatorEndIndex < command.length()) {
                char nextChar = command.charAt(operatorEndIndex);
                if (" \t\n|&;()<>".indexOf(nextChar) == -1
                        || !String.valueOf(nextChar).matches("[ \\t\\n|&;()<>]")) {
                    continue;
                }
            }

            // Find the first unquoted newline after the operator
            int firstNewlineOffset = findFirstUnquotedNewline(command, operatorEndIndex);
            if (firstNewlineOffset == -1) continue;

            // Security: check for backslash-newline continuation at end of same-line content
            String sameLineContent = command.substring(operatorEndIndex,
                    operatorEndIndex + firstNewlineOffset);
            int trailingBackslashes = 0;
            for (int j = sameLineContent.length() - 1; j >= 0; j--) {
                if (sameLineContent.charAt(j) == '\\') trailingBackslashes++;
                else break;
            }
            if (trailingBackslashes % 2 == 1) continue; // line continuation

            int contentStartIndex = operatorEndIndex + firstNewlineOffset;
            String afterNewline   = command.substring(contentStartIndex + 1); // skip '\n'
            String[] contentLines = afterNewline.split("\n", -1);

            // Find closing delimiter line
            int closingLineIndex = -1;
            outer:
            for (int i = 0; i < contentLines.length; i++) {
                String line = contentLines[i];
                String checkLine = isDash ? line.replaceFirst("^\t*", "") : line;

                if (checkLine.equals(delimiter)) {
                    closingLineIndex = i;
                    break;
                }

                // Security: PST_EOFTOKEN-like early closure
                if (checkLine.length() > delimiter.length()
                        && checkLine.startsWith(delimiter)) {
                    char charAfter = checkLine.charAt(delimiter.length());
                    if (")}`|&;(<>".indexOf(charAfter) != -1) {
                        closingLineIndex = -1;
                        break;
                    }
                }
            }

            // If quotedOnly and this is an unquoted heredoc, record skipped range
            if (quotedOnly && !isQuotedOrEscaped) {
                int skipContentEndIndex;
                if (closingLineIndex == -1) {
                    skipContentEndIndex = command.length();
                } else {
                    String[] linesUpTo = java.util.Arrays.copyOfRange(
                            contentLines, 0, closingLineIndex + 1);
                    int skipLen = String.join("\n", linesUpTo).length();
                    skipContentEndIndex = contentStartIndex + 1 + skipLen;
                }
                skippedHeredocRanges.add(new int[]{contentStartIndex, skipContentEndIndex});
                continue;
            }

            if (closingLineIndex == -1) continue;

            String[] linesUpToClosing = java.util.Arrays.copyOfRange(
                    contentLines, 0, closingLineIndex + 1);
            int contentLength   = String.join("\n", linesUpToClosing).length();
            int contentEndIndex = contentStartIndex + 1 + contentLength;

            // Security: bail if this heredoc's content overlaps a skipped range
            boolean overlapsSkipped = false;
            for (int[] skipped : skippedHeredocRanges) {
                if (contentStartIndex < skipped[1] && skipped[0] < contentEndIndex) {
                    overlapsSkipped = true;
                    break;
                }
            }
            if (overlapsSkipped) continue;

            String operatorText = command.substring(startIndex, operatorEndIndex);
            String contentText  = command.substring(contentStartIndex, contentEndIndex);
            String fullText     = operatorText + contentText;

            heredocMatches.add(new HeredocInfo(
                    fullText, delimiter, startIndex, operatorEndIndex,
                    contentStartIndex, contentEndIndex));
        }

        if (heredocMatches.isEmpty()) {
            return new HeredocExtractionResult(command, heredocs);
        }

        // Filter out nested heredocs
        List<HeredocInfo> topLevelHeredocs = new ArrayList<>();
        for (HeredocInfo candidate : heredocMatches) {
            boolean nested = false;
            for (HeredocInfo other : heredocMatches) {
                if (candidate == other) continue;
                if (candidate.operatorStartIndex() > other.contentStartIndex()
                        && candidate.operatorStartIndex() < other.contentEndIndex()) {
                    nested = true;
                    break;
                }
            }
            if (!nested) topLevelHeredocs.add(candidate);
        }

        if (topLevelHeredocs.isEmpty()) {
            return new HeredocExtractionResult(command, heredocs);
        }

        // Ensure no two heredocs share the same content start position
        Set<Integer> contentStartPositions = new HashSet<>();
        for (HeredocInfo h : topLevelHeredocs) {
            contentStartPositions.add(h.contentStartIndex());
        }
        if (contentStartPositions.size() < topLevelHeredocs.size()) {
            return new HeredocExtractionResult(command, heredocs);
        }

        // Sort descending by contentEndIndex to replace from end to start
        topLevelHeredocs.sort((a, b) -> b.contentEndIndex() - a.contentEndIndex());

        String salt = generatePlaceholderSalt();
        String processedCommand = command;

        for (int index = 0; index < topLevelHeredocs.size(); index++) {
            HeredocInfo info = topLevelHeredocs.get(index);
            int placeholderIndex = topLevelHeredocs.size() - 1 - index;
            String placeholder = HEREDOC_PLACEHOLDER_PREFIX + placeholderIndex
                    + "_" + salt + HEREDOC_PLACEHOLDER_SUFFIX;

            heredocs.put(placeholder, info);

            processedCommand =
                    processedCommand.substring(0, info.operatorStartIndex())
                    + placeholder
                    + processedCommand.substring(info.operatorEndIndex(), info.contentStartIndex())
                    + processedCommand.substring(info.contentEndIndex());
        }

        return new HeredocExtractionResult(processedCommand, heredocs);
    }

    /**
     * Extracts heredocs from a command string (all heredoc types included).
     */
    public static HeredocExtractionResult extractHeredocs(String command) {
        return extractHeredocs(command, false);
    }

    // -----------------------------------------------------------------------
    // restoreHeredocs
    // -----------------------------------------------------------------------

    /**
     * Restores heredoc placeholders in an array of strings.
     *
     * @param parts    array of strings that may contain heredoc placeholders
     * @param heredocs the map of placeholders from {@link #extractHeredocs}
     * @return new list with placeholders replaced by original heredoc content
     */
    public static List<String> restoreHeredocs(List<String> parts,
                                                Map<String, HeredocInfo> heredocs) {
        if (heredocs == null || heredocs.isEmpty()) {
            return parts;
        }
        List<String> result = new ArrayList<>(parts.size());
        for (String part : parts) {
            result.add(restoreHeredocsInString(part, heredocs));
        }
        return result;
    }

    private static String restoreHeredocsInString(String text,
                                                   Map<String, HeredocInfo> heredocs) {
        String result = text;
        for (Map.Entry<String, HeredocInfo> entry : heredocs.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue().fullText());
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // containsHeredoc
    // -----------------------------------------------------------------------

    /**
     * Checks if a command contains heredoc syntax.
     *
     * This is a quick check that does not validate the heredoc is well-formed,
     * just that the pattern exists.
     *
     * @param command the shell command string
     * @return true if the command appears to contain heredoc syntax
     */
    public static boolean containsHeredoc(String command) {
        if (command == null) return false;
        return HEREDOC_START_PATTERN.matcher(command).find();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Finds the offset (from {@code startPos}) of the first newline that is not
     * inside a quoted string. Returns -1 if no such newline exists or if we end
     * while still inside a quote.
     */
    private static int findFirstUnquotedNewline(String command, int startPos) {
        boolean inSingle = false;
        boolean inDouble = false;

        for (int k = startPos; k < command.length(); k++) {
            char ch = command.charAt(k);

            if (inSingle) {
                if (ch == '\'') inSingle = false;
                continue;
            }
            if (inDouble) {
                if (ch == '\\') { k++; continue; } // skip escaped char
                if (ch == '"')  inDouble = false;
                continue;
            }
            // Unquoted context
            if (ch == '\n') return k - startPos;

            // Backslash count for escape detection
            int backslashCount = 0;
            for (int j = k - 1; j >= startPos && command.charAt(j) == '\\'; j--) {
                backslashCount++;
            }
            if (backslashCount % 2 == 1) continue; // escaped char

            if (ch == '\'') inSingle = true;
            else if (ch == '"') inDouble = true;
        }
        return -1; // ended inside a quote or no newline found
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private HeredocUtils() {}
}
