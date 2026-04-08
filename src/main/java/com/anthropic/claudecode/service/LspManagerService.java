package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LSP manager service — singleton lifecycle controller.
 * Translated from src/services/lsp/manager.ts
 *
 * Manages the global LSP server manager singleton.  Initialization runs
 * asynchronously in the background without blocking Claude Code startup.
 * A generation counter prevents stale init callbacks from updating state.
 */
@Slf4j
@Service
public class LspManagerService {



    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    public enum InitializationState {
        NOT_STARTED, PENDING, SUCCESS, FAILED
    }

    private volatile LspServerManager lspManagerInstance;
    private final AtomicReference<InitializationState> initializationState =
        new AtomicReference<>(InitializationState.NOT_STARTED);
    private volatile Throwable initializationError;
    private final AtomicInteger initializationGeneration = new AtomicInteger(0);
    private volatile CompletableFuture<Void> initializationPromise;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final LspServerManager lspServerManagerPrototype;
    private final LspPassiveFeedbackService passiveFeedbackService;

    @Autowired
    public LspManagerService(LspServerManager lspServerManagerPrototype,
                              LspPassiveFeedbackService passiveFeedbackService) {
        this.lspServerManagerPrototype = lspServerManagerPrototype;
        this.passiveFeedbackService = passiveFeedbackService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Get the singleton LSP server manager instance.
     * Returns null if not yet initialized, initialization failed, or still pending.
     * Translated from getLspServerManager() in manager.ts
     */
    public LspServerManager getLspServerManager() {
        if (initializationState.get() == InitializationState.FAILED) {
            return null;
        }
        return lspManagerInstance;
    }

    /**
     * Get the current initialization status of the LSP server manager.
     * Translated from getInitializationStatus() in manager.ts
     */
    public InitializationStatus getInitializationStatus() {
        InitializationState state = initializationState.get();
        return switch (state) {
            case FAILED      -> new InitializationStatus.Failed(
                initializationError instanceof Exception e ? e
                    : new RuntimeException("Initialization failed"));
            case NOT_STARTED -> new InitializationStatus.NotStarted();
            case PENDING     -> new InitializationStatus.Pending();
            case SUCCESS     -> new InitializationStatus.Success();
        };
    }

    /**
     * Check whether at least one language server is connected and healthy.
     * Translated from isLspConnected() in manager.ts
     */
    public boolean isLspConnected() {
        if (initializationState.get() == InitializationState.FAILED) return false;
        LspServerManager manager = getLspServerManager();
        if (manager == null) return false;
        var servers = manager.getAllServers();
        if (servers == null || servers.isEmpty()) return false;
        return servers.values().stream().anyMatch(s -> !"error".equals(s.getState()));
    }

    /**
     * Wait for initialization to complete (success or failure).
     * Returns immediately if initialization has already finished.
     * Translated from waitForInitialization() in manager.ts
     */
    public CompletableFuture<Void> waitForInitialization() {
        InitializationState state = initializationState.get();
        if (state == InitializationState.SUCCESS || state == InitializationState.FAILED) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> promise = initializationPromise;
        if (state == InitializationState.PENDING && promise != null) {
            return promise;
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Initialize the LSP server manager singleton.
     *
     * Synchronously creates the manager instance and kicks off async initialization
     * in the background.  Safe to call multiple times — will only initialize once
     * (idempotent) unless the previous initialization failed.
     * Translated from initializeLspServerManager() in manager.ts
     */
    public synchronized void initializeLspServerManager() {
        log.debug("[LSP MANAGER] initializeLspServerManager() called");

        // Skip if already initialized or currently initializing (not failed)
        if (lspManagerInstance != null
                && initializationState.get() != InitializationState.FAILED) {
            log.debug("[LSP MANAGER] Already initialized or initializing, skipping");
            return;
        }

        // Reset for retry if previous initialization failed
        if (initializationState.get() == InitializationState.FAILED) {
            lspManagerInstance = null;
            initializationError = null;
        }

        lspManagerInstance = lspServerManagerPrototype;
        initializationState.set(InitializationState.PENDING);
        log.debug("[LSP MANAGER] Created manager instance, state=PENDING");

        int currentGeneration = initializationGeneration.incrementAndGet();
        log.debug("[LSP MANAGER] Starting async initialization (generation {})", currentGeneration);

        initializationPromise = lspManagerInstance.initialize()
            .thenRun(() -> {
                if (currentGeneration == initializationGeneration.get()) {
                    initializationState.set(InitializationState.SUCCESS);
                    log.debug("LSP server manager initialized successfully");
                    // Register passive notification handlers for diagnostics
                    if (lspManagerInstance != null) {
                        passiveFeedbackService.registerLSPNotificationHandlers(lspManagerInstance);
                    }
                }
            })
            .exceptionally(error -> {
                if (currentGeneration == initializationGeneration.get()) {
                    initializationState.set(InitializationState.FAILED);
                    initializationError = error;
                    lspManagerInstance = null;
                    log.error("Failed to initialize LSP server manager: {}", error.getMessage());
                }
                return null;
            });
    }

    /**
     * Force re-initialization of the LSP server manager, even after a prior
     * successful init.  Called after plugin caches are cleared so newly-loaded
     * plugin LSP servers are picked up.
     * Translated from reinitializeLspServerManager() in manager.ts
     */
    public synchronized void reinitializeLspServerManager() {
        if (initializationState.get() == InitializationState.NOT_STARTED) {
            return;
        }

        log.debug("[LSP MANAGER] reinitializeLspServerManager() called");

        // Best-effort shutdown of any running servers on the old instance
        if (lspManagerInstance != null) {
            LspServerManager old = lspManagerInstance;
            old.shutdown().exceptionally(err -> {
                log.debug("[LSP MANAGER] old instance shutdown during reinit failed: {}", err.getMessage());
                return null;
            });
        }

        // Force the idempotence check in initializeLspServerManager() to fall through
        lspManagerInstance = null;
        initializationState.set(InitializationState.NOT_STARTED);
        initializationError = null;

        initializeLspServerManager();
    }

    /**
     * Shutdown the LSP server manager and clean up resources.
     * State is always cleared even if shutdown fails.
     * Translated from shutdownLspServerManager() in manager.ts
     */
    public synchronized CompletableFuture<Void> shutdownLspServerManager() {
        if (lspManagerInstance == null) {
            return CompletableFuture.completedFuture(null);
        }

        LspServerManager instance = lspManagerInstance;
        return instance.shutdown()
            .whenComplete((v, err) -> {
                if (err != null) {
                    log.error("Failed to shutdown LSP server manager: {}", err.getMessage());
                } else {
                    log.debug("LSP server manager shut down successfully");
                }
                // Always clear state even if shutdown failed
                lspManagerInstance = null;
                initializationState.set(InitializationState.NOT_STARTED);
                initializationError = null;
                initializationPromise = null;
                initializationGeneration.incrementAndGet();
            });
    }

    /**
     * Test-only sync reset — clears module-scope singleton state without
     * tearing down real connections.
     * Translated from _resetLspManagerForTesting() in manager.ts
     */
    public synchronized void resetForTesting() {
        initializationState.set(InitializationState.NOT_STARTED);
        initializationError = null;
        initializationPromise = null;
        initializationGeneration.incrementAndGet();
    }

    // -------------------------------------------------------------------------
    // Sealed status type
    // -------------------------------------------------------------------------

    /**
     * Mirrors the union return type of getInitializationStatus() in manager.ts.
     */
    public sealed interface InitializationStatus
            permits InitializationStatus.NotStarted,
                    InitializationStatus.Pending,
                    InitializationStatus.Success,
                    InitializationStatus.Failed {

        record NotStarted() implements InitializationStatus {}
        record Pending()    implements InitializationStatus {}
        record Success()    implements InitializationStatus {}
        record Failed(Exception error) implements InitializationStatus {}
    }
}
