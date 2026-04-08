package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PlatformUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Computer use executor service.
 * Translated from src/utils/computerUse/executor.ts
 *
 * Executes computer use actions (mouse, keyboard, screenshots).
 */
@Slf4j
@Service
public class ComputerUseExecutorService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComputerUseExecutorService.class);


    private final ComputerUseService computerUseService;

    @Autowired
    public ComputerUseExecutorService(ComputerUseService computerUseService) {
        this.computerUseService = computerUseService;
    }

    /**
     * Take a screenshot.
     * Translated from takeScreenshot() in executor.ts
     */
    public CompletableFuture<Optional<byte[]>> takeScreenshot() {
        if (!computerUseService.isComputerUseEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            if (!PlatformUtils.isMacOS()) {
                log.debug("[computer-use] Screenshots only supported on macOS");
                return Optional.empty();
            }

            try {
                String tempPath = System.getProperty("java.io.tmpdir") + "/claude-screenshot.png";
                ProcessBuilder pb = new ProcessBuilder("screencapture", "-x", tempPath);
                Process process = pb.start();
                int exitCode = process.waitFor();

                if (exitCode != 0) return Optional.empty();

                byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(tempPath));
                new java.io.File(tempPath).delete();
                return Optional.of(bytes);
            } catch (Exception e) {
                log.debug("[computer-use] Screenshot failed: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    /**
     * Move the mouse cursor.
     * Translated from moveMouse() in executor.ts
     */
    public CompletableFuture<Void> moveMouse(int x, int y) {
        if (!computerUseService.isComputerUseEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // Use cliclick or similar tool on macOS
                ProcessBuilder pb = new ProcessBuilder("cliclick", "m:" + x + "," + y);
                pb.start().waitFor();
            } catch (Exception e) {
                log.debug("[computer-use] Mouse move failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Click at a position.
     * Translated from click() in executor.ts
     */
    public CompletableFuture<Void> click(int x, int y, String button) {
        if (!computerUseService.isComputerUseEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                String clickCmd = "right".equals(button) ? "rc:" : "c:";
                ProcessBuilder pb = new ProcessBuilder("cliclick", clickCmd + x + "," + y);
                pb.start().waitFor();
            } catch (Exception e) {
                log.debug("[computer-use] Click failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Type text.
     * Translated from typeText() in executor.ts
     */
    public CompletableFuture<Void> typeText(String text) {
        if (!computerUseService.isComputerUseEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("cliclick", "t:" + text);
                pb.start().waitFor();
            } catch (Exception e) {
                log.debug("[computer-use] Type text failed: {}", e.getMessage());
            }
        });
    }
}
