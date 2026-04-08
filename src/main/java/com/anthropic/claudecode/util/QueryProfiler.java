package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Query profiling utility for measuring and reporting time spent in the query
 * pipeline from user input to first token arrival.
 *
 * Enable by setting CLAUDE_CODE_PROFILE_QUERY=1.
 *
 * Translated from:
 *   - src/utils/queryProfiler.ts          (core checkpoint API)
 *   - src/utils/telemetry/perfettoTracing.ts (Chrome Trace Event types, span model,
 *                                             agent hierarchy, periodic write)
 *
 * This class serves dual roles:
 *   1. Query-latency profiler (CLAUDE_CODE_PROFILE_QUERY=1) — records named
 *      checkpoints within a single query and logs a report at the end.
 *   2. Perfetto / Chrome Trace Event foundation — defines the TraceEvent and
 *      TraceEventPhase types used by the session-tracing layer (SessionTracingUtils).
 *      When CLAUDE_CODE_PERFETTO_TRACE is set, events are accumulated and written
 *      to a JSON file readable by ui.perfetto.dev.
 *
 * Checkpoints tracked (in order):
 *   query_user_input_received        — Start of profiling
 *   query_context_loading_start/end  — Loading system prompts and contexts
 *   query_query_start                — Entry to query call from REPL
 *   query_fn_entry                   — Entry to query() function
 *   query_microcompact_start/end     — Micro-compaction of messages
 *   query_autocompact_start/end      — Auto-compaction check
 *   query_setup_start/end            — StreamingToolExecutor and model setup
 *   query_api_loop_start             — Start of API retry loop
 *   query_api_streaming_start        — Start of streaming API call
 *   query_tool_schema_build_start/end
 *   query_message_normalization_start/end
 *   query_client_creation_start/end
 *   query_api_request_sent           — HTTP request dispatched
 *   query_response_headers_received  — Response headers arrived
 *   query_first_chunk_received       — First streaming chunk (TTFT)
 *   query_api_streaming_end          — Streaming complete
 *   query_tool_execution_start/end   — Tool execution
 *   query_recursive_call             — Before recursive query call
 *   query_end                        — End of query
 */
