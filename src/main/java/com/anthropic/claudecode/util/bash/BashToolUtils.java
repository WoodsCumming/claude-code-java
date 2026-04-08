package com.anthropic.claudecode.util.bash;

import com.anthropic.claudecode.util.OutputLimits;
import com.anthropic.claudecode.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for the Bash tool — output formatting, image detection,
 * data-URI parsing, and content summarisation.
 *
 * Translated from src/tools/BashTool/utils.ts
 *
 * Notes on omitted functions:
 *  - {@code resizeShellImageOutput()} — depends on image-resizer infrastructure;
 *    implement in a dedicated ImageService when that subsystem is available.
 *  - {@code resetCwdIfOutsideProject()} — depends on Shell/bootstrap state singletons;
 *    implement in the ShellService / BashCommandHelpersService layer.
 *  - {@code stdErrAppendShellResetMessage()} — depends on getOriginalCwd() bootstrap state;
 *    provided here as a simple helper that accepts the original cwd as a parameter.
 */
@Slf4j
public final class BashToolUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BashToolUtils.class);


    private static final Pattern IMAGE_OUTPUT_PATTERN =
            Pattern.compile("^data:image/[a-z0-9.+_-]+;base64,", Pattern.CASE_INSENSITIVE);

    private static final Pattern DATA_URI_PATTERN =
            Pattern.compile("^data:([^;]+);base64,(.+)$", Pattern.DOTALL);

    // -----------------------------------------------------------------------
    // stripEmptyLines
    // -----------------------------------------------------------------------

    /**
     * Strips leading and trailing lines that contain only whitespace/newlines.
     * Unlike {@code String.strip()}, this preserves whitespace within content lines
     * and only removes completely empty lines from the beginning and end.
     *
     * @param content the string to process
     * @return content with leading and trailing blank lines removed
     */
    public static String stripEmptyLines(String content) {
        if (content == null || content.isEmpty()) return "";

        String[] lines = content.split("\n", -1);

        int startIndex = 0;
        while (startIndex < lines.length && lines[startIndex].trim().isEmpty()) {
            startIndex++;
        }

        int endIndex = lines.length - 1;
        while (endIndex >= 0 && lines[endIndex].trim().isEmpty()) {
            endIndex--;
        }

        if (startIndex > endIndex) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i <= endIndex; i++) {
            if (i > startIndex) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // isImageOutput
    // -----------------------------------------------------------------------

    /**
     * Check if content is a base64-encoded image data URL.
     *
     * @param content the string to inspect
     * @return {@code true} if the string starts with a data-image URI prefix
     */
    public static boolean isImageOutput(String content) {
        if (content == null || content.isEmpty()) return false;
        return IMAGE_OUTPUT_PATTERN.matcher(content).find();
    }

    // -----------------------------------------------------------------------
    // parseDataUri
    // -----------------------------------------------------------------------

    /**
     * Result of parsing a data URI.
     *
     * @param mediaType the MIME type (e.g. {@code image/png})
     * @param data      the base64-encoded payload
     */
    public record DataUri(String mediaType, String data) {}

    /**
     * Parse a data-URI string into its media type and base64 payload.
     * The input is trimmed before matching.
     *
     * @param s the data URI string
     * @return a {@link DataUri} on success, or {@code null} if the string is not
     *         a valid data URI
     */
    public static DataUri parseDataUri(String s) {
        if (s == null) return null;
        Matcher matcher = DATA_URI_PATTERN.matcher(s.trim());
        if (!matcher.matches()) return null;
        String mediaType = matcher.group(1);
        String data = matcher.group(2);
        if (mediaType == null || mediaType.isEmpty() || data == null || data.isEmpty()) {
            return null;
        }
        return new DataUri(mediaType, data);
    }

    // -----------------------------------------------------------------------
    // buildImageToolResult  (simplified — no Anthropic SDK dependency)
    // -----------------------------------------------------------------------

    /**
     * Represents a minimal image tool-result block.
     *
     * @param toolUseId the originating tool-use ID
     * @param mediaType MIME type of the image
     * @param base64Data base64-encoded image data
     */
    public record ImageToolResult(String toolUseId, String mediaType, String base64Data) {}

    /**
     * Build an image tool-result block from shell stdout containing a data URI.
     * Returns {@code null} if parsing fails so callers can fall through to text
     * handling.
     *
     * @param stdout    shell standard output (expected to be a data URI)
     * @param toolUseId the originating tool-use ID
     * @return an {@link ImageToolResult}, or {@code null} if {@code stdout} is not
     *         a valid data URI
     */
    public static ImageToolResult buildImageToolResult(String stdout, String toolUseId) {
        DataUri parsed = parseDataUri(stdout);
        if (parsed == null) return null;
        return new ImageToolResult(toolUseId, parsed.mediaType(), parsed.data());
    }

    // -----------------------------------------------------------------------
    // formatOutput
    // -----------------------------------------------------------------------

    /**
     * Result of formatting command output for display.
     *
     * @param totalLines        total number of lines in the original output
     * @param truncatedContent  the (possibly truncated) content string
     * @param isImage           whether the content is an image data URI
     */
    public record FormattedOutput(int totalLines, String truncatedContent, boolean isImage) {}

    /**
     * Format command output, truncating if it exceeds the configured maximum
     * output length.
     *
     * @param content the raw command output
     * @return a {@link FormattedOutput} describing the formatted result
     */
    public static FormattedOutput formatOutput(String content) {
        if (content == null) content = "";

        boolean isImage = isImageOutput(content);
        if (isImage) {
            return new FormattedOutput(1, content, true);
        }

        int maxOutputLength = OutputLimits.getMaxOutputLength();
        int totalLines = StringUtils.countCharInString(content, '\n') + 1;

        if (content.length() <= maxOutputLength) {
            return new FormattedOutput(totalLines, content, false);
        }

        String truncatedPart = content.substring(0, maxOutputLength);
        int remainingLines = countCharFrom(content, '\n', maxOutputLength) + 1;
        String truncated = truncatedPart + "\n\n... [" + remainingLines + " lines truncated] ...";

        return new FormattedOutput(totalLines, truncated, false);
    }

    // -----------------------------------------------------------------------
    // stdErrAppendShellResetMessage
    // -----------------------------------------------------------------------

    /**
     * Appends a shell-reset notice to a stderr string.
     *
     * @param stderr      the original stderr output
     * @param originalCwd the path the shell was reset to
     * @return the concatenated message
     */
    public static String stdErrAppendShellResetMessage(String stderr, String originalCwd) {
        String trimmed = stderr == null ? "" : stderr.trim();
        return trimmed + "\nShell cwd was reset to " + originalCwd;
    }

    // -----------------------------------------------------------------------
    // createContentSummary
    // -----------------------------------------------------------------------

    /**
     * Represents a content block (text or image) in a tool result.
     * Used to avoid a hard dependency on the Anthropic SDK's ContentBlockParam.
     */
    public sealed interface ContentBlock permits ContentBlock.TextBlock, ContentBlock.ImageBlock {
        record TextBlock(String text) implements ContentBlock {}
        record ImageBlock() implements ContentBlock {}
    }

    /**
     * Creates a human-readable summary of structured content blocks.
     * Used to display MCP results with images and text in the UI.
     *
     * @param content list of content blocks to summarise
     * @return a human-readable summary string
     */
    public static String createContentSummary(List<ContentBlock> content) {
        if (content == null || content.isEmpty()) return "MCP Result: ";

        List<String> parts   = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        int textCount  = 0;
        int imageCount = 0;

        for (ContentBlock block : content) {
            switch (block) {
                case ContentBlock.ImageBlock ignored -> imageCount++;
                case ContentBlock.TextBlock tb -> {
                    textCount++;
                    String preview = tb.text().substring(0, Math.min(200, tb.text().length()));
                    parts.add(preview + (tb.text().length() > 200 ? "..." : ""));
                }
            }
        }

        if (imageCount > 0) {
            summary.add("[" + imageCount + " " + StringUtils.plural(imageCount, "image") + "]");
        }
        if (textCount > 0) {
            summary.add("[" + textCount + " text " + StringUtils.plural(textCount, "block") + "]");
        }

        String summaryStr = String.join(", ", summary);
        String partsStr = parts.isEmpty() ? "" : "\n\n" + String.join("\n\n", parts);

        return "MCP Result: " + summaryStr + partsStr;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Count occurrences of {@code ch} in {@code str} starting from {@code fromIndex}.
     */
    private static int countCharFrom(String str, char ch, int fromIndex) {
        int count = 0;
        for (int i = fromIndex; i < str.length(); i++) {
            if (str.charAt(i) == ch) count++;
        }
        return count;
    }

    private BashToolUtils() {}
}
