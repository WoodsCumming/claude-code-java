package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool result storage service for persisting large tool results to disk.
 * Translated from src/utils/toolResultStorage.ts
 *
 * When tool results exceed a size threshold, they are persisted to disk and
 * the model receives a reference to the file with a preview instead of the
 * full content. This prevents very large tool results from consuming token budget.
 */
@Slf4j
@Service
public class ToolResultStorageService {



    // =========================================================================
    // Constants — mirror constants/toolLimits.ts
    // =========================================================================

    /** Default max result size in characters (50k). */
    public static final int DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000;

    /** Hard upper limit on a single tool result payload (bytes). */
    public static final int MAX_TOOL_RESULT_BYTES = 1_000_000;

    /** Combined per-message aggregate limit for all tool results (chars). */
    public static final int MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200_000;

    /** Approximate bytes per API token for size estimates. */
    public static final int BYTES_PER_TOKEN = 4;

    /** Subdirectory name for tool results within a session. */
    public static final String TOOL_RESULTS_SUBDIR = "tool-results";

    /** XML tag wrapping persisted output reference messages. */
    public static final String PERSISTED_OUTPUT_TAG = "<persisted-output>";

    /** Closing XML tag for persisted output messages. */
    public static final String PERSISTED_OUTPUT_CLOSING_TAG = "</persisted-output>";

    /** Placeholder shown when old tool result content was cleared without persisting. */
    public static final String TOOL_RESULT_CLEARED_MESSAGE = "[Old tool result content cleared]";

    /** Preview size in bytes included in the reference message. */
    public static final int PREVIEW_SIZE_BYTES = 2000;

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final SessionService sessionService;

    public ToolResultStorageService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    // =========================================================================
    // Directory helpers
    // =========================================================================

    /**
     * Get the tool results directory for the current session.
     * Translated from getToolResultsDir() in toolResultStorage.ts
     */
    public String getToolResultsDir() {
        return sessionService.getProjectDir() + "/"
            + sessionService.getCurrentSessionId() + "/" + TOOL_RESULTS_SUBDIR;
    }

    /**
     * Get the file path where a tool result would be persisted.
     * Translated from getToolResultPath() in toolResultStorage.ts
     */
    public String getToolResultPath(String id, boolean isJson) {
        String ext = isJson ? "json" : "txt";
        return getToolResultsDir() + "/" + id + "." + ext;
    }

    /**
     * Ensure the session-specific tool results directory exists.
     * Translated from ensureToolResultsDir() in toolResultStorage.ts
     */
    public void ensureToolResultsDir() throws IOException {
        Files.createDirectories(Path.of(getToolResultsDir()));
    }

    // =========================================================================
    // Threshold resolution
    // =========================================================================

