package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.*;

/**
 * Which command utilities.
 * Translated from src/utils/which.ts
 *
 * Finds the path to a command on the system.
 */
@Slf4j
public class WhichUtils {



    /**
     * Find the path to a command.
     * Translated from which() in which.ts
     */
    public static CompletableFuture<Optional<String>> which(String command) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String whichCmd = PlatformUtils.isWindows() ? "where.exe" : "which";
                ProcessBuilder pb = new ProcessBuilder(whichCmd, command);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                String output = new String(p.getInputStream().readAllBytes()).trim();
                boolean success = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;

                if (!success || output.isEmpty()) return Optional.empty();

                // On Windows, where.exe may return multiple paths
                String firstLine = output.split("[\r\n]+")[0].trim();
                return firstLine.isEmpty() ? Optional.empty() : Optional.of(firstLine);

            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Synchronous version.
     */
    public static Optional<String> whichSync(String command) {
        try {
            return which(command).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private WhichUtils() {}
}
