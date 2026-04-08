package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import lombok.Data;

/**
 * Shell command execution service.
 * Translated from src/utils/Shell.ts
 *
 * Executes bash commands with timeout support.
 */
@Slf4j
@Service
public class ShellService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShellService.class);


    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024; // 10MB

    /**
     * Execute a shell command.
     * Translated from exec() in Shell.ts
     */
    /** Execute a command in a specified directory. */
    public ExecResult executeInDir(String command, String dir, long timeoutMs) {
        try {
            String shell = getShell();
            ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
            pb.directory(new java.io.File(dir));
            pb.environment().put("TERM", "xterm-256color");
            Process proc = pb.start();
            boolean done = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
                return ExecResult.builder().stdout("").stderr("Timeout").exitCode(1).interrupted(true).build();
            }
            String stdout = new String(proc.getInputStream().readAllBytes());
            String stderr = new String(proc.getErrorStream().readAllBytes());
            return ExecResult.builder().stdout(stdout).stderr(stderr).exitCode(proc.exitValue()).interrupted(false).build();
        } catch (Exception e) {
            return ExecResult.builder().stdout("").stderr(e.getMessage()).exitCode(1).interrupted(false).build();
        }
    }

    public ExecResult execute(String command, long timeoutMs, boolean aborted) throws Exception {
        if (aborted) {
            throw new InterruptedException("Operation aborted before execution");
        }

        String shell = getShell();
        ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
        pb.environment().put("TERM", "xterm-256color");

        // Inherit current directory
        String cwd = System.getProperty("user.dir");
        if (cwd != null) {
            pb.directory(new File(cwd));
        }

        Process process = pb.start();

        // Read stdout and stderr concurrently
        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream(), MAX_OUTPUT_BYTES);
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream(), MAX_OUTPUT_BYTES);

        boolean completed;
        boolean interrupted = false;

        try {
            completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            interrupted = true;
            completed = false;
            Thread.currentThread().interrupt();
        }

        if (!completed) {
            process.destroyForcibly();
            interrupted = true;
        }

        String stdout = "";
        String stderr = "";
        try {
            stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Could not read process output: {}", e.getMessage());
        }

        int exitCode = completed ? process.exitValue() : -1;

        return ExecResult.builder()
            .stdout(stdout)
            .stderr(stderr)
            .exitCode(exitCode)
            .interrupted(interrupted)
            .build();
    }

    /**
     * Execute a PowerShell command.
     * Delegates to PowerShell or pwsh if available.
     */
    public ExecResult executePowerShell(String command, long timeoutMs) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] psArgs;
        if (os.contains("win")) {
            psArgs = new String[]{"powershell.exe", "-NonInteractive", "-Command", command};
        } else {
            psArgs = new String[]{"pwsh", "-NonInteractive", "-Command", command};
        }

        ProcessBuilder pb = new ProcessBuilder(psArgs);
        String cwd = System.getProperty("user.dir");
        if (cwd != null) pb.directory(new File(cwd));

        Process process = pb.start();
        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream(), MAX_OUTPUT_BYTES);
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream(), MAX_OUTPUT_BYTES);

        boolean completed;
        boolean interrupted = false;
        try {
            completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            interrupted = true;
            completed = false;
            Thread.currentThread().interrupt();
        }
        if (!completed) {
            process.destroyForcibly();
            interrupted = true;
        }

        String stdout = "";
        String stderr = "";
        try {
            stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Could not read PowerShell output: {}", e.getMessage());
        }

        return ExecResult.builder()
            .stdout(stdout)
            .stderr(stderr)
            .exitCode(completed ? process.exitValue() : -1)
            .interrupted(interrupted)
            .build();
    }

    private CompletableFuture<String> readStreamAsync(InputStream stream, int maxBytes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;

                while ((read = stream.read(buffer)) != -1) {
                    int toWrite = Math.min(read, maxBytes - total);
                    if (toWrite <= 0) break;
                    baos.write(buffer, 0, toWrite);
                    total += toWrite;
                }

                return baos.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private String getShell() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isEmpty()) return shell;

        // Default shells by OS
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "cmd.exe";
        return "/bin/bash";
    }

    @Data
    @lombok.Builder
    
    public static class ExecResult {
        private String stdout;
        private String stderr;
        private int exitCode;
        private boolean interrupted;

        public ExecResult() {}
        public String getStdout() { return stdout; }
        public void setStdout(String v) { stdout = v; }
        public String getStderr() { return stderr; }
        public void setStderr(String v) { stderr = v; }
        public int getExitCode() { return exitCode; }
        public void setExitCode(int v) { exitCode = v; }
        public boolean isInterrupted() { return interrupted; }
        public void setInterrupted(boolean v) { interrupted = v; }

        public static ExecResultBuilder builder() { return new ExecResultBuilder(); }
        public static class ExecResultBuilder {
            private final ExecResult r = new ExecResult();
            public ExecResultBuilder stdout(String v) { r.stdout = v; return this; }
            public ExecResultBuilder stderr(String v) { r.stderr = v; return this; }
            public ExecResultBuilder exitCode(int v) { r.exitCode = v; return this; }
            public ExecResultBuilder interrupted(boolean v) { r.interrupted = v; return this; }
            public ExecResult build() { return r; }
        }
    }
}
