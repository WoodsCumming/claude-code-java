package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Headless mode profiler for measuring per-turn latency.
 * Translated from src/utils/headlessProfiler.ts
 */
@Slf4j
public class HeadlessProfiler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HeadlessProfiler.class);


    private static final boolean DETAILED_PROFILING =
        EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_PROFILE_STARTUP"));

    private static final Map<String, Long> marks = new ConcurrentHashMap<>();
    private static long turnStart = 0;

    /**
     * Record a headless profiler checkpoint.
     * Translated from headlessProfilerCheckpoint() in headlessProfiler.ts
     */
    public static void checkpoint(String name) {
        if (!DETAILED_PROFILING) return;
        marks.put("headless_" + name, System.currentTimeMillis());
        log.debug("[HEADLESS] {} at +{}ms", name, System.currentTimeMillis() - turnStart);
    }

    /**
     * Start a new turn.
     */
    public static void startTurn() {
        if (!DETAILED_PROFILING) return;
        turnStart = System.currentTimeMillis();
        marks.clear();
    }

    private HeadlessProfiler() {}
}
