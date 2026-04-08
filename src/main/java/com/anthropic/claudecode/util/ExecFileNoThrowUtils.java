package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Utility for executing external processes without throwing exceptions.
 * Always resolves (never throws) — callers inspect the exit code.
 *
 * Translated from src/utils/execFileNoThrow.ts
 *
 * Provides cross-platform process execution wrappers, mirroring the
 * behaviour of execa with reject:false. On Windows the JVM's ProcessBuilder
 * handles .bat/.cmd lookup via the PATH, so no extra shell-escaping layer is
 * needed here.
 */
@Slf4j
public final class ExecFileNoThrowUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExecFileNoThrowUtils.class);


    /** Default timeout: 10 minutes in milliseconds (mirrors TS constant). */
    public static final int DEFAULT_TIMEOUT_MS = 10 * 60 * 1_000;

    /** Default max-buffer size in bytes (1 MB). */
    public static final int DEFAULT_MAX_BUFFER = 1_000_000;

    private ExecFileNoThrowUtils() {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Result of a process execution.
     * Mirrors the {@code { stdout, stderr, code, error? }} shape from TS.
     */
    public record ExecResult(
            String stdout,
            String stderr,
            int code,
            String error   // null when the process succeeded (code == 0)
    ) {
        /** Convenience: true when code == 0. */
        public boolean isSuccess() { return code == 0; }
    }

    /**
     * Options for {@link #execFileNoThrow}.
     * All fields optional — use {@link ExecOptions#defaults()} for defaults.
     */
    public static final class ExecOptions {
        public Integer timeoutMs;
        public boolean preserveOutputOnError = true;
        /** Working directory. Null → inherit parent CWD. */
        public String cwd;
        public Map<String, String> env;
        public Integer maxBuffer;

        public static ExecOptions defaults() {
            ExecOptions o = new ExecOptions();
            o.timeoutMs = DEFAULT_TIMEOUT_MS;
            o.preserveOutputOnError = true;
            o.maxBuffer = DEFAULT_MAX_BUFFER;
            return o;
        }
    }

    /**
     * Executes {@code file} with {@code args}, returning a future that always
     * resolves.  On non-zero exit or process error the future resolves with a
     * non-zero code and an error message rather than throwing.
     *
     * Mirrors execFileNoThrow() / execFileNoThrowWithCwd() in execFileNoThrow.ts
     */
    public static CompletableFuture<ExecResult> execFileNoThrow(
            String file,
            List<String> args) {
        return execFileNoThrow(file, args, ExecOptions.defaults());
    }

    /**
     * Overload accepting explicit options.
     */
    public static CompletableFuture<ExecResult> execFileNoThrow(
            String file,
            List<String> args,
            ExecOptions options) {

        ExecOptions opts = options != null ? options : ExecOptions.defaults();
        int timeoutMs = opts.timeoutMs != null ? opts.timeoutMs : DEFAULT_TIMEOUT_MS;
        int maxBuffer = opts.maxBuffer != null ? opts.maxBuffer : DEFAULT_MAX_BUFFER;

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> command = new ArrayList<>();
                command.add(file);
                command.addAll(args);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(false);

                if (opts.cwd != null) {
                    pb.directory(new File(opts.cwd));
                }

                if (opts.env != null) {
                    pb.environment().putAll(opts.env);
                }

                Process process = pb.start();

                // Drain stdout and stderr concurrently to prevent blocking
                Future<String> stdoutFuture = drainStream(process.getInputStream(), maxBuffer);
                Future<String> stderrFuture = drainStream(process.getErrorStream(), maxBuffer);

                boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    String stdoutSoFar = getOrEmpty(stdoutFuture);
                    String stderrSoFar = getOrEmpty(stderrFuture);
                    if (opts.preserveOutputOnError) {
                        return new ExecResult(stdoutSoFar, stderrSoFar, 1, "Process timed out after " + timeoutMs + "ms");
                    }
                    return new ExecResult("", "", 1, "Process timed out after " + timeoutMs + "ms");
                }

                int exitCode = process.exitValue();
                String stdout = getOrEmpty(stdoutFuture);
                String stderr = getOrEmpty(stderrFuture);

                if (exitCode != 0) {
                    String errorMsg = buildErrorMessage(exitCode, stderr);
                    if (opts.preserveOutputOnError) {
                        return new ExecResult(stdout, stderr, exitCode, errorMsg);
                    }
                    return new ExecResult("", "", exitCode, errorMsg);
                }

                return new ExecResult(stdout, stderr, 0, null);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("execFileNoThrow interrupted for command: {} {}", file, args, e);
                return new ExecResult("", "", 1, "Interrupted: " + e.getMessage());
            } catch (IOException e) {
                log.error("execFileNoThrow IO error for command: {} {}", file, args, e);
                return new ExecResult("", "", 1, e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Drains an InputStream into a String, capped at {@code maxBytes}.
     * Mirrors execa's maxBuffer option.
     */
    private static Future<String> drainStream(InputStream stream, int maxBytes) {
        return ForkJoinPool.commonPool().submit(() -> {
            try {
                byte[] buffer = new byte[8192];
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int total = 0;
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int toWrite = Math.min(read, maxBytes - total);
                    if (toWrite > 0) {
                        baos.write(buffer, 0, toWrite);
                        total += toWrite;
                    }
                    if (total >= maxBytes) break;
                }
                return baos.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String getOrEmpty(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Builds a human-readable error message from an exit code and stderr.
     * Mirrors getErrorMessage() priority: stderr content first, then exit code.
     */
    private static String buildErrorMessage(int exitCode, String stderr) {
        if (stderr != null && !stderr.isBlank()) {
            String firstLine = stderr.lines().findFirst().orElse("").trim();
            if (!firstLine.isEmpty()) {
                return "Command failed with exit code " + exitCode + ": " + firstLine;
            }
        }
        return String.valueOf(exitCode);
    }
}
