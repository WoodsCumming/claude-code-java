package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Platform-agnostic process utilities — stdout/stderr helpers, process inspection,
 * ancestor/child PID traversal.
 * Translated from src/utils/genericProcessUtils.ts (and src/utils/process.ts)
 *
 * <p>When adding new code here, ensure correct behaviour on:</p>
 * <ul>
 *   <li>Win32: {@code ps} inside Cygwin/WSL may not reach host processes.</li>
 *   <li>Unix vs BSD {@code ps}: options differ (use POSIX-portable variants).</li>
 * </ul>
 */
@Slf4j
public final class ProcessUtils {



    // =========================================================================
    // Output helpers (process.ts)
    // =========================================================================

    /**
     * Write data to stdout, silently skipping if the stream is in an error state.
     * Translated from {@code writeToStdout()} in process.ts.
     */
    public static void writeToStdout(String data) {
        writeOut(System.out, data);
    }

    /**
     * Write data to stderr, silently skipping if the stream is in an error state.
     * Translated from {@code writeToStderr()} in process.ts.
     */
    public static void writeToStderr(String data) {
        writeOut(System.err, data);
    }

    /**
     * Write an error message to stderr and terminate the JVM with exit code 1.
     * Translated from {@code exitWithError()} in process.ts.
     */
    public static void exitWithError(String message) {
        System.err.println(message);
        System.exit(1);
    }

