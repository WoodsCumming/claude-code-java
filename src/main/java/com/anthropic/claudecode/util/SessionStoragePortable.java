package com.anthropic.claudecode.util;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portable session storage utilities.
 * Translated from src/utils/sessionStoragePortable.ts
 *
 * <p>Pure utilities — no dependency on logging, experiments, or feature flags.
 * Shared between CLI and VS Code extension equivalents.</p>
 */
public class SessionStoragePortable {

    /** Size of the head/tail buffer for lite metadata reads (64 KB). */
    public static final int LITE_READ_BUF_SIZE = 65536;

    /** Max length for a single sanitized path component (200 chars). */
    public static final int MAX_SANITIZED_LENGTH = 200;

    /**
     * File size below which precompact filtering is skipped (5 MB).
     * Translated from SKIP_PRECOMPACT_THRESHOLD in sessionStoragePortable.ts
     */
    public static final long SKIP_PRECOMPACT_THRESHOLD = 5L * 1024 * 1024;

    // -------------------------------------------------------------------------
    // UUID validation
    // -------------------------------------------------------------------------

    private static final Pattern UUID_REGEX = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Validate a UUID string.
     * Translated from validateUuid() in sessionStoragePortable.ts
     */
    public static String validateUuid(Object maybeUuid) {
        if (!(maybeUuid instanceof String s)) return null;
        return UUID_REGEX.matcher(s).matches() ? s : null;
    }

    // -------------------------------------------------------------------------
    // JSON string field extraction (no full parse, works on truncated lines)
    // -------------------------------------------------------------------------

