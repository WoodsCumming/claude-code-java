package com.anthropic.claudecode.util;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shell command utilities — wrapping a child process for flexible execution.
 * Translated from src/utils/ShellCommand.ts
 *
 * Provides:
 *   - {@link ExecResult} — the result of a finished shell command.
 *   - {@link ShellCommand} — sealed interface modelling a running/completed command.
 *   - {@link #wrapProcess(Process, long, boolean)} — wraps a {@link Process} into
 *     a {@link ShellCommand}.
 *   - {@link #createAbortedCommand()} / {@link #createFailedCommand(String)} —
 *     static factories for pre-resolved commands.
 *
 * TypeScript union types ({@code status}) are modelled with a {@code StatusValue} enum.
 * The TypeScript class hierarchy (ShellCommandImpl, AbortedShellCommand) is collapsed
 * into a sealed interface with two implementations.
 */
@Slf4j
public class ShellCommandUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShellCommandUtils.class);


    // Mirrors the TypeScript constants
    private static final int SIGKILL_CODE = 137;
    private static final int SIGTERM_CODE = 143;
    private static final int ABORTED_CODE = 145;

    // -------------------------------------------------------------------------
    // Result record
    // -------------------------------------------------------------------------

    /**
     * Result of a completed shell command.
     * Translated from ExecResult in ShellCommand.ts
     */
    @Data
    public static class ExecResult {
        private String stdout;
        private String stderr;
        private int code;
        private boolean interrupted;
        private String backgroundTaskId;
        private boolean backgroundedByUser;
        private boolean assistantAutoBackgrounded;
        /** Set when stdout was too large to fit inline — path to the overflow file. */
        private String outputFilePath;
        private Long outputFileSize;
        private String outputTaskId;
        /** Error message when the command failed before spawning (e.g., deleted cwd). */
        private String preSpawnError;
    
        public int getCode() { return code; }
    
        public static ExecResultBuilder builder() { return new ExecResultBuilder(); }
        public static class ExecResultBuilder {
            private String stdout;
            private String stderr;
            private int code;
            private boolean interrupted;
            private String backgroundTaskId;
            private boolean backgroundedByUser;
            private boolean assistantAutoBackgrounded;
            private String outputFilePath;
            private Long outputFileSize;
            private String outputTaskId;
            private String preSpawnError;
            public ExecResultBuilder stdout(String v) { this.stdout = v; return this; }
            public ExecResultBuilder stderr(String v) { this.stderr = v; return this; }
            public ExecResultBuilder code(int v) { this.code = v; return this; }
            public ExecResultBuilder interrupted(boolean v) { this.interrupted = v; return this; }
            public ExecResultBuilder backgroundTaskId(String v) { this.backgroundTaskId = v; return this; }
            public ExecResultBuilder backgroundedByUser(boolean v) { this.backgroundedByUser = v; return this; }
            public ExecResultBuilder assistantAutoBackgrounded(boolean v) { this.assistantAutoBackgrounded = v; return this; }
            public ExecResultBuilder outputFilePath(String v) { this.outputFilePath = v; return this; }
            public ExecResultBuilder outputFileSize(Long v) { this.outputFileSize = v; return this; }
            public ExecResultBuilder outputTaskId(String v) { this.outputTaskId = v; return this; }
            public ExecResultBuilder preSpawnError(String v) { this.preSpawnError = v; return this; }
            public ExecResult build() {
                ExecResult o = new ExecResult();
                o.stdout = stdout;
                o.stderr = stderr;
                o.code = code;
                o.interrupted = interrupted;
                o.backgroundTaskId = backgroundTaskId;
                o.backgroundedByUser = backgroundedByUser;
                o.assistantAutoBackgrounded = assistantAutoBackgrounded;
                o.outputFilePath = outputFilePath;
                o.outputFileSize = outputFileSize;
                o.outputTaskId = outputTaskId;
                o.preSpawnError = preSpawnError;
                return o;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Status enum  (replaces the TypeScript string literal union)
    // -------------------------------------------------------------------------

    /** Runtime status of a {@link ShellCommand}. */
    public enum StatusValue {
        RUNNING, BACKGROUNDED, COMPLETED, KILLED
    }

    // -------------------------------------------------------------------------
    // ShellCommand sealed interface
    // -------------------------------------------------------------------------

    /**
     * Represents a running or completed shell command.
     * Translated from the ShellCommand type in ShellCommand.ts
     */
    public sealed interface ShellCommand
            permits ShellCommandUtils.LiveShellCommand,
                    ShellCommandUtils.ResolvedShellCommand {

        /** Move the command to the background, returning {@code true} on success. */
        boolean background(String backgroundTaskId);

        /** Returns the future result of this command. */
        CompletableFuture<ExecResult> getResult();

        /** Send SIGKILL to the process. */
        void kill();

        /** Current status. */
        StatusValue getStatus();

        /** Release stream resources to prevent memory leaks. */
        void cleanup();
    }

    // -------------------------------------------------------------------------
    // Live implementation — wraps a real Process
    // -------------------------------------------------------------------------

    /**
     * A running shell command backed by a {@link Process}.
     * Translated from ShellCommandImpl in ShellCommand.ts
     */
    public static final class LiveShellCommand implements ShellCommand {

        private final Process process;
        private final CompletableFuture<ExecResult> resultFuture;
        private volatile StatusValue status = StatusValue.RUNNING;
        private volatile String backgroundTaskId;
        private final long timeoutMs;

        private LiveShellCommand(Process process, long timeoutMs, boolean shouldAutoBackground) {
            this.process = process;
            this.timeoutMs = timeoutMs;
            this.resultFuture = buildResultFuture(shouldAutoBackground);
        }

        private CompletableFuture<ExecResult> buildResultFuture(boolean shouldAutoBackground) {
            return CompletableFuture.supplyAsync(() -> {
                boolean completed;
                boolean interrupted = false;
                try {
                    completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    completed = false;
                    interrupted = true;
                }

                if (!completed) {
                    if (shouldAutoBackground && status == StatusValue.RUNNING) {
                        // Auto-background on timeout
                        status = StatusValue.BACKGROUNDED;
                    } else {
                        process.destroyForcibly();
                        interrupted = true;
                        if (status == StatusValue.RUNNING) status = StatusValue.KILLED;
                    }
                } else if (status == StatusValue.RUNNING || status == StatusValue.BACKGROUNDED) {
                    status = StatusValue.COMPLETED;
                }

                String stdout = readStream(process.getInputStream());
                String stderr = readStream(process.getErrorStream());
                int code = completed ? process.exitValue() : (interrupted ? SIGKILL_CODE : -1);

                ExecResult.ExecResultBuilder builder = ExecResult.builder()
                        .stdout(stdout)
                        .stderr(stderr)
                        .code(code)
                        .interrupted(interrupted)
                        .backgroundTaskId(backgroundTaskId);

                if (code == SIGTERM_CODE && !interrupted) {
                    builder.stderr("Command timed out" + (stderr.isEmpty() ? "" : " " + stderr));
                }

                return builder.build();
            });
        }

        @Override
        public boolean background(String taskId) {
            if (status == StatusValue.RUNNING) {
                this.backgroundTaskId = taskId;
                this.status = StatusValue.BACKGROUNDED;
                return true;
            }
            return false;
        }

        @Override
        public CompletableFuture<ExecResult> getResult() {
            return resultFuture;
        }

        @Override
        public void kill() {
            status = StatusValue.KILLED;
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        @Override
        public StatusValue getStatus() {
            return status;
        }

        @Override
        public void cleanup() {
            // Release streams
            try { process.getInputStream().close(); } catch (Exception ignored) {}
            try { process.getErrorStream().close(); } catch (Exception ignored) {}
            try { process.getOutputStream().close(); } catch (Exception ignored) {}
        }

        private String readStream(InputStream stream) {
            if (stream == null) return "";
            try {
                return new String(stream.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pre-resolved implementation — no underlying process
    // -------------------------------------------------------------------------

    /**
     * A shell command that was never started (aborted or failed pre-spawn).
     * Translated from AbortedShellCommand / createFailedCommand in ShellCommand.ts
     */
    public static final class ResolvedShellCommand implements ShellCommand {

        private final ExecResult result;

        private ResolvedShellCommand(ExecResult result) {
            this.result = result;
        }

        @Override
        public boolean background(String backgroundTaskId) {
            return false;
        }

        @Override
        public CompletableFuture<ExecResult> getResult() {
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void kill() {}

        @Override
        public StatusValue getStatus() {
            return result.getCode() == ABORTED_CODE ? StatusValue.KILLED : StatusValue.COMPLETED;
        }

        @Override
        public void cleanup() {}
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Wrap a running {@link Process} in a {@link ShellCommand}.
     * Translated from wrapSpawn() in ShellCommand.ts
     *
     * @param process            the child process
     * @param timeoutMs          command timeout in milliseconds
     * @param shouldAutoBackground auto-background on timeout instead of killing
     */
    public static ShellCommand wrapProcess(
            Process process,
            long timeoutMs,
            boolean shouldAutoBackground
    ) {
        return new LiveShellCommand(process, timeoutMs, shouldAutoBackground);
    }

    /** Overload with auto-background defaulting to {@code false}. */
    public static ShellCommand wrapProcess(Process process, long timeoutMs) {
        return wrapProcess(process, timeoutMs, false);
    }

    /**
     * Create a command that was aborted before execution.
     * Translated from createAbortedCommand() in ShellCommand.ts
     */
    public static ShellCommand createAbortedCommand() {
        return createAbortedCommand(null, null);
    }

    /**
     * Create a command that was aborted before execution, with optional details.
     *
     * @param backgroundTaskId optional background task ID
     * @param stderr           optional error message
     */
    public static ShellCommand createAbortedCommand(String backgroundTaskId, String stderr) {
        ExecResult result = ExecResult.builder()
                .code(ABORTED_CODE)
                .stdout("")
                .stderr(stderr != null ? stderr : "Command aborted before execution")
                .interrupted(true)
                .backgroundTaskId(backgroundTaskId)
                .build();
        return new ResolvedShellCommand(result);
    }

    /**
     * Create a command that failed before the process was spawned.
     * Translated from createFailedCommand() in ShellCommand.ts
     *
     * @param preSpawnError human-readable description of the failure
     */
    public static ShellCommand createFailedCommand(String preSpawnError) {
        ExecResult result = ExecResult.builder()
                .code(1)
                .stdout("")
                .stderr(preSpawnError != null ? preSpawnError : "")
                .interrupted(false)
                .preSpawnError(preSpawnError)
                .build();
        return new ResolvedShellCommand(result);
    }

    private ShellCommandUtils() {}
}
