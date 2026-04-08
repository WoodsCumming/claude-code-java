package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Asciicast terminal recording utilities.
 * Translated from src/utils/asciicast.ts
 *
 * Records terminal sessions in the asciicast v2 format (.cast files).
 * In Java this is adapted as a service-style utility; actual stdout
 * hooking is not possible at the JVM level, so the recorder API is
 * provided for callers that manage output streams directly.
 */
@Slf4j
public class AsciicastUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AsciicastUtils.class);


    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Mutable recording state — filePath is updated when session ID changes
     * (e.g. after resume).
     * Translated from recordingState in asciicast.ts
     */
    private static final AtomicReference<String> recordingFilePath = new AtomicReference<>(null);
    private static volatile long recordingTimestamp = 0;

    /**
     * Get the asciicast recording file path.
     * Returns a path for ant users with CLAUDE_CODE_TERMINAL_RECORDING=1,
     * otherwise returns null.
     * Translated from getRecordFilePath() in asciicast.ts
     *
     * @param claudeConfigHomeDir home directory for Claude config files
     * @param originalCwd         original working directory
     * @param sessionId           current session ID
     * @return path string or null
     */
    public static String getRecordFilePath(String claudeConfigHomeDir,
                                           String originalCwd,
                                           String sessionId) {
        String existing = recordingFilePath.get();
        if (existing != null) {
            return existing;
        }

        String userType = System.getenv("USER_TYPE");
        if (!"ant".equals(userType)) {
            return null;
        }

        String recording = System.getenv("CLAUDE_CODE_TERMINAL_RECORDING");
        if (!isEnvTruthy(recording)) {
            return null;
        }

        String projectsDir = Paths.get(claudeConfigHomeDir, "projects").toString();
        String sanitized = sanitizePath(originalCwd);
        String projectDir = Paths.get(projectsDir, sanitized).toString();

        recordingTimestamp = Instant.now().toEpochMilli();
        String filePath = Paths.get(projectDir, sessionId + "-" + recordingTimestamp + ".cast").toString();
        recordingFilePath.set(filePath);
        return filePath;
    }

    /**
     * Reset recording state (for testing only).
     * Translated from _resetRecordingStateForTesting() in asciicast.ts
     */
    public static void resetRecordingStateForTesting() {
        recordingFilePath.set(null);
        recordingTimestamp = 0;
    }

    /**
     * Find all .cast files for the current session, sorted by filename
     * (chronological by timestamp suffix).
     * Translated from getSessionRecordingPaths() in asciicast.ts
     *
     * @param claudeConfigHomeDir home directory for Claude config files
     * @param originalCwd         original working directory
     * @param sessionId           current session ID
     * @return list of .cast file paths sorted by name
     */
    public static List<String> getSessionRecordingPaths(String claudeConfigHomeDir,
                                                        String originalCwd,
                                                        String sessionId) {
        String projectsDir = Paths.get(claudeConfigHomeDir, "projects").toString();
        String projectDir = Paths.get(projectsDir, sanitizePath(originalCwd)).toString();

        try (Stream<Path> stream = Files.list(Paths.get(projectDir))) {
            List<String> castFiles = stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(sessionId) && name.endsWith(".cast"))
                    .sorted()
                    .map(name -> Paths.get(projectDir, name).toString())
                    .toList();
            return castFiles;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Rename the recording file to match the current session ID.
     * Translated from renameRecordingForSession() in asciicast.ts
     *
     * @param claudeConfigHomeDir home directory for Claude config files
     * @param originalCwd         original working directory
     * @param newSessionId        new session ID after resume/continue
     * @return CompletableFuture that completes when the rename is done
     */
    public static CompletableFuture<Void> renameRecordingForSession(String claudeConfigHomeDir,
                                                                     String originalCwd,
                                                                     String newSessionId) {
        return CompletableFuture.runAsync(() -> {
            String oldPath = recordingFilePath.get();
            if (oldPath == null || recordingTimestamp == 0) {
                return;
            }

            String projectsDir = Paths.get(claudeConfigHomeDir, "projects").toString();
            String projectDir = Paths.get(projectsDir, sanitizePath(originalCwd)).toString();
            String newPath = Paths.get(projectDir, newSessionId + "-" + recordingTimestamp + ".cast").toString();

            if (oldPath.equals(newPath)) {
                return;
            }

            String oldName = Paths.get(oldPath).getFileName().toString();
            String newName = Paths.get(newPath).getFileName().toString();
            try {
                Files.move(Paths.get(oldPath), Paths.get(newPath));
                recordingFilePath.set(newPath);
                log.debug("[asciicast] Renamed recording: {} -> {}", oldName, newName);
            } catch (IOException e) {
                log.debug("[asciicast] Failed to rename recording from {} to {}", oldName, newName);
            }
        });
    }

    /**
     * Write the asciicast v2 header to the given file path.
     * Translated from the header-writing logic in installAsciicastRecorder() in asciicast.ts
     *
     * @param filePath    path to the .cast file
     * @param cols        terminal width in columns
     * @param rows        terminal height in rows
     * @param shell       SHELL environment variable value
     * @param term        TERM environment variable value
     * @throws IOException if the file cannot be written
     */
    public static void writeAsciicastHeader(String filePath, int cols, int rows,
                                             String shell, String term) throws IOException {
        Path path = Paths.get(filePath);
        Files.createDirectories(path.getParent());

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("version", 2);
        header.put("width", cols);
        header.put("height", rows);
        header.put("timestamp", Instant.now().getEpochSecond());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SHELL", shell != null ? shell : "");
        env.put("TERM", term != null ? term : "");
        header.put("env", env);

        String headerJson = OBJECT_MAPPER.writeValueAsString(header);

        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);

        Files.writeString(path, headerJson + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows)
        }
    }

    /**
     * Append an asciicast v2 event line to the recording file.
     * Translated from the writer.write() calls in installAsciicastRecorder() in asciicast.ts
     *
     * @param filePath path to the .cast file
     * @param elapsed  elapsed time in seconds since recording started
     * @param type     event type: "o" for output, "r" for resize
     * @param data     event data (terminal output text or "COLSxROWS" resize string)
     * @throws IOException if the file cannot be written
     */
    public static void appendAsciicastEvent(String filePath, double elapsed,
                                             String type, String data) throws IOException {
        String currentPath = recordingFilePath.get();
        if (currentPath == null) {
            currentPath = filePath;
        }
        List<Object> event = List.of(elapsed, type, data);
        String eventJson = OBJECT_MAPPER.writeValueAsString(event);
        Files.writeString(Paths.get(currentPath), eventJson + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static boolean isEnvTruthy(String value) {
        if (value == null) return false;
        return "1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    /**
     * Sanitize a filesystem path for use as a directory name component.
     * Matches the sanitizePath() helper used in the TS source.
     */
    private static String sanitizePath(String path) {
        if (path == null) return "";
        // Replace characters that are illegal in directory names on common platforms
        return path.replaceAll("[/\\\\:*?\"<>|]", "_");
    }

    private AsciicastUtils() {}
}
