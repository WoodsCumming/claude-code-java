package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Compact warning state service.
 * Translated from src/services/compact/compactWarningHook.ts
 * and src/services/compact/compactWarningState.ts
 *
 * Tracks whether the compact warning should be suppressed after microcompaction.
 * In the TypeScript source the state lives in compactWarningState.ts (a plain
 * mutable store) and the React hook lives in compactWarningHook.ts. Here both
 * responsibilities are unified in a single Spring service so subscribers can
 * react to state changes without React.
 */
@Slf4j
@Service
public class CompactWarningService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompactWarningService.class);


    private final AtomicBoolean suppressed = new AtomicBoolean(false);
    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Returns true when the compact warning is currently suppressed.
     * Corresponds to getState() in the TypeScript store.
     */
    public boolean isCompactWarningSuppressed() {
        return suppressed.get();
    }

    /**
     * Suppress the compact warning.
     * Translated from suppressCompactWarning() in compactWarningState.ts
     */
    public void suppressCompactWarning() {
        boolean prev = suppressed.getAndSet(true);
        if (!prev) {
            notifyListeners();
        }
    }

    /**
     * Clear the compact warning suppression.
     * Translated from clearCompactWarningSuppression() in compactWarningState.ts
     *
     * Called at the start of each new microcompact attempt so a warning can
     * reappear if the next compaction also doesn't relieve enough pressure.
     */
    public void clearCompactWarningSuppression() {
        boolean prev = suppressed.getAndSet(false);
        if (prev) {
            notifyListeners();
        }
    }

    /**
     * Subscribe to state changes.
     * Returns a {@link Runnable} that, when invoked, unsubscribes the listener.
     * Corresponds to compactWarningStore.subscribe() in the TypeScript source.
     */
    public Runnable subscribe(Consumer<Boolean> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void notifyListeners() {
        boolean state = suppressed.get();
        for (Consumer<Boolean> listener : listeners) {
            try {
                listener.accept(state);
            } catch (Exception e) {
                log.warn("Compact warning listener threw an exception", e);
            }
        }
    }
}
