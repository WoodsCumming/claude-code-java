package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Git worktree path utilities with analytics tracking.
 * Translated from src/utils/getWorktreePaths.ts
 *
 * Gets paths of all worktrees for the current git repository.
 * For a version without analytics deps use WorktreePathsPortable.
 */
@Slf4j
public class WorktreePaths {



    /**
     * Returns the paths of all worktrees for the current git repository.
     * If git is not available, not in a git repo, or only has one worktree,
     * returns an empty list.
     *
     * Result is sorted: current worktree first, then remaining worktrees
     * alphabetically — matching the TS implementation behaviour.
     *
     * Translated from getWorktreePaths() in getWorktreePaths.ts
     *
     * @param cwd Directory to run the command from
     * @return List of absolute worktree paths
     */
    public static List<String> getWorktreePaths(String cwd) {
        long startTime = System.currentTimeMillis();

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "list", "--porcelain");
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            long durationMs = System.currentTimeMillis() - startTime;

            if (exitCode != 0) {
                log.debug("tengu_worktree_detection duration_ms={} worktree_count=0 success=false",
                    durationMs);
                return List.of();
            }

            List<String> worktreePaths = parseWorktreeOutput(output);

            log.debug("tengu_worktree_detection duration_ms={} worktree_count={} success=true",
                durationMs, worktreePaths.size());

            // Sort: current worktree first, then alphabetically
            String sep = File.separator;
            String currentWorktree = worktreePaths.stream()
                .filter(p -> cwd.equals(p) || cwd.startsWith(p + sep))
                .findFirst()
                .orElse(null);

            List<String> others = worktreePaths.stream()
                .filter(p -> !p.equals(currentWorktree))
                .sorted(Comparator.naturalOrder())
                .toList();

            List<String> result = new ArrayList<>();
            if (currentWorktree != null) result.add(currentWorktree);
            result.addAll(others);
            return result;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.debug("tengu_worktree_detection duration_ms={} worktree_count=0 success=false: {}",
                durationMs, e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Parse porcelain worktree list output — lines starting with "worktree " contain paths.
     */
    private static List<String> parseWorktreeOutput(String output) {
        List<String> paths = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("worktree ")) {
                String path = line.substring("worktree ".length()).trim();
                // NFC-normalize the path (mirrors .normalize('NFC') in TS)
                if (!path.isEmpty()) paths.add(java.text.Normalizer.normalize(path,
                    java.text.Normalizer.Form.NFC));
            }
        }
        return paths;
    }

    private WorktreePaths() {}
}