    /**
     * Unescape a JSON string value extracted as raw text.
     * Only allocates a new string when escape sequences are present.
     * Translated from unescapeJsonString() in sessionStoragePortable.ts
     */
    public static String unescapeJsonString(String raw) {
        if (!raw.contains("\\")) return raw;
        try {
            // Delegate to JSON parsing — wrap in quotes to make it a valid JSON string
            // We use a minimal parser to avoid a full JSON library dependency.
            StringBuilder sb = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < raw.length()) {
                    char next = raw.charAt(++i);
                    switch (next) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 < raw.length()) {
                                String hex = raw.substring(i + 1, i + 5);
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } else {
                                sb.append('\\').append(next);
                            }
                        }
                        default -> { sb.append('\\'); sb.append(next); }
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * Extracts a simple JSON string field value from raw text without full parsing.
     * Looks for {@code "key":"value"} or {@code "key": "value"} patterns.
     * Returns the first match, or null if not found.
     * Translated from extractJsonStringField() in sessionStoragePortable.ts
     */
    public static String extractJsonStringField(String text, String key) {
        String[] patterns = { "\"" + key + "\":\"", "\"" + key + "\": \"" };
        for (String pattern : patterns) {
            int idx = text.indexOf(pattern);
            if (idx < 0) continue;

            int valueStart = idx + pattern.length();
            int i = valueStart;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return unescapeJsonString(text.substring(valueStart, i));
                }
                i++;
            }
        }
        return null;
    }

    /**
     * Like {@link #extractJsonStringField} but finds the LAST occurrence.
     * Useful for fields that are appended (customTitle, tag, etc.).
     * Translated from extractLastJsonStringField() in sessionStoragePortable.ts
     */
    public static String extractLastJsonStringField(String text, String key) {
        String[] patterns = { "\"" + key + "\":\"", "\"" + key + "\": \"" };
        String lastValue = null;
        for (String pattern : patterns) {
            int searchFrom = 0;
            while (true) {
                int idx = text.indexOf(pattern, searchFrom);
                if (idx < 0) break;

                int valueStart = idx + pattern.length();
                int i = valueStart;
                while (i < text.length()) {
                    char c = text.charAt(i);
                    if (c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        lastValue = unescapeJsonString(text.substring(valueStart, i));
                        break;
                    }
                    i++;
                }
                searchFrom = i + 1;
            }
        }
        return lastValue;
    }

    // -------------------------------------------------------------------------
    // First prompt extraction from head chunk
    // -------------------------------------------------------------------------

    private static final Pattern SKIP_FIRST_PROMPT_PATTERN =
            Pattern.compile("^(?:\\s*<[a-z][\\w-]*[\\s>]|\\[Request interrupted by user[^\\]]*\\])");
    private static final Pattern COMMAND_NAME_RE =
            Pattern.compile("<command-name>(.*?)</command-name>");
    private static final Pattern BASH_INPUT_RE =
            Pattern.compile("<bash-input>([\\s\\S]*?)</bash-input>");

    /**
     * Extracts the first meaningful user prompt from a JSONL head chunk.
     *
     * Skips tool_result messages, isMeta, isCompactSummary, command-name
     * messages, and auto-generated patterns. Truncates to 200 chars.
     * Translated from extractFirstPromptFromHead() in sessionStoragePortable.ts
     */
    public static String extractFirstPromptFromHead(String head) {
        int start = 0;
        String commandFallback = "";

        while (start < head.length()) {
            int newlineIdx = head.indexOf('\n', start);
            String line = (newlineIdx >= 0) ? head.substring(start, newlineIdx) : head.substring(start);
            start = (newlineIdx >= 0) ? newlineIdx + 1 : head.length();

            if (!line.contains("\"type\":\"user\"") && !line.contains("\"type\": \"user\"")) continue;
            if (line.contains("\"tool_result\"")) continue;
            if (line.contains("\"isMeta\":true") || line.contains("\"isMeta\": true")) continue;
            if (line.contains("\"isCompactSummary\":true") || line.contains("\"isCompactSummary\": true")) continue;

            // Extract text content from the line without full JSON parse
            // Look for content blocks
            String contentStr = extractJsonStringField(line, "content");
            if (contentStr == null) continue;

            String result = contentStr.replace("\n", " ").trim();
            if (result.isEmpty()) continue;

            Matcher cmdMatch = COMMAND_NAME_RE.matcher(result);
            if (cmdMatch.find()) {
                if (commandFallback.isEmpty()) commandFallback = cmdMatch.group(1);
                continue;
            }

            Matcher bashMatch = BASH_INPUT_RE.matcher(result);
            if (bashMatch.find()) return "! " + bashMatch.group(1).trim();

            if (SKIP_FIRST_PROMPT_PATTERN.matcher(result).find()) continue;

            if (result.length() > 200) {
                result = result.substring(0, 200).trim() + "\u2026";
            }
            return result;
        }
        return commandFallback.isEmpty() ? "" : commandFallback;
    }

    // -------------------------------------------------------------------------
    // Lite session file I/O
    // -------------------------------------------------------------------------

    /**
     * Lite session file: mtime, size, head, and tail chunks.
     * Translated from LiteSessionFile in sessionStoragePortable.ts
     */
    public record LiteSessionFile(
            long mtime,
            long size,
            String head,
            String tail
    ) {}

    /**
     * Result of a head/tail read.
     * Translated from readHeadAndTail() in sessionStoragePortable.ts
     */
    public record HeadAndTail(String head, String tail) {}

    /**
     * Reads the first and last LITE_READ_BUF_SIZE bytes of a file.
     * For small files where head covers tail, tail === head.
     * Returns {@code {head: "", tail: ""}} on any error.
     * Translated from readHeadAndTail() in sessionStoragePortable.ts
     */
    public static CompletableFuture<HeadAndTail> readHeadAndTail(String filePath, long fileSize) {
        return CompletableFuture.supplyAsync(() -> {
            try (FileChannel fc = FileChannel.open(Paths.get(filePath), StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.allocate(LITE_READ_BUF_SIZE);
                int headBytes = fc.read(buf, 0);
                if (headBytes <= 0) return new HeadAndTail("", "");
                buf.flip();
                String head = StandardCharsets.UTF_8.decode(buf).toString();

                long tailOffset = Math.max(0, fileSize - LITE_READ_BUF_SIZE);
                String tail = head;
                if (tailOffset > 0) {
                    buf.clear();
                    int tailBytes = fc.read(buf, tailOffset);
                    if (tailBytes > 0) {
                        buf.flip();
                        tail = StandardCharsets.UTF_8.decode(buf).toString();
                    }
                }
                return new HeadAndTail(head, tail);
            } catch (Exception e) {
                return new HeadAndTail("", "");
            }
        });
    }

    /**
     * Opens a single session file, stats it, and reads head + tail.
     * Returns null on any error.
     * Translated from readSessionLite() in sessionStoragePortable.ts
     */
    public static CompletableFuture<LiteSessionFile> readSessionLite(String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path path = Paths.get(filePath);
                var attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                long size = attrs.size();
                long mtime = attrs.lastModifiedTime().toMillis();

                try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
                    ByteBuffer buf = ByteBuffer.allocate(LITE_READ_BUF_SIZE);
                    int headBytes = fc.read(buf, 0);
                    if (headBytes <= 0) return null;
                    buf.flip();
                    String head = StandardCharsets.UTF_8.decode(buf).toString();

                    long tailOffset = Math.max(0, size - LITE_READ_BUF_SIZE);
                    String tail = head;
                    if (tailOffset > 0) {
                        buf.clear();
                        int tailBytes = fc.read(buf, tailOffset);
                        if (tailBytes > 0) {
                            buf.flip();
                            tail = StandardCharsets.UTF_8.decode(buf).toString();
                        }
                    }
                    return new LiteSessionFile(mtime, size, head, tail);
                }
            } catch (Exception e) {
                return null;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Path sanitization
    // -------------------------------------------------------------------------

    /**
     * Makes a string safe for use as a directory or file name.
     * Replaces all non-alphanumeric characters with hyphens.
     * For long paths (> MAX_SANITIZED_LENGTH), truncates and appends a hash.
     * Translated from sanitizePath() in sessionStoragePortable.ts
     */
    public static String sanitizePath(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9]", "-");
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return sanitized;
        }
        int hash = Math.abs(HashUtils.djb2Hash(name));
        return sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-" + Integer.toString(hash, 36);
    }

    // -------------------------------------------------------------------------
    // Project directory discovery
    // -------------------------------------------------------------------------

    /**
     * Get the root projects directory.
     * Translated from getProjectsDir() in sessionStoragePortable.ts
     */
    public static String getProjectsDir() {
        return EnvUtils.getClaudeConfigHomeDir() + "/projects";
    }

    /**
     * Get the project directory for a specific project path.
     * Translated from getProjectDir() in sessionStoragePortable.ts
     */
    public static String getProjectDir(String projectDir) {
        return getProjectsDir() + "/" + sanitizePath(projectDir);
    }

    /**
     * Sanitize a project path for use as a directory name.
     * Alias for sanitizePath, kept for backward compatibility.
     */
    public static String sanitizePathForProjectDir(String projectPath) {
        if (projectPath == null) return "";
        return sanitizePath(projectPath);
    }

    /**
     * Resolves a directory path to its canonical form using realpath + NFC normalization.
     * Falls back to NFC-only if realpath fails.
     * Translated from canonicalizePath() in sessionStoragePortable.ts
     */
    public static CompletableFuture<String> canonicalizePath(String dir) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Paths.get(dir).toRealPath().toString();
            } catch (Exception e) {
                return dir;
            }
        });
    }

    /**
     * Finds the project directory for a given path, tolerating hash mismatches
     * for long paths (> MAX_SANITIZED_LENGTH). Falls back to prefix-based scanning.
     * Translated from findProjectDir() in sessionStoragePortable.ts
     */
    public static CompletableFuture<String> findProjectDir(String projectPath) {
        return CompletableFuture.supplyAsync(() -> {
            String exact = getProjectDir(projectPath);
            Path exactPath = Paths.get(exact);
            if (Files.isDirectory(exactPath)) {
                return exact;
            }

            // For short paths no sessions exist
            String sanitized = sanitizePath(projectPath);
            if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
                return null;
            }

            // Long path: try prefix matching to handle hash mismatches
            String prefix = sanitized.substring(0, MAX_SANITIZED_LENGTH);
            Path projectsDir = Paths.get(getProjectsDir());
            try (var stream = Files.newDirectoryStream(projectsDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry) && entry.getFileName().toString().startsWith(prefix + "-")) {
                        return entry.toString();
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            return null;
        });
    }

    /**
     * Result of a session file path resolution.
     * Translated from the return type of resolveSessionFilePath() in sessionStoragePortable.ts
     */
    public record SessionFileLocation(
            String filePath,
            String projectPath,
            long fileSize
    ) {}

    /**
     * Resolve a sessionId to its on-disk JSONL file path.
     *
     * When {@code dir} is provided: canonicalize it, look in that project's
     * directory (with findProjectDir fallback), then fall back to sibling
     * git worktrees. When {@code dir} is null: scan all project directories.
     *
     * Translated from resolveSessionFilePath() in sessionStoragePortable.ts
     */
    public static CompletableFuture<SessionFileLocation> resolveSessionFilePath(
            String sessionId, String dir) {
        return CompletableFuture.supplyAsync(() -> {
            String fileName = sessionId + ".jsonl";

            if (dir != null && !dir.isBlank()) {
                try {
                    String canonical = canonicalizePath(dir).join();
                    String projectDir = findProjectDir(canonical).join();
                    if (projectDir != null) {
                        Path filePath = Paths.get(projectDir, fileName);
                        try {
                            long size = Files.size(filePath);
                            if (size > 0) {
                                return new SessionFileLocation(filePath.toString(), canonical, size);
                            }
                        } catch (Exception ignored) {}
                    }
                    // Worktree fallback omitted (requires git integration)
                } catch (Exception e) {
                    // fall through to scan
                }
                return null;
            }

            // No dir — scan all project directories
            Path projectsDir = Paths.get(getProjectsDir());
            try {
                if (!Files.isDirectory(projectsDir)) return null;
                try (var stream = Files.newDirectoryStream(projectsDir)) {
                    for (Path entry : stream) {
                        Path filePath = entry.resolve(fileName);
                        try {
                            long size = Files.size(filePath);
                            if (size > 0) {
                                return new SessionFileLocation(filePath.toString(), null, size);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            return null;
        });
    }

    /** Convenience overload without directory. */
    public static CompletableFuture<SessionFileLocation> resolveSessionFilePath(String sessionId) {
        return resolveSessionFilePath(sessionId, null);
    }

    // -------------------------------------------------------------------------
    // Transcript read
    // -------------------------------------------------------------------------

    /**
     * Simple transcript read: returns the file contents as a string.
     * The full compact-boundary chunked algorithm from the TypeScript source is
     * complex and depends on binary Buffer manipulation; this version provides
     * the essential read path used by most callers.
     * Translated from readTranscriptForLoad() in sessionStoragePortable.ts
     */
    public static CompletableFuture<Optional<String>> readTranscriptForLoad(String transcriptPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String content = Files.readString(Paths.get(transcriptPath));
                return Optional.of(content);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    private SessionStoragePortable() {}
}
