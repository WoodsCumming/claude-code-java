package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Early input capture for the REPL.
 * Translated from src/utils/earlyInput.ts
 *
 * Captures terminal input typed before the REPL is fully initialized.
 */
@Slf4j
public class EarlyInputCapture {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EarlyInputCapture.class);


    private static final AtomicBoolean capturing = new AtomicBoolean(false);
    private static final AtomicReference<StringBuilder> buffer = new AtomicReference<>(new StringBuilder());

    /**
     * Start capturing early input.
     * Translated from startCapturingEarlyInput() in earlyInput.ts
     */
    public static void startCapturing() {
        if (capturing.compareAndSet(false, true)) {
            buffer.set(new StringBuilder());
            log.debug("Early input capture started");
        }
    }

    /**
     * Stop capturing early input.
     * Translated from stopCapturingEarlyInput() in earlyInput.ts
     */
    public static void stopCapturing() {
        capturing.set(false);
        log.debug("Early input capture stopped");
    }

    /**
     * Get and clear the captured input.
     * Translated from consumeEarlyInput() in earlyInput.ts
     */
    public static String consumeEarlyInput() {
        stopCapturing();
        StringBuilder buf = buffer.getAndSet(new StringBuilder());
        return buf.toString();
    }

    /**
     * Add input to the buffer.
     */
    public static void addInput(String input) {
        if (capturing.get() && input != null) {
            buffer.get().append(input);
        }
    }

    /**
     * Check if there is any early input available without consuming it.
     * Translated from hasEarlyInput() in earlyInput.ts
     */
    public static boolean hasEarlyInput() {
        return !buffer.get().toString().trim().isEmpty();
    }

    /**
     * Seed the early input buffer with text that will appear pre-filled
     * in the prompt input when the REPL renders. Does not auto-submit.
     * Translated from seedEarlyInput() in earlyInput.ts
     */
    public static void seedEarlyInput(String text) {
        if (text != null) {
            buffer.set(new StringBuilder(text));
        }
    }

    /**
     * Check if early input capture is currently active.
     * Translated from isCapturingEarlyInput() in earlyInput.ts
     */
    public static boolean isCapturing() {
        return capturing.get();
    }

    private EarlyInputCapture() {}
}
