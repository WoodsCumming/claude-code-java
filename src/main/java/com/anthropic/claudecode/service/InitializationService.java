package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application initialization service.
 * Translated from src/entrypoints/init.ts
 *
 * Handles the startup sequence for Claude Code.
 */
@Slf4j
@Service
public class InitializationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InitializationService.class);


    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private final GlobalConfigService globalConfigService;
    private final SettingsService settingsService;
    private final GracefulShutdownService gracefulShutdownService;
    private final SessionService sessionService;

    @Autowired
    public InitializationService(
            GlobalConfigService globalConfigService,
            SettingsService settingsService,
            GracefulShutdownService gracefulShutdownService,
            SessionService sessionService) {
        this.globalConfigService = globalConfigService;
        this.settingsService = settingsService;
        this.gracefulShutdownService = gracefulShutdownService;
        this.sessionService = sessionService;
    }

    /**
     * Initialize Claude Code.
     * Translated from init() in init.ts
     */
    public CompletableFuture<Void> init() {
        if (!initialized.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            log.debug("[INIT] Starting initialization");
            DiagLogs.logForDiagnosticsNoPII("info", "init_started");

            try {
                // 1. Apply environment variables from settings
                applyConfigEnvironmentVariables();

                // 2. Configure proxy
                ProxyUtils.configureSystemProxy();

                // 3. Initialize session
                sessionService.startSession();

                // 4. Setup graceful shutdown
                gracefulShutdownService.registerShutdownHook();

                // 5. Preconnect to API (fire-and-forget)
                ApiPreconnect.preconnectAnthropicApi();

                long duration = System.currentTimeMillis() - startTime;
                log.debug("[INIT] Initialization complete in {}ms", duration);
                DiagLogs.logForDiagnosticsNoPII("info", "init_completed");

            } catch (Exception e) {
                log.error("[INIT] Initialization failed: {}", e.getMessage(), e);
                throw new RuntimeException("Initialization failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Initialize telemetry after trust dialog.
     * Translated from initializeTelemetryAfterTrust() in init.ts
     */
    public void initializeTelemetryAfterTrust() {
        // Telemetry initialization (stub)
        log.debug("Telemetry initialized");
    }

    @SuppressWarnings("unchecked")
    private void applyConfigEnvironmentVariables() {
        String projectPath = System.getProperty("user.dir");
        java.util.Map<String, Object> settings = settingsService.getMergedSettings(projectPath);
        Object envObj = settings.get("env");
        java.util.Map<String, String> env = null;
        if (envObj instanceof java.util.Map<?, ?> map) {
            env = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String k && entry.getValue() instanceof String v) {
                    env.put(k, v);
                }
            }
        }

        if (env != null && !env.isEmpty()) {
            // In Java, we can't modify System.getenv() directly
            // We would use ProcessBuilder.environment() for subprocesses
            log.debug("Applied {} env vars from settings", env.size());
        }
    }
}
