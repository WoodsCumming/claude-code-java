package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Computer use drain run loop service.
 * Translated from src/utils/computerUse/drainRunLoop.ts
 *
 * Pumps the main run loop for macOS Swift operations.
 * In Java, we use thread management instead of CFRunLoop.
 */
@Slf4j
@Service
public class ComputerUseDrainRunLoopService {



    private volatile ScheduledFuture<?> pump;
    private final AtomicInteger pending = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Execute a function while draining the run loop.
     * Translated from drainRunLoop() in drainRunLoop.ts
     */
    public <T> CompletableFuture<T> drainRunLoop(Callable<T> fn) {
        return CompletableFuture.supplyAsync(() -> {
            pending.incrementAndGet();
            try {
                return fn.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                pending.decrementAndGet();
            }
        });
    }
}
