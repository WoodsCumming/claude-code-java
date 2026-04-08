package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown rendering utilities for CLI output.
 * Translated from src/utils/markdown.ts
 *
 * Handles converting Markdown tokens into terminal-formatted text,
 * including tables, lists, code blocks, headings, and hyperlinks.
 */
@Slf4j
public class MarkdownUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarkdownUtils.class);


    private static final String EOL = "\n";

    // Matches owner/repo#NNN style GitHub issue/PR references.
    private static final Pattern ISSUE_REF_PATTERN =
            Pattern.compile("(^|[^\\w./-])([A-Za-z0-9][\\w-]*/[A-Za-z0-9][\\w.-]*)#(\\d+)\\b");

    // =========================================================================
    // Token types (sealed hierarchy)
    // =========================================================================

    public sealed interface Token permits
            Token.Blockquote, Token.Code, Token.Codespan, Token.Em, Token.Strong,
            Token.Heading, Token.Hr, Token.Image, Token.Link,
            Token.ListToken, Token.ListItem, Token.Paragraph, Token.Space,
            Token.Br, Token.Text, Token.TableCell, Token.Table, Token.Escape,
            Token.Def, Token.Del, Token.Html, Token.Unknown {

        record Blockquote(List<Token> tokens) implements Token {}
        record Code(String text, String lang) implements Token {}
        record Codespan(String text) implements Token {}
        record Em(List<Token> tokens) implements Token {}
        record Strong(List<Token> tokens) implements Token {}
        record Heading(int depth, List<Token> tokens) implements Token {}
        record Hr() implements Token {}
        record Image(String href, String title, String text) implements Token {}
        record Link(String href, List<Token> tokens) implements Token {}
        record ListToken(List<Token> items, boolean ordered, int start) implements Token {}
        record ListItem(List<Token> tokens) implements Token {}
        record Paragraph(List<Token> tokens) implements Token {}
        record Space() implements Token {}
        record Br() implements Token {}
        record Text(String text, List<Token> tokens) implements Token {}
        record TableCell(List<Token> tokens) implements Token {}
        record Table(List<TableCell> header, List<List<TableCell>> rows,
                     List<Alignment> align) implements Token {}
        record Escape(String text) implements Token {}
        record Def() implements Token {}
        record Del() implements Token {}
        record Html() implements Token {}
        record Unknown() implements Token {}

        enum Alignment { LEFT, CENTER, RIGHT, NONE }
    }

    // =========================================================================
    // Markdown application
    // =========================================================================

    /**
     * Apply markdown formatting to content for terminal output.
     * Stripped version: returns plain text without ANSI codes (for non-terminal use).
     */
    public static String applyMarkdown(String content) {
        if (content == null || content.isBlank()) return "";
        // In a real implementation this would invoke the markdown lexer/parser.
        // Here we return the content stripped of common markdown syntax.
        return stripMarkdown(content).trim();
    }

    /**
     * Format a single token into terminal-displayable text.
     * Translated from formatToken() in markdown.ts
     */
    public static String formatToken(Token token, int listDepth,
                                     Integer orderedListNumber, Token parent) {
        return switch (token) {
            case Token.Blockquote bq -> {
                String inner = bq.tokens().stream()
                        .map(t -> formatToken(t, 0, null, null))
                        .reduce("", String::concat);
                StringBuilder result = new StringBuilder();
                for (String line : inner.split(EOL, -1)) {
                    if (!line.strip().isEmpty()) {
                        result.append("| ").append(line);
                    } else {
                        result.append(line);
                    }
                    result.append(EOL);
                }
                yield result.toString();
            }
            case Token.Code c -> c.text() + EOL;
            case Token.Codespan cs -> "`" + cs.text() + "`";
            case Token.Em em -> em.tokens().stream()
                    .map(t -> formatToken(t, 0, null, parent))
                    .reduce("", String::concat);
            case Token.Strong s -> s.tokens().stream()
                    .map(t -> formatToken(t, 0, null, parent))
                    .reduce("", String::concat);
            case Token.Heading h -> {
                String text = h.tokens().stream()
                        .map(t -> formatToken(t, 0, null, null))
                        .reduce("", String::concat);
                yield text + EOL + EOL;
            }
            case Token.Hr ignored -> "---";
            case Token.Image img -> img.href();
            case Token.Link link -> {
                if (link.href().startsWith("mailto:")) {
                    yield link.href().replaceFirst("^mailto:", "");
                }
                String linkText = link.tokens().stream()
                        .map(t -> formatToken(t, 0, null, link))
                        .reduce("", String::concat);
                yield linkText.isBlank() ? link.href() : linkText + " (" + link.href() + ")";
            }
            case Token.ListToken list -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.items().size(); i++) {
                    Integer num = list.ordered() ? list.start() + i : null;
                    sb.append(formatToken(list.items().get(i), listDepth, num, list));
                }
                yield sb.toString();
            }
            case Token.ListItem li -> li.tokens().stream()
                    .map(t -> "  ".repeat(listDepth) +
                            formatToken(t, listDepth + 1, orderedListNumber, li))
                    .reduce("", String::concat);
            case Token.Paragraph p -> p.tokens().stream()
                    .map(t -> formatToken(t, 0, null, null))
                    .reduce("", String::concat) + EOL;
            case Token.Space ignored -> EOL;
            case Token.Br ignored -> EOL;
            case Token.Text txt -> {
                if (parent instanceof Token.Link) {
                    yield txt.text();
                }
                if (parent instanceof Token.ListItem) {
                    String prefix = orderedListNumber == null
                            ? "-"
                            : getListNumber(listDepth, orderedListNumber) + ".";
                    String inner = txt.tokens() != null && !txt.tokens().isEmpty()
                            ? txt.tokens().stream()
                            .map(t -> formatToken(t, listDepth, orderedListNumber, txt))
                            .reduce("", String::concat)
                            : linkifyIssueReferences(txt.text());
                    yield prefix + " " + inner + EOL;
                }
                yield linkifyIssueReferences(txt.text());
            }
            case Token.Table tbl -> formatTable(tbl);
            case Token.Escape esc -> esc.text();
            case Token.Def ignored -> "";
            case Token.Del ignored -> "";
            case Token.Html ignored -> "";
            default -> "";
        };
    }

    // =========================================================================
    // Table formatting
    // =========================================================================

    private static String formatTable(Token.Table tableToken) {
        // Compute column widths
        int cols = tableToken.header().size();
        int[] colWidths = new int[cols];
        for (int i = 0; i < cols; i++) {
            String headerText = getCellText(tableToken.header().get(i));
            colWidths[i] = Math.max(headerText.length(), 3);
            for (List<Token.TableCell> row : tableToken.rows()) {
                if (i < row.size()) {
                    colWidths[i] = Math.max(colWidths[i], getCellText(row.get(i)).length());
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        // Header
        sb.append("| ");
        for (int i = 0; i < cols; i++) {
            Token.Alignment align = i < tableToken.align().size() ? tableToken.align().get(i) : Token.Alignment.NONE;
            String content = renderCell(tableToken.header().get(i));
            String displayText = getCellText(tableToken.header().get(i));
            sb.append(padAligned(content, displayText.length(), colWidths[i], align)).append(" | ");
        }
        sb.setLength(sb.length() - 1); // trim trailing space before newline
        sb.append(EOL);

        // Separator
        sb.append("|");
        for (int w : colWidths) {
            sb.append("-".repeat(w + 2)).append("|");
        }
        sb.append(EOL);

        // Data rows
        for (List<Token.TableCell> row : tableToken.rows()) {
            sb.append("| ");
            for (int i = 0; i < cols; i++) {
                Token.Alignment align = i < tableToken.align().size() ? tableToken.align().get(i) : Token.Alignment.NONE;
                Token.TableCell cell = i < row.size() ? row.get(i) : new Token.TableCell(List.of());
                String content = renderCell(cell);
                String displayText = getCellText(cell);
                sb.append(padAligned(content, displayText.length(), colWidths[i], align)).append(" | ");
            }
            sb.setLength(sb.length() - 1);
            sb.append(EOL);
        }
        sb.append(EOL);
        return sb.toString();
    }

    private static String getCellText(Token.TableCell cell) {
        return cell.tokens().stream()
                .map(t -> formatToken(t, 0, null, null))
                .reduce("", String::concat);
    }

    private static String renderCell(Token.TableCell cell) {
        return getCellText(cell);
    }

    // =========================================================================
    // Padding
    // =========================================================================

    /**
     * Pad content to targetWidth according to alignment.
     * Translated from padAligned() in markdown.ts
     */
    public static String padAligned(String content, int displayWidth, int targetWidth,
                                     Token.Alignment align) {
        int padding = Math.max(0, targetWidth - displayWidth);
        return switch (align) {
            case CENTER -> {
                int leftPad = padding / 2;
                yield " ".repeat(leftPad) + content + " ".repeat(padding - leftPad);
            }
            case RIGHT -> " ".repeat(padding) + content;
            default -> content + " ".repeat(padding);
        };
    }

    // =========================================================================
    // Issue reference linkification
    // =========================================================================

    /**
     * Replace owner/repo#123 references with hyperlink-style text.
     * Translated from linkifyIssueReferences() in markdown.ts
     */
    public static String linkifyIssueReferences(String text) {
        if (text == null) return "";
        Matcher m = ISSUE_REF_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String prefix = m.group(1);
            String repo = m.group(2);
            String num = m.group(3);
            String url = "https://github.com/" + repo + "/issues/" + num;
            m.appendReplacement(sb, Matcher.quoteReplacement(prefix + repo + "#" + num + " (" + url + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // =========================================================================
    // List number helpers
    // =========================================================================

    /**
     * Get the list item label for a given depth and ordinal.
     * Translated from getListNumber() in markdown.ts
     */
    public static String getListNumber(int listDepth, int orderedListNumber) {
        return switch (listDepth) {
            case 0, 1 -> String.valueOf(orderedListNumber);
            case 2 -> numberToLetter(orderedListNumber);
            case 3 -> numberToRoman(orderedListNumber);
            default -> String.valueOf(orderedListNumber);
        };
    }

    private static String numberToLetter(int n) {
        StringBuilder result = new StringBuilder();
        while (n > 0) {
            n--;
            result.insert(0, (char) ('a' + (n % 26)));
            n = n / 26;
        }
        return result.toString();
    }

    private static final int[][] ROMAN_VALUES = {
            {1000, 'm'}, {900, 'c'}, {500, 'd'}, {400, 'c'},
            {100, 'c'}, {90, 'x'}, {50, 'l'}, {40, 'x'},
            {10, 'x'}, {9, 'i'}, {5, 'v'}, {4, 'i'}, {1, 'i'}
    };

    private static final String[] ROMAN_NUMERALS =
            {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
    private static final int[] ROMAN_NUMBERS =
            {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};

    private static String numberToRoman(int n) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < ROMAN_NUMBERS.length; i++) {
            while (n >= ROMAN_NUMBERS[i]) {
                result.append(ROMAN_NUMERALS[i]);
                n -= ROMAN_NUMBERS[i];
            }
        }
        return result.toString();
    }

    // =========================================================================
    // Strip markdown (simple helper)
    // =========================================================================

    /**
     * Strip basic markdown syntax from a string for plain-text output.
     */
    public static String stripMarkdown(String text) {
        if (text == null) return "";
        return text
                .replaceAll("^#{1,6}\\s+", "")       // headings
                .replaceAll("[*_]{1,2}(.+?)[*_]{1,2}", "$1") // bold/italic
                .replaceAll("`{1,3}[^`]*`{1,3}", "")  // inline/code blocks
                .replaceAll("!\\[.*?]\\(.*?\\)", "")   // images
                .replaceAll("\\[(.+?)]\\(.*?\\)", "$1") // links
                .replaceAll("^>\\s+", "");              // blockquotes
    }

    private MarkdownUtils() {}
}
