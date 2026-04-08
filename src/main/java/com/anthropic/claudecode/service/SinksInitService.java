package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Sinks initialization service.
 * Translated from src/utils/sinks.ts
 *
 * Attaches error log and analytics sinks, draining any events queued before
 * attachment. Both inits are idempotent. Called from setup() for the default
 * command; other entrypoints (subcommands, daemon, bridge) call this directly
 * since they bypass setup().
 *
 * Leaf module — kept out of setup to avoid import cycles.
 */
@Slf4j
@Service
public class SinksInitService {



    private final AnalyticsSinkService analyticsSinkService;
    private final ErrorLogSinkService errorLogSinkService;

    @Autowired
    public SinksInitService(AnalyticsSinkService analyticsSinkService,
                            ErrorLogSinkService errorLogSinkService) {
        this.analyticsSinkService = analyticsSinkService;
        this.errorLogSinkService = errorLogSinkService;
    }

    /**
     * Attach error log and analytics sinks.
     * Translated from initSinks() in sinks.ts
     */
    public void initSinks() {
        errorLogSinkService.initialize();
        analyticsSinkService.initialize();
        log.debug("Sinks initialized");
    }
}