@Slf4j
public final class QueryProfiler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryProfiler.class);


    // =========================================================================
    // Query profiler enablement
    // =========================================================================

    private static final boolean ENABLED =
        EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_PROFILE_QUERY"));

    private static final ThreadLocal<QuerySession> SESSION = new ThreadLocal<>();
    private static final AtomicInteger QUERY_COUNT = new AtomicInteger(0);

    // =========================================================================
    // Chrome Trace Event types  (perfettoTracing.ts)
    // =========================================================================

    /**
     * Chrome Trace Event format phase identifiers.
     * See: https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU
     *
     * Translated from TraceEventPhase in perfettoTracing.ts.
     */
    public enum TraceEventPhase {
        /** Begin duration event */          BEGIN("B"),
        /** End duration event */            END("E"),
        /** Complete event (with duration) */COMPLETE("X"),
        /** Instant event */                 INSTANT("i"),
        /** Counter event */                 COUNTER("C"),
        /** Async begin */                   ASYNC_BEGIN("b"),
        /** Async instant */                 ASYNC_INSTANT("n"),
        /** Async end */                     ASYNC_END("e"),
        /** Metadata event */                METADATA("M");

        public final String symbol;
        TraceEventPhase(String symbol) { this.symbol = symbol; }
    }

    /**
     * A single Chrome Trace Event record.
     * Translated from TraceEvent in perfettoTracing.ts.
     */
    public record TraceEvent(
            String name,
            String cat,
            TraceEventPhase ph,
            /** Timestamp in microseconds relative to trace start. */
            long ts,
            /** Process ID (1 for main process, numeric ID for sub-agents). */
            int pid,
            /** Thread ID (numeric hash of agent name, or 1 for main). */
            int tid,
            /** Duration in microseconds (for COMPLETE events). */
            Long dur,
            /** Extra key/value arguments shown in the Perfetto UI. */
            Map<String, Object> args,
            /** Async event correlation ID. */
            String id
    ) {
        /** Convenience constructor without optional fields. */
        public TraceEvent(String name, String cat, TraceEventPhase ph,
                          long ts, int pid, int tid) {
            this(name, cat, ph, ts, pid, tid, null, null, null);
        }
    }

    // =========================================================================
    // Perfetto tracing constants  (perfettoTracing.ts)
    // =========================================================================

    public static final long STALE_SPAN_TTL_MS          = 30L * 60 * 1000;
    public static final long STALE_SPAN_CLEANUP_INTERVAL = 60_000L;
    public static final int  MAX_EVENTS                  = 100_000;

    // =========================================================================
    // Query profiler public API
    // =========================================================================

    /**
     * Start profiling a new query session.
     * Translated from startQueryProfile() in queryProfiler.ts.
     */
    public static void startQueryProfile() {
        if (!ENABLED) return;
        SESSION.set(new QuerySession(QUERY_COUNT.incrementAndGet()));
        queryCheckpoint("query_user_input_received");
    }

    /**
     * Record a named checkpoint.
     * Translated from queryCheckpoint() in queryProfiler.ts.
     */
    public static void queryCheckpoint(String name) {
        if (!ENABLED) return;
        QuerySession session = SESSION.get();
        if (session == null) return;

        long now = System.nanoTime();
        session.checkpoints.put(name, now);

        if ("query_first_chunk_received".equals(name) && session.firstTokenNanos == 0) {
            session.firstTokenNanos = now;
        }
    }

    /**
     * End the current query profiling session.
     * Translated from endQueryProfile() in queryProfiler.ts.
     */
    public static void endQueryProfile() {
        if (!ENABLED) return;
        queryCheckpoint("query_profile_end");
    }

    /**
     * Log the query profile report to debug output.
     * Translated from logQueryProfileReport() in queryProfiler.ts.
     */
    public static void logQueryProfileReport() {
        if (!ENABLED) return;
        QuerySession session = SESSION.get();
        if (session == null) return;
        log.debug("{}", buildReport(session));
    }

    // =========================================================================
    // Perfetto enablement check  (perfettoTracing.ts)
    // =========================================================================

    /**
     * Check if Perfetto tracing is enabled.
     * Translated from isPerfettoTracingEnabled() in perfettoTracing.ts.
     */
    public static boolean isPerfettoTracingEnabled() {
        String val = System.getenv("CLAUDE_CODE_PERFETTO_TRACE");
        return val != null && !val.isBlank() && !EnvUtils.isEnvDefinedFalsy(val);
    }

    /**
     * Resolve the Perfetto trace file path.
     * When CLAUDE_CODE_PERFETTO_TRACE=1, uses the default path under ~/.claude/traces/.
     * Otherwise uses the env value as a direct file path.
     *
     * Translated from the path-resolution logic in initializePerfettoTracing()
     * in perfettoTracing.ts.
     *
     * @param sessionId the current session ID
     * @return resolved trace file path, or null if Perfetto is disabled
     */
    public static String resolvePerfettoTracePath(String sessionId) {
        String val = System.getenv("CLAUDE_CODE_PERFETTO_TRACE");
        if (val == null || val.isBlank() || EnvUtils.isEnvDefinedFalsy(val)) return null;

        if (EnvUtils.isEnvTruthy(val)) {
            String home = System.getenv("CLAUDE_HOME");
            if (home == null || home.isBlank()) {
                home = System.getProperty("user.home") + "/.claude";
            }
            return home + "/traces/trace-" + sessionId + ".json";
        }
        return val; // explicit path
    }

    /**
     * Returns the periodic write interval in seconds, or -1 if not configured.
     * Translated from the CLAUDE_CODE_PERFETTO_WRITE_INTERVAL_S parse in
     * initializePerfettoTracing() in perfettoTracing.ts.
     */
    public static int getPerfettoWriteIntervalSeconds() {
        String raw = System.getenv("CLAUDE_CODE_PERFETTO_WRITE_INTERVAL_S");
        if (raw == null || raw.isBlank()) return -1;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // =========================================================================
    // Private report building
    // =========================================================================

    private static String buildReport(QuerySession session) {
        Map<String, Long> marks = session.checkpoints;
        if (marks.isEmpty()) return "No query profiling checkpoints recorded";

        List<Map.Entry<String, Long>> entries = new ArrayList<>(marks.entrySet());
        entries.sort(Map.Entry.comparingByValue());

        StringBuilder sb = new StringBuilder();
        String separator = "=".repeat(80);
        sb.append(separator).append('\n');
        sb.append("QUERY PROFILING REPORT - Query #").append(session.queryNumber).append('\n');
        sb.append(separator).append('\n').append('\n');

        long baselineNanos = entries.get(0).getValue();
        long prevNanos = baselineNanos;
        long apiRequestSentMs = 0, firstChunkMs = 0;

        for (Map.Entry<String, Long> entry : entries) {
            long relativeMs = nanosToMs(entry.getValue() - baselineNanos);
            long deltaMs    = nanosToMs(entry.getValue() - prevNanos);
            String warning  = getSlowWarning(deltaMs, entry.getKey());

            sb.append(String.format("  %8dms  \u0394%6dms  %-50s%s%n",
                relativeMs, deltaMs, entry.getKey(), warning));

            if ("query_api_request_sent".equals(entry.getKey()))    apiRequestSentMs = relativeMs;
            if ("query_first_chunk_received".equals(entry.getKey())) firstChunkMs     = relativeMs;

            prevNanos = entry.getValue();
        }

        sb.append('\n').append("-".repeat(80)).append('\n');
        if (firstChunkMs > 0) {
            long preRequest = apiRequestSentMs;
            long network    = firstChunkMs - apiRequestSentMs;
            sb.append(String.format("Total TTFT: %dms%n", firstChunkMs));
            sb.append(String.format("  - Pre-request overhead: %dms (%.1f%%)%n",
                preRequest, safePercent(preRequest, firstChunkMs)));
            sb.append(String.format("  - Network latency:      %dms (%.1f%%)%n",
                network, safePercent(network, firstChunkMs)));
        } else {
            long totalMs = nanosToMs(entries.get(entries.size() - 1).getValue() - baselineNanos);
            sb.append(String.format("Total time: %dms%n", totalMs));
        }

        sb.append(buildPhaseSummary(marks, baselineNanos));
        sb.append(separator);
        return sb.toString();
    }

    private static String buildPhaseSummary(Map<String, Long> marks, long baselineNanos) {
        record Phase(String name, String start, String end) {}

        List<Phase> phases = List.of(
            new Phase("Context loading",    "query_context_loading_start",       "query_context_loading_end"),
            new Phase("Microcompact",       "query_microcompact_start",          "query_microcompact_end"),
            new Phase("Autocompact",        "query_autocompact_start",           "query_autocompact_end"),
            new Phase("Query setup",        "query_setup_start",                 "query_setup_end"),
            new Phase("Tool schemas",       "query_tool_schema_build_start",     "query_tool_schema_build_end"),
            new Phase("Msg normalization",  "query_message_normalization_start", "query_message_normalization_end"),
            new Phase("Client creation",    "query_client_creation_start",       "query_client_creation_end"),
            new Phase("Network TTFB",       "query_api_request_sent",            "query_first_chunk_received"),
            new Phase("Tool execution",     "query_tool_execution_start",        "query_tool_execution_end")
        );

        StringBuilder sb = new StringBuilder("\nPHASE BREAKDOWN:\n");
        for (Phase phase : phases) {
            Long startNanos = marks.get(phase.start());
            Long endNanos   = marks.get(phase.end());
            if (startNanos == null || endNanos == null) continue;
            long durationMs = nanosToMs(endNanos - startNanos);
            int blocks = Math.min((int) Math.ceil(durationMs / 10.0), 50);
            String bar = "\u2588".repeat(blocks);
            sb.append(String.format("  %-24s %8dms %s%n", phase.name(), durationMs, bar));
        }

        Long apiRequestNanos = marks.get("query_api_request_sent");
        if (apiRequestNanos != null) {
            long preApiMs = nanosToMs(apiRequestNanos - baselineNanos);
            sb.append(String.format("%n  %-24s %8dms%n", "Total pre-API overhead", preApiMs));
        }

        return sb.toString();
    }

    private static String getSlowWarning(long deltaMs, String name) {
        if ("query_user_input_received".equals(name)) return "";
        if (deltaMs > 1000) return "  WARNING: VERY SLOW";
        if (deltaMs > 100)  return "  WARNING: SLOW";
        if (name.contains("git_status")      && deltaMs > 50) return "  WARNING: git status";
        if (name.contains("tool_schema")     && deltaMs > 50) return "  WARNING: tool schemas";
        if (name.contains("client_creation") && deltaMs > 50) return "  WARNING: client creation";
        return "";
    }

    private static long nanosToMs(long nanos) { return nanos / 1_000_000L; }

    private static double safePercent(long part, long total) {
        return total == 0 ? 0.0 : (double) part / total * 100.0;
    }

    // =========================================================================
    // Session state
    // =========================================================================

    private static class QuerySession {
        final int queryNumber;
        final Map<String, Long> checkpoints = new LinkedHashMap<>();
        long firstTokenNanos = 0;

        QuerySession(int queryNumber) {
            this.queryNumber = queryNumber;
        }
    }

    private QueryProfiler() {}
}
