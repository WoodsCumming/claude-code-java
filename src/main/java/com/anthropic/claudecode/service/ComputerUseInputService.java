package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PlatformUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

/**
 * Computer use input service.
 * Translated from src/utils/computerUse/inputLoader.ts
 *
 * Handles keyboard and mouse input for computer use.
 */
@Slf4j
@Service
public class ComputerUseInputService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComputerUseInputService.class);

    /**
     * Check if computer use input is supported.
     * Translated from isComputerUseInputSupported() in inputLoader.ts
     */
    public boolean isSupported() {
        return PlatformUtils.isMacOS();
    }

    /**
     * Press a key.
     * Translated from key() in inputLoader.ts
     */
    public CompletableFuture<Void> pressKey(String key) {
        return CompletableFuture.runAsync(() -> {
            if (!isSupported()) return;
            try {
                // Use cliclick or similar tool
                ProcessBuilder pb = new ProcessBuilder("cliclick", "kp:" + key);
                pb.start().waitFor();
            } catch (Exception e) {
                log.debug("[computer-use] Key press failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Press multiple keys.
     * Translated from keys() in inputLoader.ts
     */
    public CompletableFuture<Void> pressKeys(String[] keys) {
        return CompletableFuture.runAsync(() -> {
            if (!isSupported()) return;
            for (String key : keys) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("cliclick", "kp:" + key);
                    pb.start().waitFor();
                } catch (Exception e) {
                    log.debug("[computer-use] Key press failed: {}", e.getMessage());
                }
            }
        });
    }
}
