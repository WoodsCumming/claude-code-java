package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

/**
 * Computer use cleanup service.
 * Translated from src/utils/computerUse/cleanup.ts
 *
 * Handles cleanup after computer use operations.
 */
@Slf4j
@Service
public class ComputerUseCleanupService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComputerUseCleanupService.class);


    private final ComputerUseLockService lockService;

    @Autowired
    public ComputerUseCleanupService(ComputerUseLockService lockService) {
        this.lockService = lockService;
    }

    /**
     * Perform turn-end cleanup for computer use.
     * Translated from cleanupComputerUse() in cleanup.ts
     */
    public CompletableFuture<Void> cleanupComputerUse() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Release the computer use lock
                lockService.releaseLock();
                log.debug("[computer-use] Cleanup complete");
            } catch (Exception e) {
                log.debug("[computer-use] Cleanup error: {}", e.getMessage());
            }
        });
    }
}
