package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Portable worktree path detection.
 * Translated from src/utils/getWorktreePathsPortable.ts
 *
 * Uses only a child process with no analytics or bootstrap dependencies.
 * Used wherever worktree paths are needed without the full CLI dependency chain.
 */
@Slf4j
public class WorktreePathsPortable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorktreePathsPortable.class);


    private static final long GIT_TIMEOUT_MS = 5000;

    /**
     * Get worktree paths using only a git subprocess.
     * Returns all worktree paths, including the main worktree.
     * Returns an empty list on any failure.
     * Translated from getWorktreePathsPortable() in getWorktreePathsPortable.ts
     *
     * @param cwd Directory to run git from
     * @return List of absolute worktree paths (NFC-normalised)
     */
    public static List<String> getWorktreePathsPortable(String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "list", "--porcelain");
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return List.of();
            }

            String output = new String(process.getInputStream().readAllBytes());
            if (output.isBlank()) return List.of();

            return parseWorktreeOutput(output);
        } catch (Exception e) {
            log.debug("Could not get worktree paths (portable): {}", e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static List<String> parseWorktreeOutput(String output) {
        List<String> paths = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("worktree ")) {
                String path = line.substring("worktree ".length());
                // NFC-normalize mirrors .normalize('NFC') in the TS implementation
                paths.add(Normalizer.normalize(path, Normalizer.Form.NFC));
            }
        }
        return paths;
    }

    /**
     * Async variant of {@link #getWorktreePathsPortable(String)}.
     * Runs the blocking git call on the common ForkJoinPool and returns a
     * CompletableFuture so callers can compose further async steps.
     */
    public static java.util.concurrent.CompletableFuture<List<String>> getWorktreePaths(String cwd) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getWorktreePathsPortable(cwd));
    }

    private WorktreePathsPortable() {}
}
