package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Process execution utilities.
 * Translated from src/utils/execFileNoThrow.ts
 *
 * Provides safe process execution that doesn't throw on non-zero exit codes.
 */
@Slf4j
public class ExecUtils {



    private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ExecResult {
        private String stdout;
        private String stderr;
        private int code;
        private String error;

        public String getStdout() { return stdout; }
        public void setStdout(String v) { stdout = v; }
        public String getStderr() { return stderr; }
        public void setStderr(String v) { stderr = v; }
        public int getCode() { return code; }
        public void setCode(int v) { code = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    
    }

    /**
     * Execute a file without throwing on non-zero exit.
     * Translated from execFileNoThrow() in execFileNoThrow.ts
     */
    public static CompletableFuture<ExecResult> execFileNoThrow(
            String file,
            List<String> args) {
        return execFileNoThrow(file, args, null, DEFAULT_TIMEOUT_MS);
    }

    public static CompletableFuture<ExecResult> execFileNoThrow(
            String file,
            List<String> args,
            String cwd,
            long timeoutMs) {

        return CompletableFuture.supplyAsync(() -> {
            List<String> command = new ArrayList<>();
            command.add(file);
            if (args != null) command.addAll(args);

            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                if (cwd != null) pb.directory(new File(cwd));
                pb.redirectErrorStream(false);

                Process process = pb.start();

                // Read stdout and stderr concurrently
                CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return new String(process.getInputStream().readAllBytes());
                    } catch (Exception e) {
                        return "";
                    }
                });

                CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return new String(process.getErrorStream().readAllBytes());
                    } catch (Exception e) {
                        return "";
                    }
                });

                boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new ExecResult("", "", -1, "Process timed out after " + timeoutMs + "ms");
                }

                String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
                String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
                int exitCode = process.exitValue();

                return new ExecResult(stdout, stderr, exitCode, null);

            } catch (Exception e) {
                return new ExecResult("", "", -1, e.getMessage());
            }
        });
    }

    /**
     * Execute synchronously.
     */
    public static ExecResult execSync(String file, List<String> args, String cwd) {
        try {
            return execFileNoThrow(file, args, cwd, DEFAULT_TIMEOUT_MS).get();
        } catch (Exception e) {
            return new ExecResult("", "", -1, e.getMessage());
        }
    }
}
