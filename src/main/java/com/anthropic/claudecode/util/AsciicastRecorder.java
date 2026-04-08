package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asciicast terminal recording utilities.
 * Translated from src/utils/asciicast.ts
 *
 * Records terminal sessions in asciicast format for playback.
 */
@Slf4j
public class AsciicastRecorder {



    private static final AtomicReference<String> recordFilePath = new AtomicReference<>();
    private static volatile long recordingTimestamp = 0;

    /**
     * Get the record file path.
     * Translated from getRecordFilePath() in asciicast.ts
     */
    public static String getRecordFilePath() {
        if (recordFilePath.get() != null) return recordFilePath.get();

        if (!EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_TERMINAL_RECORDING"))) {
            return null;
        }

        String projectsDir = EnvUtils.getClaudeConfigHomeDir() + "/projects";
        String cwd = System.getProperty("user.dir");
        String sanitizedCwd = cwd.replace("/", "-").replace("\\", "-");

        recordingTimestamp = System.currentTimeMillis();
        String path = projectsDir + "/" + sanitizedCwd + "/recordings/" + recordingTimestamp + ".cast";
        recordFilePath.set(path);
        return path;
    }

    /**
     * Install the asciicast recorder.
     * Translated from installAsciicastRecorder() in asciicast.ts
     */
    public static void installAsciicastRecorder() {
        String path = getRecordFilePath();
        if (path == null) return;

        try {
            new File(path).getParentFile().mkdirs();
            // Write asciicast header
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("version", 2);
            header.put("width", RenderOptions.getTerminalWidth());
            header.put("height", RenderOptions.getTerminalHeight());
            header.put("timestamp", System.currentTimeMillis() / 1000);
            header.put("title", "Claude Code Session");

            Files.writeString(Paths.get(path), SlowOperations.jsonStringify(header) + "\n");
            log.debug("Asciicast recording started: {}", path);
        } catch (Exception e) {
            log.debug("Could not start asciicast recording: {}", e.getMessage());
        }
    }

    /**
     * Record a terminal event.
     */
    public static void recordEvent(double timestamp, String type, String data) {
        String path = getRecordFilePath();
        if (path == null) return;

        try {
            List<Object> event = List.of(timestamp, type, data);
            Files.writeString(Paths.get(path), SlowOperations.jsonStringify(event) + "\n",
                StandardOpenOption.APPEND);
        } catch (Exception e) {
            // Silently fail - recording is best-effort
        }
    }

    private AsciicastRecorder() {}
}
