package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Computer use ESC hotkey service.
 * Translated from src/utils/computerUse/escHotkey.ts
 *
 * Manages a global Escape key handler for aborting computer use operations.
 */
@Slf4j
@Service
public class ComputerUseEscHotkeyService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComputerUseEscHotkeyService.class);


    private final AtomicBoolean registered = new AtomicBoolean(false);
    private volatile Runnable abortCallback;

    /**
     * Register the ESC hotkey handler.
     * Translated from registerEscHotkey() in escHotkey.ts
     */
    public void registerEscHotkey(Runnable onAbort) {
        if (registered.compareAndSet(false, true)) {
            this.abortCallback = onAbort;
            log.debug("[computer-use] ESC hotkey registered");
        }
    }

    /**
     * Unregister the ESC hotkey handler.
     * Translated from unregisterEscHotkey() in escHotkey.ts
     */
    public void unregisterEscHotkey() {
        if (registered.compareAndSet(true, false)) {
            this.abortCallback = null;
            log.debug("[computer-use] ESC hotkey unregistered");
        }
    }

    /**
     * Notify that an expected ESC is coming (from the model).
     * Translated from notifyExpectedEscape() in escHotkey.ts
     */
    public void notifyExpectedEscape() {
        // In a full implementation, this would set a flag to allow the next ESC
        log.debug("[computer-use] Expected ESC notified");
    }

    /**
     * Handle an ESC key event.
     */
    public void handleEsc() {
        if (registered.get() && abortCallback != null) {
            log.info("[computer-use] ESC pressed, aborting");
            abortCallback.run();
        }
    }
}
