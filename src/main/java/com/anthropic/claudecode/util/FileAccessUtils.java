package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * File system access utilities.
 * Translated from src/utils/file.ts
 *
 * Provides path resolution, file reading/writing (with atomic-write support),
 * line-number formatting, and cross-platform path helpers.
 */
@Slf4j
public class FileAccessUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileAccessUtils.class);


    /** Maximum output size: 0.25 MB in bytes.
     *  Translated from MAX_OUTPUT_SIZE in file.ts */
    public static final long MAX_OUTPUT_SIZE = (long) (0.25 * 1024 * 1024);

    /** Marker included in file-not-found error messages that contain a cwd note.
     *  Translated from FILE_NOT_FOUND_CWD_NOTE in file.ts */
    public static final String FILE_NOT_FOUND_CWD_NOTE =
            "Note: your current working directory is";

    // -------------------------------------------------------------------------
    // File record
    // Translated from File type in file.ts
    // -------------------------------------------------------------------------

    /**
     * A simple filename + content pair.
     * Translated from the File type in file.ts
     */
    public record FileRecord(String filename, String content) {}

    // -------------------------------------------------------------------------
    // pathExists
    // Translated from pathExists() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Check whether a path exists asynchronously.
     * Translated from pathExists() in file.ts
     */
    public static CompletableFuture<Boolean> pathExists(String path) {
        return CompletableFuture.supplyAsync(() -> Files.exists(Paths.get(path)));
    }

    // -------------------------------------------------------------------------
    // readFileSafe
    // Translated from readFileSafe() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Read a file's contents as UTF-8, returning null on any error.
     * Translated from readFileSafe() in file.ts
     */
    public static String readFileSafe(String filePath) {
        try {
            return Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("readFileSafe failed for {}", filePath, e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // getFileModificationTime / getFileModificationTimeAsync
    // Translated from getFileModificationTime / getFileModificationTimeAsync in file.ts
    // -------------------------------------------------------------------------

    /**
     * Return the modification time of a file in milliseconds (floored to ms).
     * Translated from getFileModificationTime() in file.ts
     */
    public static long getFileModificationTime(String filePath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    Paths.get(filePath), BasicFileAttributes.class);
            return Math.floorDiv(attrs.lastModifiedTime().toMillis(), 1);
        } catch (IOException e) {
            throw new RuntimeException("Cannot stat file: " + filePath, e);
        }
    }

    /**
     * Async variant of {@link #getFileModificationTime}.
     * Translated from getFileModificationTimeAsync() in file.ts
     */
    public static CompletableFuture<Long> getFileModificationTimeAsync(String filePath) {
        return CompletableFuture.supplyAsync(() -> getFileModificationTime(filePath));
    }

    // -------------------------------------------------------------------------
    // writeTextContent
    // Translated from writeTextContent() in file.ts
    // -------------------------------------------------------------------------

    /** Line-ending type.
     *  Translated from LineEndingType in file.ts */
    public enum LineEndingType { LF, CRLF }

    /**
     * Write text content to a file, normalising line endings.
     * Translated from writeTextContent() in file.ts
     */
    public static void writeTextContent(String filePath, String content,
                                         Charset encoding, LineEndingType endings) {
        String toWrite = content;
        if (endings == LineEndingType.CRLF) {
            // Normalise any existing CRLF to LF first, then convert all LF to CRLF
            toWrite = content.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        writeFileSyncAndFlush(filePath, toWrite, encoding, null);
    }

    // -------------------------------------------------------------------------
    // convertLeadingTabsToSpaces
    // Translated from convertLeadingTabsToSpaces() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Convert leading tabs on each line to two spaces each.
     * Translated from convertLeadingTabsToSpaces() in file.ts
     */
    public static String convertLeadingTabsToSpaces(String content) {
        if (content == null || !content.contains("\t")) return content;
        return Pattern.compile("^\\t+", Pattern.MULTILINE).matcher(content)
                .replaceAll(m -> "  ".repeat(m.group().length()));
    }

    // -------------------------------------------------------------------------
    // getDisplayPath
    // Translated from getDisplayPath() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Return a display-friendly path (relative to cwd when possible, or ~-prefixed
     * for paths under the home directory).
     * Translated from getDisplayPath() in file.ts
     *
     * @param filePath absolute path to the file
     * @param cwd      current working directory
     * @return display path string
     */
    public static String getDisplayPath(String filePath, String cwd) {
        try {
            Path file = Paths.get(filePath);
            Path cwdPath = Paths.get(cwd);
            Path relative = cwdPath.relativize(file);
            String relStr = relative.toString();
            if (!relStr.startsWith("..")) {
                return relStr;
            }
        } catch (Exception ignored) {}

        // Tilde-notation for home directory
        String homeDir = System.getProperty("user.home");
        if (homeDir != null && filePath.startsWith(homeDir + "/")) {
            return "~" + filePath.substring(homeDir.length());
        }

        return filePath;
    }

    // -------------------------------------------------------------------------
    // findSimilarFile
    // Translated from findSimilarFile() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Find files with the same base name but different extension in the same directory.
     * Translated from findSimilarFile() in file.ts
     *
     * @param filePath path to the file that was not found
     * @return basename of a similar file, or null if none
     */
    public static String findSimilarFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            Path dir = path.getParent();
            if (dir == null) return null;

            String fileName = path.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String baseName = dot >= 0 ? fileName.substring(0, dot) : fileName;

            try (var stream = Files.list(dir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(name -> {
                            int d = name.lastIndexOf('.');
                            String b = d >= 0 ? name.substring(0, d) : name;
                            return b.equals(baseName) && !name.equals(fileName);
                        })
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            if (!(e instanceof NoSuchFileException)) {
                log.error("findSimilarFile failed for {}", filePath, e);
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // suggestPathUnderCwd
    // Translated from suggestPathUnderCwd() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Suggest a corrected path under the current working directory when a
     * file/directory is not found (handles the "dropped repo folder" pattern).
     * Translated from suggestPathUnderCwd() in file.ts
     *
     * @param requestedPath the absolute path that was not found
     * @param cwd           current working directory
     * @return corrected path if it exists, or null
     */
    public static CompletableFuture<String> suggestPathUnderCwd(String requestedPath, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path reqPath = Paths.get(requestedPath);
                Path cwdPath = Paths.get(cwd);
                Path cwdParent = cwdPath.getParent();
                if (cwdParent == null) return null;

                // Resolve symlinks in the requested path's parent
                Path resolvedPath = reqPath;
                try {
                    Path resolvedDir = reqPath.getParent() != null
                            ? reqPath.getParent().toRealPath()
                            : null;
                    if (resolvedDir != null) {
                        resolvedPath = resolvedDir.resolve(reqPath.getFileName());
                    }
                } catch (IOException ignored) {}

                String resolvedStr = resolvedPath.toString();
                String cwdParentStr = cwdParent.toString();
                String cwdStr = cwdPath.toString();

                String prefix = cwdParentStr.equals("/") ? "/" : cwdParentStr + "/";
                if (!resolvedStr.startsWith(prefix)
                        || resolvedStr.startsWith(cwdStr + "/")
                        || resolvedStr.equals(cwdStr)) {
                    return null;
                }

                Path relFromParent = cwdParent.relativize(resolvedPath);
                Path corrected = cwdPath.resolve(relFromParent);

                if (Files.exists(corrected)) {
                    return corrected.toString();
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    // -------------------------------------------------------------------------
    // addLineNumbers / stripLineNumberPrefix
    // Translated from addLineNumbers() / stripLineNumberPrefix() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Add cat-n style line numbers to content.
     * Uses compact format {@code N\tLINE} when {@code compact} is true,
     * otherwise the padded-arrow format {@code      N→LINE}.
     * Translated from addLineNumbers() in file.ts
     *
     * @param content   file content
     * @param startLine 1-indexed starting line number
     * @param compact   true to use compact N-tab format
     * @return content with line numbers prepended to each line
     */
    public static String addLineNumbers(String content, int startLine, boolean compact) {
        if (content == null || content.isEmpty()) return "";

        String[] lines = content.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            int lineNum = i + startLine;
            if (compact) {
                sb.append(lineNum).append('\t').append(lines[i]);
            } else {
                String numStr = String.valueOf(lineNum);
                if (numStr.length() >= 6) {
                    sb.append(numStr).append('\u2192').append(lines[i]);
                } else {
                    sb.append(String.format("%6d", lineNum)).append('\u2192').append(lines[i]);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Strip the {@code N→} or {@code N\t} line-number prefix from a single line.
     * Translated from stripLineNumberPrefix() in file.ts
     */
    public static String stripLineNumberPrefix(String line) {
        if (line == null) return null;
        java.util.regex.Matcher m = Pattern.compile("^\\s*\\d+[\u2192\t](.*)$")
                .matcher(line);
        return m.matches() ? m.group(1) : line;
    }

    // -------------------------------------------------------------------------
    // isDirEmpty
    // Translated from isDirEmpty() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Check whether a directory is empty (or does not exist).
     * Translated from isDirEmpty() in file.ts
     */
    public static boolean isDirEmpty(String dirPath) {
        try (var stream = Files.newDirectoryStream(Paths.get(dirPath))) {
            return !stream.iterator().hasNext();
        } catch (NoSuchFileException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // isFileWithinReadSizeLimit
    // Translated from isFileWithinReadSizeLimit() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Validate that a file's size is within the specified limit.
     * Translated from isFileWithinReadSizeLimit() in file.ts
     */
    public static boolean isFileWithinReadSizeLimit(String filePath, long maxSizeBytes) {
        try {
            return Files.size(Paths.get(filePath)) <= maxSizeBytes;
        } catch (IOException e) {
            return false;
        }
    }

    /** Overload using the default limit {@link #MAX_OUTPUT_SIZE}. */
    public static boolean isFileWithinReadSizeLimit(String filePath) {
        return isFileWithinReadSizeLimit(filePath, MAX_OUTPUT_SIZE);
    }

    // -------------------------------------------------------------------------
    // normalizePathForComparison / pathsEqual
    // Translated from normalizePathForComparison() / pathsEqual() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Normalize a file path for comparison, handling platform differences.
     * On Windows, converts to lowercase for case-insensitive comparison.
     * Translated from normalizePathForComparison() in file.ts
     */
    public static String normalizePathForComparison(String filePath) {
        String normalized = Paths.get(filePath).normalize().toString();
        if (isWindows()) {
            normalized = normalized.replace('/', '\\').toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    /**
     * Compare two file paths for equality, handling Windows case-insensitivity.
     * Translated from pathsEqual() in file.ts
     */
    public static boolean pathsEqual(String path1, String path2) {
        return normalizePathForComparison(path1).equals(normalizePathForComparison(path2));
    }

    // -------------------------------------------------------------------------
    // getDesktopPath
    // Translated from getDesktopPath() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Return the platform-appropriate Desktop directory path.
     * Translated from getDesktopPath() in file.ts
     */
    public static String getDesktopPath() {
        String homeDir = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("mac") || os.contains("linux")) {
            Path desktop = Paths.get(homeDir, "Desktop");
            if (Files.isDirectory(desktop)) return desktop.toString();
            return homeDir;
        }

        if (os.contains("win")) {
            String userProfile = System.getenv("USERPROFILE");
            if (userProfile != null) {
                Path desktop = Paths.get(userProfile, "Desktop");
                if (Files.isDirectory(desktop)) return desktop.toString();
            }
        }

        Path desktop = Paths.get(homeDir, "Desktop");
        return Files.isDirectory(desktop) ? desktop.toString() : homeDir;
    }

    // -------------------------------------------------------------------------
    // writeFileSyncAndFlush (atomic write helper)
    // Translated from writeFileSyncAndFlush_DEPRECATED() in file.ts
    // -------------------------------------------------------------------------

    /**
     * Write content to a file atomically (via a temp file + rename).
     * Falls back to a direct write if the atomic approach fails.
     * Translated from writeFileSyncAndFlush_DEPRECATED() in file.ts
     *
     * @param filePath path to the destination file
     * @param content  content to write
     * @param encoding character encoding to use
     * @param mode     POSIX file permission bits (e.g. 0600); may be null
     */
    public static void writeFileSyncAndFlush(String filePath, String content,
                                              Charset encoding, Integer mode) {
        if (encoding == null) encoding = StandardCharsets.UTF_8;
        Path target = Paths.get(filePath);

        // Follow symlink if necessary
        try {
            Path linkTarget = Files.readSymbolicLink(target);
            target = linkTarget.isAbsolute()
                    ? linkTarget
                    : target.getParent().resolve(linkTarget);
            log.debug("Writing through symlink: {} -> {}", filePath, target);
        } catch (NotLinkException | UnsupportedOperationException ignored) {
            // Not a symlink — use original path
        } catch (IOException ignored) {}

        // Capture existing permissions
        Set<PosixFilePermission> existingPerms = null;
        boolean targetExists = Files.exists(target);
        if (targetExists) {
            try {
                existingPerms = Files.getPosixFilePermissions(target);
            } catch (UnsupportedOperationException | IOException ignored) {}
        }

        // Attempt atomic write via temp file
        Path tempPath = target.resolveSibling(
                target.getFileName() + ".tmp." + ProcessHandle.current().pid()
                        + "." + System.currentTimeMillis());
        try {
            OpenOption[] options = {
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            };
            Files.writeString(tempPath, content, encoding, options);

            if (existingPerms != null) {
                try {
                    Files.setPosixFilePermissions(tempPath, existingPerms);
                } catch (UnsupportedOperationException ignored) {}
            }

            Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.debug("File {} written atomically", target);
        } catch (IOException atomicError) {
            log.error("Failed to write file atomically: {}", atomicError.getMessage());
            // Clean up temp file
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}

            // Fallback: direct write
            try {
                Files.writeString(target, content, encoding,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                log.debug("File {} written with non-atomic fallback", target);
            } catch (IOException fallbackError) {
                log.error("Non-atomic write also failed: {}", fallbackError.getMessage());
                throw new RuntimeException("Failed to write file: " + filePath, fallbackError);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private FileAccessUtils() {}
}