    /**
     * Resolve the effective persistence threshold for a tool.
     * Infinity (Integer.MAX_VALUE) means hard opt-out — never persist.
     * Translated from getPersistenceThreshold() in toolResultStorage.ts
     */
    public int getPersistenceThreshold(String toolName, int declaredMaxResultSizeChars) {
        if (declaredMaxResultSizeChars == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.min(declaredMaxResultSizeChars, DEFAULT_MAX_RESULT_SIZE_CHARS);
    }

    // =========================================================================
    // Preview generation
    // =========================================================================

    /**
     * Generate a preview of content, truncating at a newline boundary when possible.
     * Translated from generatePreview() in toolResultStorage.ts
     */
    public PreviewResult generatePreview(String content, int maxBytes) {
        if (content.length() <= maxBytes) {
            return new PreviewResult(content, false);
        }

        String truncated = content.substring(0, maxBytes);
        int lastNewline = truncated.lastIndexOf('\n');

        // If we found a newline reasonably close to the limit, use it.
        int cutPoint = (lastNewline > maxBytes * 0.5) ? lastNewline : maxBytes;
        return new PreviewResult(content.substring(0, cutPoint), true);
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Persist a tool result string to disk and return metadata.
     * Translated from persistToolResult() in toolResultStorage.ts
     *
     * Uses CREATE_NEW so a pre-existing file (already persisted on a prior
     * turn) is left intact and falls through to preview generation.
     */
    public CompletableFuture<Object> persistToolResult(String content, String toolUseId) {
        return CompletableFuture.supplyAsync(() -> {
            boolean isJson = content.trim().startsWith("[") || content.trim().startsWith("{");
            Path filepath = Path.of(getToolResultPath(toolUseId, isJson));

            try {
                ensureToolResultsDir();
                // CREATE_NEW: skip if already persisted (idempotent across turns).
                try {
                    Files.writeString(filepath, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    log.debug("Persisted tool result to {} ({} chars)", filepath, content.length());
                } catch (FileAlreadyExistsException e) {
                    // Already persisted on a prior turn — fall through to preview.
                }

                PreviewResult preview = generatePreview(content, PREVIEW_SIZE_BYTES);
                return new PersistedToolResult(
                    filepath.toString(),
                    content.length(),
                    isJson,
                    preview.preview(),
                    preview.hasMore()
                );
            } catch (IOException e) {
                log.warn("Failed to persist tool result {}: {}", toolUseId, e.getMessage());
                return new PersistToolResultError(getFileSystemErrorMessage(e));
            }
        });
    }

    /**
     * Build a message for large tool results with preview.
     * Translated from buildLargeToolResultMessage() in toolResultStorage.ts
     */
    public String buildLargeToolResultMessage(PersistedToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(PERSISTED_OUTPUT_TAG).append("\n");
        sb.append("Output too large (").append(formatFileSize(result.originalSize()))
            .append("). Full output saved to: ").append(result.filepath()).append("\n\n");
        sb.append("Preview (first ").append(formatFileSize(PREVIEW_SIZE_BYTES)).append("):\n");
        sb.append(result.preview());
        sb.append(result.hasMore() ? "\n...\n" : "\n");
        sb.append(PERSISTED_OUTPUT_CLOSING_TAG);
        return sb.toString();
    }

    /**
     * Check if a tool result's content is empty or effectively empty.
     * Translated from isToolResultContentEmpty() in toolResultStorage.ts
     */
    public boolean isToolResultContentEmpty(String content) {
        return content == null || content.isBlank();
    }

    // =========================================================================
    // Content replacement state
    // =========================================================================

    /**
     * Per-conversation-thread state for the aggregate tool result budget.
     * Tracks which tool_use_ids have been seen and which have replacements,
     * ensuring prompt-cache stability across turns.
     *
     * Translated from ContentReplacementState in toolResultStorage.ts
     */
    public static class ContentReplacementState {
        /** All tool_use_ids that have passed through the budget check. */
        private final Set<String> seenIds = new HashSet<>();
        private final Map<String, String> replacements = new HashMap<>();

        public ContentReplacementState() {}
        public Set<String> getSeenIds() { return seenIds; }
        public Map<String, String> getReplacements() { return replacements; }
    }

    /**
     * Create a fresh ContentReplacementState for a new conversation thread.
     * Translated from createContentReplacementState() in toolResultStorage.ts
     */
    public ContentReplacementState createContentReplacementState() {
        return new ContentReplacementState();
    }

    /**
     * Clone replacement state for a cache-sharing fork (e.g. agentSummary).
     * Mutating the clone does not affect the source.
     * Translated from cloneContentReplacementState() in toolResultStorage.ts
     */
    public ContentReplacementState cloneContentReplacementState(ContentReplacementState source) {
        ContentReplacementState clone = new ContentReplacementState();
        clone.getSeenIds().addAll(source.getSeenIds());
        clone.getReplacements().putAll(source.getReplacements());
        return clone;
    }

    // =========================================================================
    // Serializable replacement record
    // =========================================================================

    /**
     * Serializable record of one content-replacement decision.
     * Written to the transcript as a ContentReplacementEntry so decisions survive resume.
     * Translated from ContentReplacementRecord in toolResultStorage.ts
     */
    public record ContentReplacementRecord(
        String kind,       // always "tool-result"
        String toolUseId,
        String replacement // the exact string the model saw
    ) {}

    // =========================================================================
    // Result types (sealed interface hierarchy)
    // =========================================================================

    /** Successful persistence result. */
    public record PersistedToolResult(
        String filepath,
        int originalSize,
        boolean isJson,
        String preview,
        boolean hasMore
    ) {}

    /** Error result when persistence fails. */
    public record PersistToolResultError(String error) {}

    /** Preview result from generatePreview. */
    public record PreviewResult(String preview, boolean hasMore) {}

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Check whether a persist result is an error. */
    public boolean isPersistError(Object result) {
        return result instanceof PersistToolResultError;
    }

    /**
     * Format a byte count as a human-readable size string.
     * Mirrors formatFileSize() from utils/format.ts
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * Translate a filesystem IOException to a human-readable message.
     * Translated from getFileSystemErrorMessage() in toolResultStorage.ts
     */
    private String getFileSystemErrorMessage(IOException error) {
        String msg = error.getMessage();
        if (msg == null) return "Unknown filesystem error";
        if (msg.contains("No such file or directory")) return "Directory not found";
        if (msg.contains("Permission denied")) return "Permission denied";
        if (msg.contains("No space left on device")) return "No space left on device";
        if (msg.contains("Read-only file system")) return "Read-only file system";
        if (msg.contains("Too many open files")) return "Too many open files";
        return msg;
    }
}
