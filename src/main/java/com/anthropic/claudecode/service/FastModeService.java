package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fast mode service.
 * Translated from src/utils/fastMode.ts
 *
 * Fast mode uses Claude Opus 4.6 with faster output generation.
 * It does NOT switch to a different model.
 */
@Slf4j
@Service
public class FastModeService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FastModeService.class);


    private final AtomicBoolean fastModeActive = new AtomicBoolean(false);
    private final AtomicBoolean fastModeCooldown = new AtomicBoolean(false);

    /**
     * Check if fast mode is enabled.
     * Translated from isFastModeEnabled() in fastMode.ts
     */
    public boolean isFastModeEnabled() {
        return !EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_FAST_MODE"));
    }

    /**
     * Check if fast mode is currently active.
     */
    public boolean isFastModeActive() {
        return fastModeActive.get();
    }

    /**
     * Check if fast mode is in cooldown.
     * Translated from isFastModeCooldown() in fastMode.ts
     */
    public boolean isFastModeCooldown() {
        return fastModeCooldown.get();
    }

    /**
     * Toggle fast mode.
     */
    public boolean toggleFastMode() {
        boolean newState = !fastModeActive.get();
        fastModeActive.set(newState);
        log.info("Fast mode: {}", newState ? "enabled" : "disabled");
        return newState;
    }

    /**
     * Enable fast mode.
     */
    public void enableFastMode() {
        fastModeActive.set(true);
        fastModeCooldown.set(false);
    }

    /**
     * Disable fast mode.
     */
    public void disableFastMode() {
        fastModeActive.set(false);
    }

    /**
     * Trigger fast mode cooldown.
     * Translated from triggerFastModeCooldown() in fastMode.ts
     */
    public void triggerFastModeCooldown() {
        fastModeCooldown.set(true);
        fastModeActive.set(false);

        // Reset cooldown after some time
        new Thread(() -> {
            try {
                Thread.sleep(60_000); // 1 minute cooldown
                fastModeCooldown.set(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * Check if fast mode is supported by a model.
     * Translated from isFastModeSupportedByModel() in fastMode.ts
     */
    public boolean isFastModeSupportedByModel(String model) {
        if (model == null) return false;
        // Fast mode is supported by all Opus 4 models
        return model.toLowerCase().contains("opus-4");
    }
}
