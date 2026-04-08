package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Fullscreen mode utilities.
 * Translated from src/utils/fullscreenMode.ts
 *
 * Note: The TypeScript source file {@code fullscreenMode.ts} was not present in the
 * repository at translation time. This class is therefore modelled on the existing
 * {@code FullscreenUtils} translation and the fullscreen-related environment
 * variables referenced across the codebase.
 *
 * If the source file is later found, this class should be updated accordingly.
 *
 * Detects whether the terminal supports fullscreen / inline rendering and
 * provides a cached, resettable flag for the session.
 */
@Slf4j
public class FullscreenModeUtils {



    private static final AtomicReference<Boolean> cachedResult = new AtomicReference<>(null);

    /**
     * Returns {@code true} when the current terminal environment supports
     * fullscreen / inline rendering mode.
     *
     * The result is memoized after the first call. Call {@link #resetCache()}
     * to force re-evaluation (e.g. in tests).
     */
    public static boolean isFullscreenModeEnabled() {
        Boolean cached = cachedResult.get();
        if (cached != null) return cached;

        boolean result = detectFullscreenMode();
        cachedResult.compareAndSet(null, result);
        return cachedResult.get();
    }

    /**
     * Determine whether fullscreen mode should be active for the current
     * terminal / environment configuration.
     *
     * Logic mirrors the checks in the existing fullscreen detection code:
     *   1. Explicit {@code CLAUDE_CODE_FULLSCREEN} env var wins.
     *   2. iTerm2 with tmux integration enables fullscreen.
     *   3. All other environments default to disabled.
     */
    private static boolean detectFullscreenMode() {
        // 1. Explicit override
        String envVal = System.getenv("CLAUDE_CODE_FULLSCREEN");
        if (envVal != null && !envVal.isEmpty()) {
            if (EnvUtils.isEnvTruthy(envVal)) {
                log.debug("Fullscreen mode enabled via CLAUDE_CODE_FULLSCREEN");
                return true;
            }
            log.debug("Fullscreen mode disabled via CLAUDE_CODE_FULLSCREEN");
            return false;
        }

        // 2. iTerm2 + tmux integration
        String termProgram = System.getenv("TERM_PROGRAM");
        String tmux = System.getenv("TMUX");
        if ("iTerm.app".equals(termProgram) && tmux != null && !tmux.isEmpty()) {
            log.debug("Fullscreen mode enabled: iTerm2 + tmux detected");
            return true;
        }

        return false;
    }

    /**
     * Reset the cached fullscreen state.
     * Useful in tests that need to change environment variables between calls.
     */
    public static void resetCache() {
        cachedResult.set(null);
    }

    private FullscreenModeUtils() {}
}
