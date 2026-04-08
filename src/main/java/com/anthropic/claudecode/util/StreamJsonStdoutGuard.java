package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.io.*;

/**
 * Stream JSON stdout guard for stream-json output format.
 * Translated from src/utils/streamJsonStdoutGuard.ts
 *
 * Guards stdout to ensure only valid JSON lines are written when
 * --output-format=stream-json is active.
 */
@Slf4j
public class StreamJsonStdoutGuard {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StreamJsonStdoutGuard.class);


    public static final String STDOUT_GUARD_MARKER = "[stdout-guard]";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static volatile boolean installed = false;
    private static PrintStream originalOut;

    /**
     * Check if a line is valid JSON.
     * Translated from isJsonLine() in streamJsonStdoutGuard.ts
     */
    public static boolean isJsonLine(String line) {
        if (line == null || line.isEmpty()) return true;
        try {
            OBJECT_MAPPER.readTree(line);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Install the stdout guard.
     * Translated from installStreamJsonStdoutGuard() in streamJsonStdoutGuard.ts
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        originalOut = System.out;

        // Create a guarded output stream
        PrintStream guardedOut = new PrintStream(System.out) {
            private StringBuilder buffer = new StringBuilder();

            @Override
            public void write(byte[] buf, int off, int len) {
                String text = new String(buf, off, len);
                buffer.append(text);

                int newlineIdx;
                while ((newlineIdx = buffer.indexOf("\n")) >= 0) {
                    String line = buffer.substring(0, newlineIdx);
                    buffer.delete(0, newlineIdx + 1);

                    if (isJsonLine(line)) {
                        originalOut.println(line);
                    } else {
                        System.err.println(STDOUT_GUARD_MARKER + " " + line);
                    }
                }
            }
        };

        System.setOut(guardedOut);
        log.debug("Stream JSON stdout guard installed");
    }

    /**
     * Uninstall the stdout guard.
     */
    public static synchronized void uninstall() {
        if (!installed || originalOut == null) return;
        System.setOut(originalOut);
        installed = false;
    }

    private StreamJsonStdoutGuard() {}
}
