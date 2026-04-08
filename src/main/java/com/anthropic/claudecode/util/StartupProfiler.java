package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Startup profiler for measuring initialization performance.
 * Translated from src/utils/startupProfiler.ts
 */
@Slf4j
public class StartupProfiler {



    private static final boolean DETAILED_PROFILING =
        EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_PROFILE_STARTUP"));

    private static final Map<String, Long> checkpoints = new LinkedHashMap<>();
    private static final long startTime = System.currentTimeMillis();

    /**
     * Record a startup checkpoint.
     * Translated from profileCheckpoint() in startupProfiler.ts
     */
    public static void profileCheckpoint(String name) {
        if (!DETAILED_PROFILING) return;
        long elapsed = System.currentTimeMillis() - startTime;
        checkpoints.put(name, elapsed);
        log.debug("[STARTUP] {} at +{}ms", name, elapsed);
    }

    /**
     * Report the startup profile.
     * Translated from profileReport() in startupProfiler.ts
     */
    public static void profileReport() {
        if (!DETAILED_PROFILING) return;

        log.info("[STARTUP PROFILE]");
        checkpoints.forEach((name, elapsed) ->
            log.info("[STARTUP]   {} at +{}ms", name, elapsed)
        );
        log.info("[STARTUP] Total startup time: {}ms", System.currentTimeMillis() - startTime);
    }

    private StartupProfiler() {}
}
