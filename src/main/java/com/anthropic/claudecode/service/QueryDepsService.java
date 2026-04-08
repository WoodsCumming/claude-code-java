package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Query dependencies service.
 * Translated from src/query/deps.ts
 *
 * I/O dependencies for query(). Passing a QueryDeps override into query
 * params lets tests inject fakes directly instead of using module-level
 * spy/mock boilerplate.
 *
 * Scope is intentionally narrow (4 deps) to prove the pattern. Follow-up
 * work can add runTools, handleStopHooks, logEvent, queue ops, etc.
 */
@Slf4j
@Service
public class QueryDepsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryDepsService.class);


    private final ClaudeApiService claudeApiService;
    private final MicroCompactService microCompactService;
    private final AutoCompactService autoCompactService;

    @Autowired
    public QueryDepsService(ClaudeApiService claudeApiService,
                             MicroCompactService microCompactService,
                             AutoCompactService autoCompactService) {
        this.claudeApiService = claudeApiService;
        this.microCompactService = microCompactService;
        this.autoCompactService = autoCompactService;
    }

    /**
     * Build the production QueryDeps wired to the real service implementations.
     * Translated from productionDeps() in deps.ts
     */
    public QueryDeps productionDeps() {
        return new QueryDeps(
            claudeApiService,
            microCompactService,
            autoCompactService,
            () -> UUID.randomUUID().toString()
        );
    }

    // =========================================================================
    // Inner type — mirrors the TS QueryDeps shape
    // =========================================================================

    /**
     * Injectable I/O dependencies for the query engine.
     *
     * Fields:
     *   callModel   — streaming model API call
     *   microcompact — message micro-compaction
     *   autocompact  — auto-compaction check + trigger
     *   uuid         — UUID generator (injectable for deterministic tests)
     */
    public static class QueryDeps {
        private final ClaudeApiService claudeApi;
        private final MicroCompactService microCompact;
        private final AutoCompactService autoCompact;
        private final Supplier<String> uuid;

        public QueryDeps(ClaudeApiService claudeApi, MicroCompactService microCompact, AutoCompactService autoCompact, Supplier<String> uuid) {
            this.claudeApi = claudeApi; this.microCompact = microCompact; this.autoCompact = autoCompact; this.uuid = uuid;
        }
        public ClaudeApiService getClaudeApi() { return claudeApi; }
        public MicroCompactService getMicroCompact() { return microCompact; }
        public AutoCompactService getAutoCompact() { return autoCompact; }
        public Supplier<String> getUuid() { return uuid; }
    }
}
