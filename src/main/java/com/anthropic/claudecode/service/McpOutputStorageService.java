package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * MCP output storage service for persisting large tool outputs to disk.
 * Translated from src/utils/mcpOutputStorage.ts
 *
 * When a tool result exceeds the MCP token limit the output is written to a
 * file under the tool-results directory so Claude can read it in chunks.
 */
@Slf4j
@Service
public class McpOutputStorageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpOutputStorageService.class);


    // =========================================================================
    // MCP result types
    // =========================================================================

    public enum McpResultType {
        TOOL_RESULT,
        STRUCTURED_CONTENT,
        CONTENT_ARRAY
    }

    // =========================================================================
    // Persist binary result
    // =========================================================================

    /**
     * Result of persisting binary content to disk.
     */
    public sealed interface PersistBinaryResult permits
            McpOutputStorageService.PersistBinaryResult.Success,
            McpOutputStorageService.PersistBinaryResult.Failure {

        record Success(String filepath, long size, String ext) implements PersistBinaryResult {}
        record Failure(String error) implements PersistBinaryResult {}
    }

    // =========================================================================
    // Format description
    // =========================================================================

    /**
     * Generate a format description for a given MCP result type.
     * Translated from getFormatDescription() in mcpOutputStorage.ts
     */
    public String getFormatDescription(McpResultType type, Object schema) {
        return switch (type) {
            case TOOL_RESULT -> "Plain text";
            case STRUCTURED_CONTENT -> schema != null
                    ? "JSON with schema: " + schema
                    : "JSON";
            case CONTENT_ARRAY -> schema != null
                    ? "JSON array with schema: " + schema
                    : "JSON array";
        };
    }

    // =========================================================================
    // Large output instructions
    // =========================================================================

    /**
     * Generate instruction text for Claude to read from a saved output file.
     * Translated from getLargeOutputInstructions() in mcpOutputStorage.ts
     *
     * @param rawOutputPath   path to the saved output file
     * @param contentLength   length of the content in characters
     * @param formatDescription description of the content format
     * @param maxReadLength   optional max chars for read tool (may be null)
     * @return instruction text to include in the tool result
     */
    public String getLargeOutputInstructions(String rawOutputPath,
                                              int contentLength,
                                              String formatDescription,
                                              Integer maxReadLength) {
        String baseInstructions =
                "Error: result (" + String.format("%,d", contentLength) +
                " characters) exceeds maximum allowed tokens. Output has been saved to " +
                rawOutputPath + ".\n" +
                "Format: " + formatDescription + "\n" +
                "Use offset and limit parameters to read specific portions of the file, " +
                "search within it for specific content, and jq to make structured queries.\n" +
                "REQUIREMENTS FOR SUMMARIZATION/ANALYSIS/REVIEW:\n" +
                "- You MUST read the content from the file at " + rawOutputPath +
                " in sequential chunks until 100% of the content has been read.\n";

        String truncationWarning = maxReadLength != null
                ? "- If you receive truncation warnings when reading the file " +
                  "(\"[N lines truncated]\"), reduce the chunk size until you have read " +
                  "100% of the content without truncation ***DO NOT PROCEED UNTIL YOU " +
                  "HAVE DONE THIS***. Bash output is limited to " +
                  String.format("%,d", maxReadLength) + " chars.\n"
                : "- If you receive truncation warnings when reading the file, reduce " +
                  "the chunk size until you have read 100% of the content without truncation.\n";

        String completionRequirement =
                "- Before producing ANY summary or analysis, you MUST explicitly describe " +
                "what portion of the content you have read. ***If you did not read the " +
                "entire content, you MUST explicitly state this.***\n";

        return baseInstructions + truncationWarning + completionRequirement;
    }

    // =========================================================================
    // MIME type → file extension
    // =========================================================================

    /**
     * Map a MIME type to a file extension.
     * Translated from extensionForMimeType() in mcpOutputStorage.ts
     */
    public String extensionForMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) return "bin";
        String mt = mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return switch (mt) {
            case "application/pdf"  -> "pdf";
            case "application/json" -> "json";
            case "text/csv"         -> "csv";
            case "text/plain"       -> "txt";
            case "text/html"        -> "html";
            case "text/markdown"    -> "md";
            case "application/zip"  -> "zip";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"       -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "application/msword"      -> "doc";
            case "application/vnd.ms-excel" -> "xls";
            case "audio/mpeg" -> "mp3";
            case "audio/wav"  -> "wav";
            case "audio/ogg"  -> "ogg";
            case "video/mp4"  -> "mp4";
            case "video/webm" -> "webm";
            case "image/png"  -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif"  -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> "bin";
        };
    }

    // =========================================================================
    // Binary content type detection
    // =========================================================================

    /**
     * Heuristic for whether a content-type indicates binary content.
     * Translated from isBinaryContentType() in mcpOutputStorage.ts
     */
    public boolean isBinaryContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return false;
        String mt = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (mt.startsWith("text/")) return false;
        if (mt.endsWith("+json") || mt.equals("application/json")) return false;
        if (mt.endsWith("+xml") || mt.equals("application/xml")) return false;
        if (mt.startsWith("application/javascript")) return false;
        if (mt.equals("application/x-www-form-urlencoded")) return false;
        return true;
    }

    // =========================================================================
    // Persist binary content
    // =========================================================================

    /**
     * Write raw binary bytes to the tool-results directory with a mime-derived extension.
     * Translated from persistBinaryContent() in mcpOutputStorage.ts
     *
     * @param bytes     raw binary data
     * @param mimeType  MIME type (may be null)
     * @param persistId identifier used to construct the filename
     * @param toolResultsDir directory where files are written
     * @return a CompletableFuture with a PersistBinaryResult
     */
    public CompletableFuture<PersistBinaryResult> persistBinaryContent(
            byte[] bytes,
            String mimeType,
            String persistId,
            String toolResultsDir) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(Paths.get(toolResultsDir));
                String ext = extensionForMimeType(mimeType);
                Path filepath = Paths.get(toolResultsDir, persistId + "." + ext);
                Files.write(filepath, bytes);
                log.debug("Persisted binary content: {} ({} bytes, {})", filepath, bytes.length, ext);
                return (PersistBinaryResult) new PersistBinaryResult.Success(
                        filepath.toString(), bytes.length, ext);
            } catch (IOException e) {
                log.error("Failed to persist binary content: {}", e.getMessage());
                return new PersistBinaryResult.Failure(e.getMessage());
            }
        });
    }

    // =========================================================================
    // Binary blob saved message
    // =========================================================================

    /**
     * Build a short message telling Claude where binary content was saved.
     * Translated from getBinaryBlobSavedMessage() in mcpOutputStorage.ts
     */
    public String getBinaryBlobSavedMessage(String filepath,
                                             String mimeType,
                                             long size,
                                             String sourceDescription) {
        String mt = mimeType != null ? mimeType : "unknown type";
        return sourceDescription + "Binary content (" + mt + ", " +
               formatFileSize(size) + ") saved to " + filepath;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
