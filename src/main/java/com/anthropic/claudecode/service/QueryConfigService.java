package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Query configuration service.
 * Translated from src/query/config.ts
 *
 * Builds immutable QueryConfig snapshotted once at query() entry.
 *
 * Intentionally excludes feature-gate flags — those are tree-shaking
 * boundaries and must stay inline at the guarded blocks.
 *
 * Runtime gates (env / statsig) are snapshotted here because
 * CACHED_MAY_BE_STALE already admits staleness, so snapshotting once
 * per query() call stays within the existing contract.
 */
@Slf4j
@Service
public class QueryConfigService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryConfigService.class);


    private final BootstrapStateService bootstrapStateService;
    private final GrowthBookService growthBookService;

    @Autowired
    public QueryConfigService(BootstrapStateService bootstrapStateService,
                               GrowthBookService growthBookService) {
        this.bootstrapStateService = bootstrapStateService;
        this.growthBookService = growthBookService;
    }

    /**
     * Build an immutable QueryConfig for a single query() invocation.
     * Translated from buildQueryConfig() in config.ts
     */
    public QueryConfig buildQueryConfig() {
        return new QueryConfig(
            bootstrapStateService.getSessionId(),
            new QueryConfig.Gates(
                // Statsig gate — cached, may be stale (same contract as TS)
                growthBookService.checkFeatureGateCachedMayBeStale("tengu_streaming_tool_execution2"),
                // Env flag: CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES
                EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES")),
                // USER_TYPE === 'ant'
                "ant".equals(System.getenv("USER_TYPE")),
                // Fast-mode enabled unless CLAUDE_CODE_DISABLE_FAST_MODE is set
                !EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_FAST_MODE"))
            )
        );
    }

    // =========================================================================
    // Inner types — immutable record-style config, mirroring the TS shape
    // =========================================================================

    /**
     * Immutable values snapshotted once at query() entry.
     * Separating these from per-iteration State and the mutable ToolUseContext
     * makes future step() extraction tractable.
     */
    public static class QueryConfig {
        private String sessionId;
        private Gates gates;

        public QueryConfig() {}
        public QueryConfig(String sessionId, Gates gates) { this.sessionId = sessionId; this.gates = gates; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public Gates getGates() { return gates; }
        public void setGates(Gates v) { gates = v; }

        public static class Gates {
            private boolean streamingToolExecution;
            private boolean emitToolUseSummaries;
            private boolean isAnt;
            private boolean fastModeEnabled;

            public Gates() {}
            public Gates(boolean streamingToolExecution, boolean emitToolUseSummaries, boolean isAnt, boolean fastModeEnabled) {
                this.streamingToolExecution = streamingToolExecution; this.emitToolUseSummaries = emitToolUseSummaries;
                this.isAnt = isAnt; this.fastModeEnabled = fastModeEnabled;
            }
            public boolean isStreamingToolExecution() { return streamingToolExecution; }
            public boolean isEmitToolUseSummaries() { return emitToolUseSummaries; }
            public boolean isIsAnt() { return isAnt; }
            public boolean isFastModeEnabled() { return fastModeEnabled; }
        }
    }
}
