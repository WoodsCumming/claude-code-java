package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fullscreen mode detection utilities.
 * Translated from src/utils/fullscreen.ts
 *
 * Detects whether the terminal supports fullscreen/inline rendering.
 */
@Slf4j
public class FullscreenUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FullscreenUtils.class);


    private static final AtomicBoolean fullscreenEnvEnabled = new AtomicBoolean(false);
    private static final AtomicReference<Boolean> cachedResult = new AtomicReference<>(null);

    /**
     * Check if fullscreen environment is enabled.
     * Translated from isFullscreenEnvEnabled() in fullscreen.ts
     */
    public static boolean isFullscreenEnvEnabled() {
        Boolean cached = cachedResult.get();
        if (cached != null) return cached;

        boolean result = checkFullscreenEnabled();
        cachedResult.set(result);
        return result;
    }

    private static boolean checkFullscreenEnabled() {
        // Check explicit disable
        String envVal = System.getenv("CLAUDE_CODE_FULLSCREEN");
        if (envVal != null) {
            if (EnvUtils.isEnvDefinedFalsy(envVal)) return false;
            if (EnvUtils.isEnvTruthy(envVal)) return true;
        }

        // Check for iTerm2 with tmux integration
        String termProgram = System.getenv("TERM_PROGRAM");
        String tmux = System.getenv("TMUX");
        if ("iTerm.app".equals(termProgram) && tmux != null) {
            return true;
        }

        return false;
    }

    /**
     * Reset the cached fullscreen state.
     */
    public static void resetCache() {
        cachedResult.set(null);
    }

    private FullscreenUtils() {}
}
