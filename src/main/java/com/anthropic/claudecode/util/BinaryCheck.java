package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * Binary availability check utilities.
 * Translated from src/utils/binaryCheck.ts
 */
@Slf4j
@Component
public class BinaryCheck {



    private static final Map<String, Boolean> binaryCache = new ConcurrentHashMap<>();

    /**
     * Check if a binary is installed.
     * Translated from isBinaryInstalled() in binaryCheck.ts
     */
    public static CompletableFuture<Boolean> isBinaryInstalled(String command) {
        if (command == null || command.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }

        String trimmed = command.trim();
        Boolean cached = binaryCache.get(trimmed);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return CompletableFuture.supplyAsync(() -> {
            boolean exists = checkBinaryExists(trimmed);
            binaryCache.put(trimmed, exists);
            return exists;
        });
    }

    private static boolean checkBinaryExists(String command) {
        try {
            String whichCmd = PlatformUtils.isWindows() ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(whichCmd, command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private BinaryCheck() {}
}
