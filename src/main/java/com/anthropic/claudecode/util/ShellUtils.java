package com.anthropic.claudecode.util;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell utilities — shell detection and command execution.
 * Translated from src/utils/Shell.ts
 *
 * Provides:
 *   - {@link #findSuitableShell()} — discovers the best available POSIX shell.
 *   - {@link #exec(String, long)} — executes a shell command with timeout support.
 *   - {@link #setCwd(String)} — updates the working directory state for the session.
 *
 * The TypeScript original relies on Bun/Node primitives (spawn, memoize, etc.).
 * This translation uses standard {@link ProcessBuilder} / {@link Process} APIs and
 * Java 21 features (records, pattern matching).
 */
@Slf4j
public class ShellUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShellUtils.class);


    /** Default command timeout: 30 minutes, matching the TypeScript DEFAULT_TIMEOUT. */
    public static final long DEFAULT_TIMEOUT_MS = 30L * 60 * 1000;

    /** Maximum bytes read from stdout/stderr into memory. */
    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024; // 10 MB

    // -------------------------------------------------------------------------
    // Working-directory state
    // -------------------------------------------------------------------------

    private static final AtomicReference<String> currentCwd =
            new AtomicReference<>(System.getProperty("user.dir", "/"));

    /** Returns the current working directory tracked by this session. */
    public static String pwd() {
        return currentCwd.get();
    }

    /**
     * Set the current working directory for the session.
     * Translated from setCwd() in Shell.ts
     *
     * @param path    the new working directory (absolute or relative)
     * @param baseDir base directory used when {@code path} is relative (may be null)
     */
    public static void setCwd(String path, String baseDir) {
        if (path == null || path.isEmpty()) return;

        Path resolved;
        if (Paths.get(path).isAbsolute()) {
            resolved = Paths.get(path);
        } else {
            String base = baseDir != null ? baseDir : currentCwd.get();
            resolved = Paths.get(base).resolve(path);
        }

        Path real;
        try {
            real = resolved.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Path \"" + resolved + "\" does not exist", e);
        }
        currentCwd.set(real.toString());
    }

    /** Convenience overload — resolves relative paths against the current cwd. */
    public static void setCwd(String path) {
        setCwd(path, null);
    }

    // -------------------------------------------------------------------------
    // Shell detection
    // -------------------------------------------------------------------------

    /** Memoized shell path — computed once per JVM lifetime. */
    private static volatile String cachedShellPath = null;

    /**
     * Determines the best available POSIX shell.
     * Translated from findSuitableShell() in Shell.ts
     *
     * Priority:
     *   1. {@code CLAUDE_CODE_SHELL} env override (if bash/zsh and executable).
     *   2. {@code SHELL} env variable (if bash/zsh and executable).
     *   3. First executable shell found in well-known locations.
     *
     * @return absolute path to the selected shell
     * @throws IOException when no suitable shell is found
     */
    public static String findSuitableShell() throws IOException {
        if (cachedShellPath != null) return cachedShellPath;

        synchronized (ShellUtils.class) {
            if (cachedShellPath != null) return cachedShellPath;

            // 1. Explicit override
            String override = System.getenv("CLAUDE_CODE_SHELL");
            if (override != null && !override.isEmpty()) {
                boolean supported = override.contains("bash") || override.contains("zsh");
                if (supported && isExecutable(override)) {
                    log.debug("Using shell override: {}", override);
                    cachedShellPath = override;
                    return cachedShellPath;
                }
                log.debug("CLAUDE_CODE_SHELL=\"{}\" is not a valid bash/zsh path, falling back",
                        override);
            }

            // 2. User's preferred shell from $SHELL
            String envShell = System.getenv("SHELL");
            boolean envShellSupported = envShell != null
                    && (envShell.contains("bash") || envShell.contains("zsh"));
            boolean preferBash = envShell != null && envShell.contains("bash");

            if (envShellSupported && isExecutable(envShell)) {
                log.debug("Using $SHELL: {}", envShell);
                cachedShellPath = envShell;
                return cachedShellPath;
            }

            // 3. Search well-known locations
            String[] searchDirs = {"/bin", "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"};
            String[] shellOrder = preferBash
                    ? new String[]{"bash", "zsh"}
                    : new String[]{"zsh", "bash"};

            List<String> candidates = new ArrayList<>();
            for (String shell : shellOrder) {
                for (String dir : searchDirs) {
                    candidates.add(dir + "/" + shell);
                }
            }

            // Try `which` for each shell name as well
            for (String shell : shellOrder) {
                String found = whichShell(shell);
                if (found != null) {
                    candidates.add(0, found);
                }
            }

            String chosen = candidates.stream()
                    .filter(p -> p != null && !p.isEmpty() && isExecutable(p))
                    .findFirst()
                    .orElse(null);

            if (chosen == null) {
                String msg = "No suitable shell found. Claude CLI requires a POSIX shell. "
                        + "Please ensure you have a valid shell installed and SHELL is set.";
                throw new IOException(msg);
            }

            log.debug("Selected shell: {}", chosen);
            cachedShellPath = chosen;
            return cachedShellPath;
        }
    }

    // -------------------------------------------------------------------------
    // Command execution
    // -------------------------------------------------------------------------

    /**
     * Result of a shell command execution.
     * Translated from ExecResult in ShellCommand.ts (re-exported by Shell.ts)
     */
    @Data
    @Builder
    public static class ExecResult {
        private String stdout;
        private String stderr;
        private int code;
        private boolean interrupted;
        private String backgroundTaskId;
        private String preSpawnError;
    }

    /**
     * Execute a shell command and return its result.
     * Translated from exec() in Shell.ts
     *
     * @param command   the shell command string
     * @param timeoutMs timeout in milliseconds; use {@value #DEFAULT_TIMEOUT_MS} as default
     * @return the execution result
     */
    public static ExecResult exec(String command, long timeoutMs) {
        return exec(command, timeoutMs, false);
    }

    /**
     * Execute a shell command with optional abort.
     *
     * @param command   the shell command string
     * @param timeoutMs timeout in milliseconds
     * @param aborted   if {@code true}, the command is immediately aborted
     */
    public static ExecResult exec(String command, long timeoutMs, boolean aborted) {
        if (aborted) {
            return ExecResult.builder()
                    .code(145)
                    .stdout("")
                    .stderr("Command aborted before execution")
                    .interrupted(true)
                    .build();
        }

        String shell;
        try {
            shell = findSuitableShell();
        } catch (IOException e) {
            return ExecResult.builder()
                    .code(1)
                    .stdout("")
                    .stderr(e.getMessage())
                    .interrupted(false)
                    .preSpawnError(e.getMessage())
                    .build();
        }

        ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
        pb.directory(new File(currentCwd.get()));
        pb.environment().put("CLAUDECODE", "1");

        String shellEnv = System.getenv("SHELL");
        if (shellEnv != null) pb.environment().put("SHELL", shell);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            String msg = "Failed to spawn process: " + e.getMessage();
            log.debug(msg);
            return ExecResult.builder()
                    .code(126)
                    .stdout("")
                    .stderr(msg)
                    .interrupted(false)
                    .preSpawnError(msg)
                    .build();
        }

        // Read stdout and stderr concurrently to avoid blocking
        CompletableFuture<String> stdoutFuture =
                readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture =
                readStreamAsync(process.getErrorStream());

        boolean completed = false;
        boolean interrupted = false;
        try {
            completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted = true;
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
            log.debug("Could not fully read process output: {}", e.getMessage());
        }

        int exitCode = completed ? process.exitValue() : -1;
        return ExecResult.builder()
                .stdout(stdout)
                .stderr(stderr)
                .code(exitCode)
                .interrupted(interrupted)
                .build();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static boolean isExecutable(String path) {
        if (path == null || path.isEmpty()) return false;
        File f = new File(path);
        if (f.canExecute()) return true;
        // Fallback: try --version
        try {
            Process p = new ProcessBuilder(path, "--version")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static String whichShell(String shellName) {
        try {
            Process p = new ProcessBuilder("which", shellName)
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done || p.exitValue() != 0) return null;
            byte[] out = p.getInputStream().readAllBytes();
            String result = new String(out, StandardCharsets.UTF_8).trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    private static CompletableFuture<String> readStreamAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = stream.read(buffer)) != -1) {
                    int toWrite = Math.min(read, MAX_OUTPUT_BYTES - total);
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

    private ShellUtils() {}
}
