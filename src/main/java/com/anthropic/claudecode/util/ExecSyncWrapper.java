package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper for synchronous command execution with slow-operation logging.
 *
 * @deprecated Use async alternatives when possible. Sync exec calls block the thread.
 *             Translated from src/utils/execSyncWrapper.ts
 */
@Slf4j
@Deprecated
public class ExecSyncWrapper {



    private static final long SLOW_OPERATION_THRESHOLD_MS = 500;

    /**
     * Execute a shell command synchronously and return its output as a string.
     * Logs a warning if the operation takes longer than the slow-operation threshold.
     *
     * Translated from execSync_DEPRECATED() in execSyncWrapper.ts
     *
     * @param command the shell command to execute
     * @return the stdout output of the command
     * @throws RuntimeException if the command fails or times out
     */
    public static String execSyncDeprecated(String command) {
        return execSyncDeprecated(command, null, 60);
    }

    /**
     * Execute a shell command synchronously with a working directory.
     *
     * @param command    the shell command to execute
     * @param workingDir optional working directory (null to use current dir)
     * @param timeoutSec timeout in seconds
     * @return the stdout output of the command
     */
    public static String execSyncDeprecated(String command, String workingDir, long timeoutSec) {
        String preview = command.length() > 100 ? command.substring(0, 100) : command;
        long startMs = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            if (workingDir != null) {
                pb.directory(new java.io.File(workingDir));
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Command timed out after " + timeoutSec + "s: " + preview);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Command failed (exit " + exitCode + "): " + preview);
            }

            return output.toString();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("execSync failed for: " + preview, e);
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            if (durationMs > SLOW_OPERATION_THRESHOLD_MS) {
                log.warn("Slow execSync ({}ms): {}", durationMs, preview);
            }
        }
    }

    private ExecSyncWrapper() {}
}