    /**
     * Peeks at stdin for incoming data with a timeout.
     *
     * <p>Returns a {@link CompletableFuture} that resolves to {@code true} when the
     * timeout expired before any data arrived (stdin looks idle), or {@code false}
     * once data appears on stdin (a real producer exists).</p>
     *
     * <p>Used by {@code -p} mode to distinguish a real pipe from an inherited-but-idle
     * parent stdin.</p>
     * Translated from {@code peekForStdinData()} in process.ts.
     */
    public static CompletableFuture<Boolean> peekForStdinData(long timeoutMs) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < deadline) {
                    if (System.in.available() > 0) {
                        future.complete(false); // real producer
                        return;
                    }
                    //noinspection BusyWait
                    Thread.sleep(10);
                }
                future.complete(true); // timed out — stdin looks idle
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.complete(true);
            } catch (IOException e) {
                future.complete(true);
            }
        }, "stdin-peek");
        reader.setDaemon(true);
        reader.start();
        return future;
    }

    // =========================================================================
    // isProcessRunning
    // =========================================================================

    /**
     * Returns {@code true} when a process with {@code pid} is currently running.
     *
     * <p>PID ≤ 1 always returns {@code false} (0 = current process group, 1 = init).</p>
     *
     * <p>Note: unlike the Node {@code process.kill(pid, 0)} probe, the JDK's
     * {@link ProcessHandle} API does not give EPERM for processes owned by other users
     * — it simply returns an empty optional. This is conservative for lock recovery.</p>
     * Translated from {@code isProcessRunning()} in genericProcessUtils.ts.
     */
    public static boolean isProcessRunning(long pid) {
        if (pid <= 1) return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    // =========================================================================
    // getAncestorPidsAsync
    // =========================================================================

    /**
     * Returns the ancestor PID chain for a process, from immediate parent up to
     * {@code maxDepth} levels.
     * Translated from {@code getAncestorPidsAsync()} in genericProcessUtils.ts.
     */
    public static CompletableFuture<List<Long>> getAncestorPidsAsync(long pid, int maxDepth) {
        if (isWindows()) {
            return getAncestorPidsWindows(pid, maxDepth);
        }
        return getAncestorPidsUnix(pid, maxDepth);
    }

    /** Overload with default maxDepth = 10. */
    public static CompletableFuture<List<Long>> getAncestorPidsAsync(long pid) {
        return getAncestorPidsAsync(pid, 10);
    }

    // =========================================================================
    // getProcessCommand  (deprecated)
    // =========================================================================

    /**
     * Returns the command-line string for a given process, or {@code null} if not found.
     *
     * @deprecated Prefer {@link #getAncestorCommandsAsync(long, int)}.
     * Translated from {@code getProcessCommand()} in genericProcessUtils.ts.
     */
    @Deprecated
    public static String getProcessCommand(long pid) {
        // Use ProcessHandle API where available — avoids spawning a child process
        return ProcessHandle.of(pid)
                .flatMap(ph -> ph.info().command())
                .orElseGet(() -> {
                    try {
                        return getProcessCommandViaShell(pid);
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    // =========================================================================
    // getAncestorCommandsAsync
    // =========================================================================

    /**
     * Returns the command-line strings for a process and its ancestors in a single call.
     * Translated from {@code getAncestorCommandsAsync()} in genericProcessUtils.ts.
     */
    public static CompletableFuture<List<String>> getAncestorCommandsAsync(
            long pid, int maxDepth) {
        if (isWindows()) {
            return getAncestorCommandsWindows(pid, maxDepth);
        }
        return getAncestorCommandsUnix(pid, maxDepth);
    }

    /** Overload with default maxDepth = 10. */
    public static CompletableFuture<List<String>> getAncestorCommandsAsync(long pid) {
        return getAncestorCommandsAsync(pid, 10);
    }

    // =========================================================================
    // getChildPids
    // =========================================================================

    /**
     * Returns the immediate child PIDs of the given process.
     * Translated from {@code getChildPids()} in genericProcessUtils.ts.
     */
    public static List<Long> getChildPids(long pid) {
        // Prefer the pure-Java ProcessHandle API
        Optional<ProcessHandle> parent = ProcessHandle.of(pid);
        if (parent.isPresent()) {
            return parent.get().children()
                    .map(ProcessHandle::pid)
                    .toList();
        }

        // Fallback: shell command
        try {
            String command = isWindows()
                    ? "powershell.exe -NoProfile -Command \"(Get-CimInstance Win32_Process "
                      + "-Filter \\\"ParentProcessId=" + pid + "\\\").ProcessId\""
                    : "pgrep -P " + pid;

            String output = execSync(command, 1000);
            if (output == null || output.isBlank()) return List.of();

            List<Long> result = new ArrayList<>();
            for (String line : output.trim().split("[\n,]")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    try { result.add(Long.parseLong(trimmed)); } catch (NumberFormatException ignored) {}
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    // =========================================================================
    // getCurrentPid
    // =========================================================================

    /** Returns the current process ID. */
    public static long getCurrentPid() {
        return ProcessHandle.current().pid();
    }

    // =========================================================================
    // getAncestorPids (synchronous variant — uses ProcessHandle)
    // =========================================================================

    /**
     * Synchronous ancestor PID walk using the {@link ProcessHandle} API.
     * Useful when a blocking call is acceptable.
     */
    public static List<Long> getAncestorPids(long pid, int maxDepth) {
        List<Long> ancestors = new ArrayList<>();
        Optional<ProcessHandle> current = ProcessHandle.of(pid);
        int depth = 0;
        while (current.isPresent() && depth < maxDepth) {
            Optional<ProcessHandle> parent = current.get().parent();
            if (parent.isEmpty()) break;
            long parentPid = parent.get().pid();
            ancestors.add(parentPid);
            current = parent;
            depth++;
        }
        return ancestors;
    }

    // =========================================================================
    // Private — Windows implementations
    // =========================================================================

    private static CompletableFuture<List<Long>> getAncestorPidsWindows(long pid, int maxDepth) {
        return CompletableFuture.supplyAsync(() -> {
            String script = """
                    $pid = %d
                    $ancestors = @()
                    for ($i = 0; $i -lt %d; $i++) {
                        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$pid" -ErrorAction SilentlyContinue
                        if (-not $proc -or -not $proc.ParentProcessId -or $proc.ParentProcessId -eq 0) { break }
                        $pid = $proc.ParentProcessId
                        $ancestors += $pid
                    }
                    $ancestors -join ','
                    """.formatted(pid, maxDepth).trim();

            String output = execCmd(new String[]{"powershell.exe", "-NoProfile", "-Command", script}, 3000);
            if (output == null || output.isBlank()) return List.of();

            List<Long> result = new ArrayList<>();
            for (String p : output.trim().split(",")) {
                try { result.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) {}
            }
            return result;
        });
    }

    private static CompletableFuture<List<String>> getAncestorCommandsWindows(long pid, int maxDepth) {
        return CompletableFuture.supplyAsync(() -> {
            String script = """
                    $currentPid = %d
                    $commands = @()
                    for ($i = 0; $i -lt %d; $i++) {
                        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$currentPid" -ErrorAction SilentlyContinue
                        if (-not $proc) { break }
                        if ($proc.CommandLine) { $commands += $proc.CommandLine }
                        if (-not $proc.ParentProcessId -or $proc.ParentProcessId -eq 0) { break }
                        $currentPid = $proc.ParentProcessId
                    }
                    $commands -join [char]0
                    """.formatted(pid, maxDepth).trim();

            String output = execCmd(new String[]{"powershell.exe", "-NoProfile", "-Command", script}, 3000);
            if (output == null || output.isBlank()) return List.of();

            List<String> result = new ArrayList<>();
            for (String s : output.split("\0")) {
                if (!s.isBlank()) result.add(s);
            }
            return result;
        });
    }

    // =========================================================================
    // Private — Unix implementations
    // =========================================================================

    private static CompletableFuture<List<Long>> getAncestorPidsUnix(long pid, int maxDepth) {
        return CompletableFuture.supplyAsync(() -> {
            String script = "pid=%d; for i in $(seq 1 %d); do ppid=$(ps -o ppid= -p $pid 2>/dev/null | tr -d ' '); "
                    + "if [ -z \"$ppid\" ] || [ \"$ppid\" = \"0\" ] || [ \"$ppid\" = \"1\" ]; then break; fi; "
                    + "echo $ppid; pid=$ppid; done"
                    .formatted(pid, maxDepth);

            String output = execCmd(new String[]{"sh", "-c", script}, 3000);
            if (output == null || output.isBlank()) return List.of();

            List<Long> result = new ArrayList<>();
            for (String line : output.trim().split("\n")) {
                try { result.add(Long.parseLong(line.trim())); } catch (NumberFormatException ignored) {}
            }
            return result;
        });
    }

    private static CompletableFuture<List<String>> getAncestorCommandsUnix(long pid, int maxDepth) {
        return CompletableFuture.supplyAsync(() -> {
            // null-byte separator to handle newlines in command strings
            String script = ("currentpid=%d; for i in $(seq 1 %d); do cmd=$(ps -o command= -p $currentpid 2>/dev/null); "
                    + "if [ -n \"$cmd\" ]; then printf '%%s\\0' \"$cmd\"; fi; "
                    + "ppid=$(ps -o ppid= -p $currentpid 2>/dev/null | tr -d ' '); "
                    + "if [ -z \"$ppid\" ] || [ \"$ppid\" = \"0\" ] || [ \"$ppid\" = \"1\" ]; then break; fi; "
                    + "currentpid=$ppid; done").formatted(pid, maxDepth);

            String output = execCmd(new String[]{"sh", "-c", script}, 3000);
            if (output == null || output.isBlank()) return List.of();

            List<String> result = new ArrayList<>();
            for (String s : output.split("\0")) {
                if (!s.isBlank()) result.add(s);
            }
            return result;
        });
    }

    // =========================================================================
    // Private — shell helpers
    // =========================================================================

    private static String getProcessCommandViaShell(long pid) {
        String command = isWindows()
                ? "powershell.exe -NoProfile -Command \"(Get-CimInstance Win32_Process "
                  + "-Filter \\\"ProcessId=" + pid + "\\\").CommandLine\""
                : "ps -o command= -p " + pid;
        String result = execSync(command, 1000);
        return result != null ? result.trim() : null;
    }

    /** Execute a shell command string synchronously (blocking). */
    private static String execSync(String command, long timeoutMs) {
        try {
            String[] args = isWindows()
                    ? new String[]{"cmd.exe", "/c", command}
                    : new String[]{"sh", "-c", command};
            return execCmd(args, timeoutMs);
        } catch (Exception e) {
            return null;
        }
    }

    /** Execute a command array synchronously (blocking) and return stdout as a string. */
    private static String execCmd(String[] args, long timeoutMs) {
        try {
            Process process = new ProcessBuilder(args)
                    .redirectErrorStream(false)
                    .start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;
            return new String(process.getInputStream().readAllBytes());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            log.trace("execCmd failed: {}", e.getMessage());
            return null;
        }
    }

    /** Write to a PrintStream, swallowing any write error (EPIPE). */
    private static void writeOut(PrintStream stream, String data) {
        try {
            stream.print(data);
        } catch (Exception e) {
            log.trace("Swallowed write error (likely EPIPE): {}", e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private ProcessUtils() {}
}
