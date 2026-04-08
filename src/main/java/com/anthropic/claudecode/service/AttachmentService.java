package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Attachment types and service for building message context.
 * Translated from src/utils/attachments.ts
 *
 * <p>Defines the full set of attachment types that can be added to messages and
 * provides utilities for reading image data from clipboard and file paths.</p>
 */
@Slf4j
@Service
public class AttachmentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttachmentService.class);


    // =========================================================================
    // Constants
    // =========================================================================

    /** Threshold in characters for when to consider text a "large paste". */
    public static final int PASTE_THRESHOLD = 800;

    /** Regex pattern to match supported image file extensions. */
    public static final Pattern IMAGE_EXTENSION_REGEX =
            Pattern.compile("\\.(png|jpe?g|gif|webp)$", Pattern.CASE_INSENSITIVE);

    /** Max lines to read for memory files. */
    public static final int MAX_MEMORY_LINES = 200;

    /** Max bytes to read for a single memory file (4 KB). */
    public static final int MAX_MEMORY_BYTES = 4096;

    /** TODO reminder config: turns since last write / between reminders. */
    public static final int TODO_REMINDER_TURNS_SINCE_WRITE = 10;
    public static final int TODO_REMINDER_TURNS_BETWEEN = 10;

    /** Plan-mode attachment config. */
    public static final int PLAN_MODE_TURNS_BETWEEN = 5;
    public static final int PLAN_MODE_FULL_EVERY_N = 5;

    /** Auto-mode attachment config. */
    public static final int AUTO_MODE_TURNS_BETWEEN = 5;
    public static final int AUTO_MODE_FULL_EVERY_N = 5;

    /** Relevant-memories session byte budget (~3 full injections). */
    public static final int RELEVANT_MEMORIES_MAX_SESSION_BYTES = 60 * 1024;

    /** Verify-plan reminder: turns between reminders. */
    public static final int VERIFY_PLAN_TURNS_BETWEEN = 10;

    // =========================================================================
    // Attachment sealed hierarchy
    // =========================================================================

    /**
     * Sealed base interface for all attachment types.
     * Translated from the Attachment union type in attachments.ts
     */
    public sealed interface Attachment permits
            Attachment.FileAttachment,
            Attachment.CompactFileReferenceAttachment,
            Attachment.PdfReferenceAttachment,
            Attachment.AlreadyReadFileAttachment,
            Attachment.EditedTextFileAttachment,
            Attachment.EditedImageFileAttachment,
            Attachment.DirectoryAttachment,
            Attachment.SelectedLinesInIdeAttachment,
            Attachment.OpenedFileInIdeAttachment,
            Attachment.TodoReminderAttachment,
            Attachment.TaskReminderAttachment,
            Attachment.NestedMemoryAttachment,
            Attachment.RelevantMemoriesAttachment,
            Attachment.DynamicSkillAttachment,
            Attachment.SkillListingAttachment,
            Attachment.SkillDiscoveryAttachment,
            Attachment.QueuedCommandAttachment,
            Attachment.OutputStyleAttachment,
            Attachment.DiagnosticsAttachment,
            Attachment.PlanModeAttachment,
            Attachment.PlanModeReentryAttachment,
            Attachment.PlanModeExitAttachment,
            Attachment.AutoModeAttachment,
            Attachment.AutoModeExitAttachment,
            Attachment.HookAttachment,
            Attachment.AsyncHookResponseAttachment,
            Attachment.AgentMentionAttachment,
            Attachment.ImagePasteAttachment,
            Attachment.TextPasteAttachment,
            Attachment.McpResourceAttachment {

        /**
         * File at-mentioned by the user.
         * Translated from FileAttachment in attachments.ts
         */
        record FileAttachment(
                String filename,
                Object content,
                boolean truncated,
                String displayPath
        ) implements Attachment {}

        /**
         * Compact file reference (a file that was already read and compacted).
         * Translated from CompactFileReferenceAttachment in attachments.ts
         */
        record CompactFileReferenceAttachment(
                String filename,
                String displayPath
        ) implements Attachment {}

        /**
         * PDF file reference (inline or reference depending on page count).
         * Translated from PDFReferenceAttachment in attachments.ts
         */
        record PdfReferenceAttachment(
                String filename,
                int pageCount,
                long fileSize,
                String displayPath
        ) implements Attachment {}

        /**
         * File that was already read in a prior tool use.
         * Translated from AlreadyReadFileAttachment in attachments.ts
         */
        record AlreadyReadFileAttachment(
                String filename,
                Object content,
                boolean truncated,
                String displayPath
        ) implements Attachment {}

        /**
         * A text file that was edited (shows a diff snippet).
         * Translated from the edited_text_file variant in attachments.ts
         */
        record EditedTextFileAttachment(
                String filename,
                String snippet
        ) implements Attachment {}

        /**
         * An image file that was edited.
         * Translated from the edited_image_file variant in attachments.ts
         */
        record EditedImageFileAttachment(
                String filename,
                Object content
        ) implements Attachment {}

        /**
         * A directory listing.
         * Translated from the directory variant in attachments.ts
         */
        record DirectoryAttachment(
                String path,
                String content,
                String displayPath
        ) implements Attachment {}

        /**
         * Lines selected in an IDE editor.
         * Translated from the selected_lines_in_ide variant in attachments.ts
         */
        record SelectedLinesInIdeAttachment(
                String ideName,
                int lineStart,
                int lineEnd,
                String filename,
                String content,
                String displayPath
        ) implements Attachment {}

        /**
         * A file opened in an IDE.
         * Translated from the opened_file_in_ide variant in attachments.ts
         */
        record OpenedFileInIdeAttachment(
                String filename
        ) implements Attachment {}

        /**
         * A todo list reminder injected into the context.
         * Translated from the todo_reminder variant in attachments.ts
         */
        record TodoReminderAttachment(
                Object content,
                int itemCount
        ) implements Attachment {}

        /**
         * A task list reminder injected into the context.
         * Translated from the task_reminder variant in attachments.ts
         */
        record TaskReminderAttachment(
                List<Object> content,
                int itemCount
        ) implements Attachment {}

        /**
         * A nested CLAUDE.md memory file.
         * Translated from the nested_memory variant in attachments.ts
         */
        record NestedMemoryAttachment(
                String path,
                Object content,
                String displayPath
        ) implements Attachment {}

        /**
         * Relevant memories surfaced by the memdir system.
         * Translated from the relevant_memories variant in attachments.ts
         */
        record RelevantMemoriesAttachment(
                List<MemoryEntry> memories
        ) implements Attachment {
            public record MemoryEntry(
                    String path,
                    String content,
                    long mtimeMs,
                    String header,
                    Integer limit
            ) {}
        }

        /**
         * A dynamic skill (skill directory + discovered skill names).
         * Translated from the dynamic_skill variant in attachments.ts
         */
        record DynamicSkillAttachment(
                String skillDir,
                List<String> skillNames,
                String displayPath
        ) implements Attachment {}

        /**
         * A listing of available skills.
         * Translated from the skill_listing variant in attachments.ts
         */
        record SkillListingAttachment(
                String content,
                int skillCount,
                boolean isInitial
        ) implements Attachment {}

        /**
         * Skills discovered by the skill-search subsystem.
         * Translated from the skill_discovery variant in attachments.ts
         */
        record SkillDiscoveryAttachment(
                List<SkillEntry> skills,
                String signal,
                String source
        ) implements Attachment {
            public record SkillEntry(
                    String name,
                    String description,
                    String shortId
            ) {}
        }

        /**
         * A queued command (user message or system notification) drained mid-turn.
         * Translated from the queued_command variant in attachments.ts
         */
        record QueuedCommandAttachment(
                Object prompt,
                String sourceUuid,
                List<Integer> imagePasteIds,
                String commandMode,
                String origin,
                Boolean isMeta
        ) implements Attachment {}

        /**
         * Output style override injected into context.
         * Translated from the output_style variant in attachments.ts
         */
        record OutputStyleAttachment(
                String style
        ) implements Attachment {}

        /**
         * Diagnostic files (LSP, etc.) injected into context.
         * Translated from the diagnostics variant in attachments.ts
         */
        record DiagnosticsAttachment(
                List<Object> files,
                boolean isNew
        ) implements Attachment {}

        /**
         * Plan-mode context reminder.
         * Translated from the plan_mode variant in attachments.ts
         */
        record PlanModeAttachment(
                String reminderType,
                boolean isSubAgent,
                String planFilePath,
                boolean planExists
        ) implements Attachment {}

        /**
         * Re-entry into plan mode.
         * Translated from the plan_mode_reentry variant in attachments.ts
         */
        record PlanModeReentryAttachment(
                String planFilePath
        ) implements Attachment {}

        /**
         * Exit from plan mode.
         * Translated from the plan_mode_exit variant in attachments.ts
         */
        record PlanModeExitAttachment(
                String planFilePath,
                boolean planExists
        ) implements Attachment {}

        /**
         * Auto (agentic) mode reminder.
         * Translated from the auto_mode variant in attachments.ts
         */
        record AutoModeAttachment(
                String reminderType,
                boolean isSubAgent
        ) implements Attachment {}

        /**
         * Exit from auto mode.
         * Translated from the auto_mode_exit variant in attachments.ts
         */
        record AutoModeExitAttachment(
                String reason
        ) implements Attachment {}

        /**
         * Hook-related attachment (blocking error, success, non-blocking error, etc.).
         * Translated from HookAttachment in attachments.ts
         */
        record HookAttachment(
                String hookType,
                String hookName,
                String toolUseId,
                String hookEvent,
                String content,
                String stdout,
                String stderr,
                Integer exitCode,
                String command,
                Long durationMs,
                Object blockingError
        ) implements Attachment {}

        /**
         * Response from an async hook.
         * Translated from AsyncHookResponseAttachment in attachments.ts
         */
        record AsyncHookResponseAttachment(
                String processId,
                String hookName,
                String hookEvent,
                String toolName,
                Object response,
                String stdout,
                String stderr,
                Integer exitCode
        ) implements Attachment {}

        /**
         * An agent at-mentioned in the prompt.
         * Translated from AgentMentionAttachment in attachments.ts
         */
        record AgentMentionAttachment(
                String agentType
        ) implements Attachment {}

        /**
         * An image pasted from the clipboard or provided as a file.
         * Translated from image paste handling in attachments.ts
         */
        record ImagePasteAttachment(
                String base64,
                String mediaType,
                int pasteId
        ) implements Attachment {}

        /**
         * Large text pasted by the user (above the PASTE_THRESHOLD).
         * Translated from text paste handling in attachments.ts
         */
        record TextPasteAttachment(
                String content,
                int pasteId
        ) implements Attachment {}

        /**
         * An MCP resource result injected as context.
         * Translated from the mcp_resource variant in attachments.ts
         */
        record McpResourceAttachment(
                String serverName,
                String resourceUri,
                Object result
        ) implements Attachment {}
    }

    // =========================================================================
    // Image paste helper types
    // =========================================================================

    /**
     * An image together with its dimensions.
     * Translated from ImageWithDimensions in attachments.ts
     */
    public record ImageWithDimensions(
            String base64,
            String mediaType,
            ImageDimensions dimensions
    ) {}

    /**
     * Image dimension metadata.
     * Translated from ImageDimensions in imageResizer.ts
     */
    public record ImageDimensions(
            int originalWidth,
            int originalHeight,
            int displayWidth,
            int displayHeight
    ) {}

    /**
     * An image together with dimensions and the resolved file path.
     * Translated from ImageWithDimensionsAndPath in attachments.ts
     */
    public record ImageWithDimensionsAndPath(
            String path,
            String base64,
            String mediaType,
            ImageDimensions dimensions
    ) {}

    // =========================================================================
    // Clipboard helpers
    // =========================================================================

    /**
     * Check if clipboard contains an image without retrieving it (macOS only).
     * Translated from hasImageInClipboard() in imagePaste.ts
     */
    public CompletableFuture<Boolean> hasImageInClipboard() {
        return CompletableFuture.supplyAsync(() -> {
            if (!isMacOS()) return false;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "osascript", "-e", "the clipboard as \u00abclass PNGf\u00bb");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int code = p.waitFor();
                return code == 0;
            } catch (Exception e) {
                log.debug("hasImageInClipboard error: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Retrieve an image from the system clipboard.
     * Translated from getImageFromClipboard() in imagePaste.ts
     */
    public CompletableFuture<ImageWithDimensions> getImageFromClipboard() {
        return CompletableFuture.supplyAsync(() -> {
            String platform = detectPlatform();
            String tmpPath = getTempScreenshotPath(platform);

            try {
                String checkCmd = getCheckImageCommand(platform, tmpPath);
                if (!runShellCommand(checkCmd)) return null;

                String saveCmd = getSaveImageCommand(platform, tmpPath);
                if (!runShellCommand(saveCmd)) return null;

                byte[] bytes = Files.readAllBytes(Paths.get(tmpPath));
                if (bytes.length == 0) return null;

                String base64 = Base64.getEncoder().encodeToString(bytes);
                String mediaType = detectMediaType(bytes);

                try { Files.deleteIfExists(Paths.get(tmpPath)); } catch (Exception ignored) {}

                return new ImageWithDimensions(base64, mediaType, null);
            } catch (Exception e) {
                log.debug("getImageFromClipboard error: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * Get a file path string from the clipboard (for drag-and-drop paths).
     * Translated from getImagePathFromClipboard() in imagePaste.ts
     */
    public CompletableFuture<String> getImagePathFromClipboard() {
        return CompletableFuture.supplyAsync(() -> {
            String platform = detectPlatform();
            String getPathCmd = getPathCommand(platform);
            try {
                Process p = new ProcessBuilder("sh", "-c", getPathCmd).start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                int code = p.waitFor();
                return (code == 0 && !out.isEmpty()) ? out : null;
            } catch (Exception e) {
                log.debug("getImagePathFromClipboard error: {}", e.getMessage());
                return null;
            }
        });
    }

    // =========================================================================
    // Path helpers
    // =========================================================================

    /**
     * Check if a given text represents an image file path.
     * Translated from isImageFilePath() in imagePaste.ts
     */
    public boolean isImageFilePath(String text) {
        if (text == null) return false;
        String cleaned = removeOuterQuotes(text.trim());
        String unescaped = stripBackslashEscapes(cleaned);
        return IMAGE_EXTENSION_REGEX.matcher(unescaped).find();
    }

    /**
     * Clean and normalize a string that might be an image file path.
     * Returns the cleaned path or {@code null} if it is not an image path.
     * Translated from asImageFilePath() in imagePaste.ts
     */
    public String asImageFilePath(String text) {
        if (text == null) return null;
        String cleaned = removeOuterQuotes(text.trim());
        String unescaped = stripBackslashEscapes(cleaned);
        return IMAGE_EXTENSION_REGEX.matcher(unescaped).find() ? unescaped : null;
    }

    /**
     * Try to find and read an image file, falling back to clipboard path lookup.
     * Translated from tryReadImageFromPath() in imagePaste.ts
     */
    public CompletableFuture<ImageWithDimensionsAndPath> tryReadImageFromPath(String text) {
        return CompletableFuture.supplyAsync(() -> {
            String cleanedPath = asImageFilePath(text);
            if (cleanedPath == null) return null;

            byte[] bytes = null;
            try {
                Path p = Paths.get(cleanedPath);
                if (p.isAbsolute()) {
                    bytes = Files.readAllBytes(p);
                } else {
                    String clipPath = getImagePathFromClipboard().join();
                    if (clipPath != null && cleanedPath.equals(Paths.get(clipPath).getFileName().toString())) {
                        bytes = Files.readAllBytes(Paths.get(clipPath));
                        cleanedPath = clipPath;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read image from path '{}': {}", cleanedPath, e.getMessage());
                return null;
            }

            if (bytes == null || bytes.length == 0) return null;

            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mediaType = detectMediaType(bytes);
            return new ImageWithDimensionsAndPath(cleanedPath, base64, mediaType, null);
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String removeOuterQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    private String stripBackslashEscapes(String path) {
        if (isWindows()) return path;
        return path.replaceAll("\\\\(.)", "$1");
    }

    private boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return "darwin";
        if (os.contains("win")) return "win32";
        return "linux";
    }

    private String getTempScreenshotPath(String platform) {
        String tmpDir = System.getenv("CLAUDE_CODE_TMPDIR");
        if (tmpDir == null) {
            tmpDir = "win32".equals(platform)
                    ? (System.getenv("TEMP") != null ? System.getenv("TEMP") : "C:\\Temp")
                    : "/tmp";
        }
        return tmpDir + (isWindows() ? "\\" : "/") + "claude_cli_latest_screenshot.png";
    }

    private String getCheckImageCommand(String platform, String tmpPath) {
        return switch (platform) {
            case "darwin" -> "osascript -e 'the clipboard as \u00abclass PNGf\u00bb'";
            case "win32" -> "powershell -NoProfile -Command \"(Get-Clipboard -Format Image) -ne $null\"";
            default -> "xclip -selection clipboard -t TARGETS -o 2>/dev/null | grep -E 'image/(png|jpeg|jpg|gif|webp|bmp)' "
                    + "|| wl-paste -l 2>/dev/null | grep -E 'image/(png|jpeg|jpg|gif|webp|bmp)'";
        };
    }

    private String getSaveImageCommand(String platform, String tmpPath) {
        return switch (platform) {
            case "darwin" -> "osascript -e 'set png_data to (the clipboard as \u00abclass PNGf\u00bb)' "
                    + "-e 'set fp to open for access POSIX file \"" + tmpPath + "\" with write permission' "
                    + "-e 'write png_data to fp' -e 'close access fp'";
            case "win32" -> "powershell -NoProfile -Command \"$img = Get-Clipboard -Format Image; "
                    + "if ($img) { $img.Save('" + tmpPath.replace("\\", "\\\\")
                    + "', [System.Drawing.Imaging.ImageFormat]::Png) }\"";
            default -> "xclip -selection clipboard -t image/png -o > \"" + tmpPath
                    + "\" 2>/dev/null || wl-paste --type image/png > \"" + tmpPath + "\"";
        };
    }

    private String getPathCommand(String platform) {
        return switch (platform) {
            case "darwin" -> "osascript -e 'get POSIX path of (the clipboard as \u00abclass furl\u00bb)'";
            case "win32" -> "powershell -NoProfile -Command \"Get-Clipboard\"";
            default -> "xclip -selection clipboard -t text/plain -o 2>/dev/null || wl-paste 2>/dev/null";
        };
    }

    private boolean runShellCommand(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-c", command).start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String detectMediaType(byte[] bytes) {
        if (bytes.length >= 4) {
            if (bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47)
                return "image/png";
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8)
                return "image/jpeg";
            if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38)
                return "image/gif";
            if (bytes.length >= 12 && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
                    && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50)
                return "image/webp";
        }
        return "image/png";
    }

    /** Reset sent skill names so they are re-sent on next turn. */
    public void resetSentSkillNames() {
        log.debug("resetSentSkillNames called");
    }
}
