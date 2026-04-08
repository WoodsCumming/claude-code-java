package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.FileEditToolTypes.EditInput;
import com.anthropic.claudecode.model.FileEditToolTypes.FileEdit;
import com.anthropic.claudecode.model.FileEditToolTypes.StructuredPatchHunk;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for the FileEditTool.
 * Translated from src/tools/FileEditTool/utils.ts
 */
@Slf4j
public final class FileEditToolUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileEditToolUtils.class);


    // Claude can't output curly quotes, so we define them as constants here.
    // We normalize curly quotes to straight quotes when applying edits.
    public static final String LEFT_SINGLE_CURLY_QUOTE  = "\u2018"; // '
    public static final String RIGHT_SINGLE_CURLY_QUOTE = "\u2019"; // '
    public static final String LEFT_DOUBLE_CURLY_QUOTE  = "\u201C"; // "
    public static final String RIGHT_DOUBLE_CURLY_QUOTE = "\u201D"; // "

    // Cap on edited_text_file attachment snippets (8 KB).
    private static final int DIFF_SNIPPET_MAX_BYTES = 8192;

    private static final int CONTEXT_LINES = 4;

    /**
     * Contains replacements to de-sanitize strings from Claude.
     * Since Claude can't see any of these strings (sanitized in the API)
     * it will output the sanitized versions in the edit response.
     *
     * Key/value pairs use tag-like strings.  To avoid XML parsing issues
     * in the source file the angle-bracket characters are built with
     * String.valueOf((char)60) for '<' and (char)62 for '>'.
     */
    private static final Map<String, String> DESANITIZATIONS;

    static {
        // Build angle-bracket helpers to keep this source XML-safe.
        final String LT = "<";
        final String GT = ">";
        final String SL = "/";

        DESANITIZATIONS = new LinkedHashMap<>();
        DESANITIZATIONS.put(LT + "fnr"  + GT, LT + "function_results" + GT);
        DESANITIZATIONS.put(LT + "n"    + GT, LT + "name"   + GT);
        DESANITIZATIONS.put(LT + SL + "n"  + GT, LT + SL + "name"   + GT);
        DESANITIZATIONS.put(LT + "o"    + GT, LT + "output" + GT);
        DESANITIZATIONS.put(LT + SL + "o"  + GT, LT + SL + "output" + GT);
        DESANITIZATIONS.put(LT + "e"    + GT, LT + "error"  + GT);
        DESANITIZATIONS.put(LT + SL + "e"  + GT, LT + SL + "error"  + GT);
        DESANITIZATIONS.put(LT + "s"    + GT, LT + "system" + GT);
        DESANITIZATIONS.put(LT + SL + "s"  + GT, LT + SL + "system" + GT);
        DESANITIZATIONS.put(LT + "r"    + GT, LT + "result" + GT);
        DESANITIZATIONS.put(LT + SL + "r"  + GT, LT + SL + "result" + GT);
        DESANITIZATIONS.put("< META_START >", LT + "META_START"  + GT);
        DESANITIZATIONS.put("< META_END >",   LT + "META_END"    + GT);
        DESANITIZATIONS.put("< EOT >",        LT + "EOT"         + GT);
        DESANITIZATIONS.put("< META >",       LT + "META"        + GT);
        DESANITIZATIONS.put("< SOS >",        LT + "SOS"         + GT);
        DESANITIZATIONS.put("\n\nH:", "\n\nHuman:");
        DESANITIZATIONS.put("\n\nA:", "\n\nAssistant:");
    }

    private FileEditToolUtils() {}

    // -----------------------------------------------------------------------
    // Quote normalization
    // -----------------------------------------------------------------------

    /**
     * Normalizes quotes in a string by converting curly quotes to straight quotes.
     */
    public static String normalizeQuotes(String str) {
        return str
            .replace(LEFT_SINGLE_CURLY_QUOTE,  "'")
            .replace(RIGHT_SINGLE_CURLY_QUOTE, "'")
            .replace(LEFT_DOUBLE_CURLY_QUOTE,  "\"")
            .replace(RIGHT_DOUBLE_CURLY_QUOTE, "\"");
    }

    /**
     * Strips trailing whitespace from each line while preserving line endings.
     */
    public static String stripTrailingWhitespace(String str) {
        // Split on CRLF, LF, or CR while keeping the delimiters
        Pattern splitter = Pattern.compile("(\\r\\n|\\n|\\r)");
        String[] parts = splitter.split(str, -1);
        Matcher m = splitter.matcher(str);

        List<String> endings = new ArrayList<>();
        while (m.find()) {
            endings.add(m.group());
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            // Strip trailing whitespace from content part
            result.append(parts[i].replaceAll("\\s+$", ""));
            if (i < endings.size()) {
                result.append(endings.get(i));
            }
        }
        return result.toString();
    }

    /**
     * Finds the actual string in the file content that matches the search string,
     * accounting for quote normalization.
     *
     * @return the actual string found in the file, or null if not found
     */
    public static String findActualString(String fileContent, String searchString) {
        // First try exact match
        if (fileContent.contains(searchString)) {
            return searchString;
        }

        // Try with normalized quotes
        String normalizedSearch = normalizeQuotes(searchString);
        String normalizedFile   = normalizeQuotes(fileContent);

        int searchIndex = normalizedFile.indexOf(normalizedSearch);
        if (searchIndex != -1) {
            return fileContent.substring(searchIndex, searchIndex + searchString.length());
        }

        return null;
    }

    // -----------------------------------------------------------------------
    // Curly-quote preservation
    // -----------------------------------------------------------------------

    /**
     * When old_string matched via quote normalization, apply the same curly quote
     * style to new_string so the edit preserves the file's typography.
     */
    public static String preserveQuoteStyle(String oldString, String actualOldString, String newString) {
        if (oldString.equals(actualOldString)) {
            return newString;
        }

        boolean hasDoubleQuotes = actualOldString.contains(LEFT_DOUBLE_CURLY_QUOTE)
                || actualOldString.contains(RIGHT_DOUBLE_CURLY_QUOTE);
        boolean hasSingleQuotes = actualOldString.contains(LEFT_SINGLE_CURLY_QUOTE)
                || actualOldString.contains(RIGHT_SINGLE_CURLY_QUOTE);

        if (!hasDoubleQuotes && !hasSingleQuotes) {
            return newString;
        }

        String result = newString;
        if (hasDoubleQuotes) {
            result = applyCurlyDoubleQuotes(result);
        }
        if (hasSingleQuotes) {
            result = applyCurlySingleQuotes(result);
        }
        return result;
    }

    private static boolean isOpeningContext(int[] codePoints, int index) {
        if (index == 0) {
            return true;
        }
        int prev = codePoints[index - 1];
        return prev == ' ' || prev == '\t' || prev == '\n' || prev == '\r'
                || prev == '(' || prev == '[' || prev == '{'
                || prev == '\u2014' /* em dash */ || prev == '\u2013' /* en dash */;
    }

    private static String applyCurlyDoubleQuotes(String str) {
        int[] cps = str.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            if (cps[i] == '"') {
                sb.append(isOpeningContext(cps, i) ? LEFT_DOUBLE_CURLY_QUOTE : RIGHT_DOUBLE_CURLY_QUOTE);
            } else {
                sb.appendCodePoint(cps[i]);
            }
        }
        return sb.toString();
    }

    private static String applyCurlySingleQuotes(String str) {
        int[] cps = str.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            if (cps[i] == '\'') {
                boolean prevIsLetter = i > 0
                        && Character.isLetter(cps[i - 1]);
                boolean nextIsLetter = i < cps.length - 1
                        && Character.isLetter(cps[i + 1]);
                if (prevIsLetter && nextIsLetter) {
                    // Apostrophe in a contraction — use right single curly quote
                    sb.append(RIGHT_SINGLE_CURLY_QUOTE);
                } else {
                    sb.append(isOpeningContext(cps, i) ? LEFT_SINGLE_CURLY_QUOTE : RIGHT_SINGLE_CURLY_QUOTE);
                }
            } else {
                sb.appendCodePoint(cps[i]);
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Edit application
    // -----------------------------------------------------------------------

    /**
     * Applies a single edit (old_string -> new_string) to originalContent.
     */
    public static String applyEditToFile(
            String originalContent,
            String oldString,
            String newString,
            boolean replaceAll) {

        if (!newString.isEmpty()) {
            return replaceAll
                    ? originalContent.replace(oldString, newString)
                    : originalContent.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
        }

        boolean stripTrailingNewline =
                !oldString.endsWith("\n") && originalContent.contains(oldString + "\n");

        if (stripTrailingNewline) {
            return replaceAll
                    ? originalContent.replace(oldString + "\n", newString)
                    : originalContent.replaceFirst(Pattern.quote(oldString + "\n"), Matcher.quoteReplacement(newString));
        }
        return replaceAll
                ? originalContent.replace(oldString, newString)
                : originalContent.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
    }

    /**
     * Applies a list of edits to fileContents and returns the updated content.
     * Throws IllegalArgumentException if an edit cannot be applied.
     */
    public static String applyEditsToFile(String fileContents, List<FileEdit> edits) {
        String updatedFile = fileContents;
        List<String> appliedNewStrings = new ArrayList<>();

        // Special case: empty file with empty old/new strings
        if (fileContents.isEmpty()
                && edits.size() == 1
                && edits.get(0).getOldString().isEmpty()
                && edits.get(0).getNewString().isEmpty()) {
            return "";
        }

        for (FileEdit edit : edits) {
            String oldStringToCheck = edit.getOldString().replaceAll("\\n+$", "");

            for (String previousNewString : appliedNewStrings) {
                if (!oldStringToCheck.isEmpty() && previousNewString.contains(oldStringToCheck)) {
                    throw new IllegalArgumentException(
                            "Cannot edit file: old_string is a substring of a new_string from a previous edit.");
                }
            }

            String previousContent = updatedFile;
            if (edit.getOldString().isEmpty()) {
                updatedFile = edit.getNewString();
            } else {
                updatedFile = applyEditToFile(updatedFile, edit.getOldString(), edit.getNewString(), edit.isReplaceAll());
            }

            if (updatedFile.equals(previousContent)) {
                throw new IllegalArgumentException("String not found in file. Failed to apply edit.");
            }

            appliedNewStrings.add(edit.getNewString());
        }

        if (updatedFile.equals(fileContents)) {
            throw new IllegalArgumentException("Original and edited file match exactly. Failed to apply edit.");
        }

        return updatedFile;
    }

    // -----------------------------------------------------------------------
    // Snippet helpers
    // -----------------------------------------------------------------------

    /**
     * Gets a snippet from a file showing the context around a single edit.
     *
     * @return snippet text and the 1-based starting line number
     */
    public static SnippetResult getSnippet(
            String originalFile,
            String oldString,
            String newString,
            int contextLines) {

        String before = originalFile.contains(oldString)
                ? originalFile.substring(0, originalFile.indexOf(oldString))
                : "";
        int replacementLine = before.split("\\r?\\n", -1).length - 1;

        String editedFile = applyEditToFile(originalFile, oldString, newString, false);
        String[] newFileLines = editedFile.split("\\r?\\n", -1);

        int startLine = Math.max(0, replacementLine - contextLines);
        int endLine   = replacementLine + contextLines + newString.split("\\r?\\n", -1).length;

        List<String> snippetLines = new ArrayList<>();
        for (int i = startLine; i < Math.min(endLine, newFileLines.length); i++) {
            snippetLines.add(newFileLines[i]);
        }

        return new SnippetResult(String.join("\n", snippetLines), startLine + 1);
    }

    /**
     * Parses a list of {@link StructuredPatchHunk}s into {@link FileEdit}s.
     */
    public static List<FileEdit> getEditsForPatch(List<StructuredPatchHunk> patch) {
        List<FileEdit> result = new ArrayList<>();
        for (StructuredPatchHunk hunk : patch) {
            List<String> oldLines = new ArrayList<>();
            List<String> newLines = new ArrayList<>();

            for (String line : hunk.getLines()) {
                if (line.startsWith(" ")) {
                    // Context line — present in both versions
                    oldLines.add(line.substring(1));
                    newLines.add(line.substring(1));
                } else if (line.startsWith("-")) {
                    oldLines.add(line.substring(1));
                } else if (line.startsWith("+")) {
                    newLines.add(line.substring(1));
                }
            }

            result.add(FileEdit.builder()
                    .oldString(String.join("\n", oldLines))
                    .newString(String.join("\n", newLines))
                    .replaceAll(false)
                    .build());
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // De-sanitization
    // -----------------------------------------------------------------------

    /**
     * Result of de-sanitizing a match string.
     */
    public record DesanitizeResult(String result, List<ReplacementEntry> appliedReplacements) {}

    /**
     * A single from→to replacement that was applied.
     */
    public record ReplacementEntry(String from, String to) {}

    /**
     * Normalizes a match string by applying specific replacements (de-sanitization).
     */
    public static DesanitizeResult desanitizeMatchString(String matchString) {
        String current = matchString;
        List<ReplacementEntry> applied = new ArrayList<>();

        for (Map.Entry<String, String> entry : DESANITIZATIONS.entrySet()) {
            String before = current;
            current = current.replace(entry.getKey(), entry.getValue());
            if (!before.equals(current)) {
                applied.add(new ReplacementEntry(entry.getKey(), entry.getValue()));
            }
        }

        return new DesanitizeResult(current, applied);
    }

    // -----------------------------------------------------------------------
    // Normalize file-edit input
    // -----------------------------------------------------------------------

    /**
     * Normalizes the input for the FileEditTool.
     * If the string to replace is not found in the file, tries with a normalized version.
     */
    public static NormalizedEditInput normalizeFileEditInput(String filePath, List<EditInput> edits) {
        if (edits.isEmpty()) {
            return new NormalizedEditInput(filePath, edits);
        }

        boolean isMarkdown = filePath.toLowerCase().matches(".*\\.(md|mdx)$");

        try {
            String fileContent = Files.readString(Paths.get(filePath));

            List<EditInput> normalized = new ArrayList<>();
            for (EditInput edit : edits) {
                String normalizedNewString = isMarkdown
                        ? edit.getNewString()
                        : stripTrailingWhitespace(edit.getNewString());

                if (fileContent.contains(edit.getOldString())) {
                    normalized.add(EditInput.builder()
                            .oldString(edit.getOldString())
                            .newString(normalizedNewString)
                            .replaceAll(edit.isReplaceAll())
                            .build());
                    continue;
                }

                DesanitizeResult desanitized = desanitizeMatchString(edit.getOldString());
                if (fileContent.contains(desanitized.result())) {
                    String desanitizedNew = normalizedNewString;
                    for (ReplacementEntry rep : desanitized.appliedReplacements()) {
                        desanitizedNew = desanitizedNew.replace(rep.from(), rep.to());
                    }
                    normalized.add(EditInput.builder()
                            .oldString(desanitized.result())
                            .newString(desanitizedNew)
                            .replaceAll(edit.isReplaceAll())
                            .build());
                    continue;
                }

                normalized.add(EditInput.builder()
                        .oldString(edit.getOldString())
                        .newString(normalizedNewString)
                        .replaceAll(edit.isReplaceAll())
                        .build());
            }

            return new NormalizedEditInput(filePath, normalized);

        } catch (Exception e) {
            if (!(e instanceof java.nio.file.NoSuchFileException)) {
                log.error("Error reading file for edit normalization: {}", filePath, e);
            }
        }

        return new NormalizedEditInput(filePath, edits);
    }

    /**
     * Result of normalizing file edit input.
     */
    public record NormalizedEditInput(String filePath, List<EditInput> edits) {}

    // -----------------------------------------------------------------------
    // Equivalence checks
    // -----------------------------------------------------------------------

    /**
     * Compare two sets of edits by applying both to originalContent and comparing results.
     */
    public static boolean areFileEditsEquivalent(
            List<FileEdit> edits1,
            List<FileEdit> edits2,
            String originalContent) {

        // Fast path: literal equality
        if (edits1.size() == edits2.size()) {
            boolean identical = true;
            for (int i = 0; i < edits1.size(); i++) {
                FileEdit e1 = edits1.get(i);
                FileEdit e2 = edits2.get(i);
                if (!e1.getOldString().equals(e2.getOldString())
                        || !e1.getNewString().equals(e2.getNewString())
                        || e1.isReplaceAll() != e2.isReplaceAll()) {
                    identical = false;
                    break;
                }
            }
            if (identical) {
                return true;
            }
        }

        String result1 = null;
        String error1  = null;
        String result2 = null;
        String error2  = null;

        try {
            result1 = applyEditsToFile(originalContent, edits1);
        } catch (Exception e) {
            error1 = e.getMessage();
        }

        try {
            result2 = applyEditsToFile(originalContent, edits2);
        } catch (Exception e) {
            error2 = e.getMessage();
        }

        if (error1 != null && error2 != null) {
            return error1.equals(error2);
        }
        if (error1 != null || error2 != null) {
            return false;
        }

        return result1 != null && result1.equals(result2);
    }

    /**
     * Checks whether two file-edit inputs are equivalent (same file and same effective outcome).
     */
    public static boolean areFileEditsInputsEquivalent(
            String filePath1, List<FileEdit> edits1,
            String filePath2, List<FileEdit> edits2) {

        if (!filePath1.equals(filePath2)) {
            return false;
        }

        // Fast path: literal equality
        if (edits1.size() == edits2.size()) {
            boolean identical = true;
            for (int i = 0; i < edits1.size(); i++) {
                FileEdit e1 = edits1.get(i);
                FileEdit e2 = edits2.get(i);
                if (!e1.getOldString().equals(e2.getOldString())
                        || !e1.getNewString().equals(e2.getNewString())
                        || e1.isReplaceAll() != e2.isReplaceAll()) {
                    identical = false;
                    break;
                }
            }
            if (identical) {
                return true;
            }
        }

        String fileContent = "";
        try {
            fileContent = Files.readString(Paths.get(filePath1));
        } catch (java.nio.file.NoSuchFileException ignored) {
            // New file — compare against empty content
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return areFileEditsEquivalent(edits1, edits2, fileContent);
    }

    // -----------------------------------------------------------------------
    // Helper record
    // -----------------------------------------------------------------------

    /**
     * Result of getSnippet().
     */
    public record SnippetResult(String snippet, int startLine) {}
}
