package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Ripgrep (rg) utilities.
 * Translated from src/utils/ripgrep.ts
 *
 * Provides utilities for running ripgrep searches.
 */
@Slf4j
@Component
public class RipgrepUtils {



    /**
     * Check if ripgrep is available on the system.
     */
    public static boolean isRipgrepAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("rg", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Run a ripgrep search.
     * Translated from ripGrep() in ripgrep.ts
     */
    public static RipgrepResult ripGrep(
            String pattern,
            String path,
            List<String> extraArgs) throws Exception {

        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--no-heading");
        cmd.add("--with-filename");
        cmd.add("-n"); // line numbers

        if (extraArgs != null) {
            cmd.addAll(extraArgs);
        }

        cmd.add(pattern);
        if (path != null) {
            cmd.add(path);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes());
        String stderr = new String(p.getErrorStream().readAllBytes());
        boolean completed = p.waitFor(30, TimeUnit.SECONDS);

        if (!completed) {
            p.destroyForcibly();
            throw new RuntimeException("ripgrep timed out");
        }

        return new RipgrepResult(stdout, stderr, p.exitValue());
    }

    /**
     * Count files matching a glob pattern using ripgrep.
     * Translated from countFilesRoundedRg() in ripgrep.ts
     */
    public static int countFilesRounded(String path, String glob) {
        try {
            List<String> cmd = new ArrayList<>(List.of("rg", "--files"));
            if (glob != null) {
                cmd.add("--glob");
                cmd.add(glob);
            }
            if (path != null) {
                cmd.add(path);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);

            long count = output.lines().filter(l -> !l.isBlank()).count();
            // Round to nearest 10
            return (int) ((count / 10) * 10);

        } catch (Exception e) {
            log.debug("Could not count files with ripgrep: {}", e.getMessage());
            return 0;
        }
    }

    public record RipgrepResult(String stdout, String stderr, int exitCode) {
        public boolean isSuccess() { return exitCode == 0; }
        public boolean hasMatches() { return exitCode == 0 && !stdout.isBlank(); }
    }

    private RipgrepUtils() {}
}
