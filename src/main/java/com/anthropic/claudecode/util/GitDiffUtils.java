package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git diff utilities.
 * Translated from src/utils/gitDiff.ts
 *
 * Provides utilities for computing and parsing git diffs.
 */
@Slf4j
public class GitDiffUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GitDiffUtils.class);


    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Aggregate diff statistics.
     * Translated from GitDiffStats in gitDiff.ts
     */
    public record GitDiffStats(int filesCount, int linesAdded, int linesRemoved) {}

    /**
     * Per-file diff statistics.
     * Translated from PerFileStats in gitDiff.ts
     */
    public record PerFileStats(int added, int removed, boolean isBinary, boolean isUntracked) {
        public PerFileStats(int added, int removed, boolean isBinary) {
            this(added, removed, isBinary, false);
        }
    }

    /**
     * Structured patch hunk (equivalent to StructuredPatchHunk from 'diff').
     */
    public record PatchHunk(int oldStart, int oldLines, int newStart, int newLines, List<String> lines) {}

    /**
     * Full git diff result.
     * Translated from GitDiffResult in gitDiff.ts
     */
    public record GitDiffResult(
        GitDiffStats stats,
        Map<String, PerFileStats> perFileStats,
        Map<String, List<PatchHunk>> hunks
    ) {}

    /**
     * Numstat parse result.
     * Translated from NumstatResult in gitDiff.ts
     */
    public record NumstatResult(GitDiffStats stats, Map<String, PerFileStats> perFileStats) {}

    /**
     * Single-file diff result for tool use.
     * Translated from ToolUseDiff in gitDiff.ts
     */
    public record ToolUseDiff(
        String filename,
        String status,          // "modified" | "added"
        int additions,
        int deletions,
        int changes,
        String patch,
        String repository       // nullable - "owner/repo" when on github.com
    ) {}

    // =========================================================================
    // Constants
    // =========================================================================

    private static final long GIT_TIMEOUT_MS = 5000;
    private static final int MAX_FILES = 50;
    private static final int MAX_DIFF_SIZE_BYTES = 1_000_000; // 1 MB
    private static final int MAX_LINES_PER_FILE = 400;
    private static final int MAX_FILES_FOR_DETAILS = 500;
    private static final long SINGLE_FILE_DIFF_TIMEOUT_MS = 3000;

    // =========================================================================
    // Main API
    // =========================================================================

    /**
     * Fetch git diff stats and hunks comparing working tree to HEAD.
     * Returns empty if not in a git repo or if git commands fail.
     * Returns empty during merge/rebase/cherry-pick/revert.
     * Translated from fetchGitDiff() in gitDiff.ts
     */
    public static Optional<GitDiffResult> fetchGitDiff(String repoPath) {
        if (!GitUtils.getIsGit(repoPath)) return Optional.empty();
        if (isInTransientGitState(repoPath)) return Optional.empty();

        // Quick probe with --shortstat
        Optional<String> shortstatOut = runGit(repoPath,
            "--no-optional-locks", "diff", "HEAD", "--shortstat");

        if (shortstatOut.isPresent()) {
            Optional<GitDiffStats> quickStats = parseShortstat(shortstatOut.get());
            if (quickStats.isPresent() && quickStats.get().filesCount() > MAX_FILES_FOR_DETAILS) {
                return Optional.of(new GitDiffResult(quickStats.get(), Map.of(), Map.of()));
            }
        }

        // Full numstat
        Optional<String> numstatOut = runGit(repoPath,
            "--no-optional-locks", "diff", "HEAD", "--numstat");
        if (numstatOut.isEmpty()) return Optional.empty();

        NumstatResult numstat = parseGitNumstat(numstatOut.get());
        Map<String, PerFileStats> perFileStats = new LinkedHashMap<>(numstat.perFileStats());
        GitDiffStats stats = numstat.stats();

        // Include untracked files
        int remainingSlots = MAX_FILES - perFileStats.size();
        if (remainingSlots > 0) {
            Map<String, PerFileStats> untrackedStats = fetchUntrackedFiles(repoPath, remainingSlots);
            if (!untrackedStats.isEmpty()) {
                int newTotal = stats.filesCount() + untrackedStats.size();
                stats = new GitDiffStats(newTotal, stats.linesAdded(), stats.linesRemoved());
                perFileStats.putAll(untrackedStats);
            }
        }

        return Optional.of(new GitDiffResult(stats, perFileStats, Map.of()));
    }

    /**
     * Fetch git diff hunks on-demand (for DiffDialog).
     * Translated from fetchGitDiffHunks() in gitDiff.ts
     */
    public static Map<String, List<PatchHunk>> fetchGitDiffHunks(String repoPath) {
        if (!GitUtils.getIsGit(repoPath)) return Map.of();
        if (isInTransientGitState(repoPath)) return Map.of();

        Optional<String> diffOut = runGit(repoPath, "--no-optional-locks", "diff", "HEAD");
        return diffOut.map(GitDiffUtils::parseGitDiff).orElse(Map.of());
    }

    /**
     * Fetch a structured diff for a single file against merge base with default branch.
     * Translated from fetchSingleFileGitDiff() in gitDiff.ts
     */
    public static Optional<ToolUseDiff> fetchSingleFileGitDiff(
            String absoluteFilePath, String repoPath) {

        Optional<String> gitRootOpt = GitUtils.findGitRoot(
            Paths.get(absoluteFilePath).getParent().toString());
        if (gitRootOpt.isEmpty()) return Optional.empty();
        String gitRoot = gitRootOpt.get();

        String gitPath = Paths.get(gitRoot).relativize(Paths.get(absoluteFilePath))
            .toString().replace(File.separator, "/");

        // Check if file is tracked
        boolean isTracked = runGitExitCode(gitRoot,
            "--no-optional-locks", "ls-files", "--error-unmatch", gitPath) == 0;

        if (isTracked) {
            String diffRef = getDiffRef(gitRoot);
            Optional<String> diffOut = runGitWithTimeout(gitRoot, SINGLE_FILE_DIFF_TIMEOUT_MS,
                "--no-optional-locks", "diff", diffRef, "--", gitPath);
            if (diffOut.isEmpty() || diffOut.get().isBlank()) return Optional.empty();
            ToolUseDiff diff = parseRawDiffToToolUseDiff(gitPath, diffOut.get(), "modified");
            return Optional.of(diff);
        }

        // Untracked — synthetic diff
        return generateSyntheticDiff(gitPath, absoluteFilePath);
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    /**
     * Parse git diff --numstat output.
     * Format: added\tremoved\tfilename
     * Translated from parseGitNumstat() in gitDiff.ts
     */
    public static NumstatResult parseGitNumstat(String stdout) {
        String[] lines = stdout.trim().split("\n");
        int added = 0, removed = 0, validFileCount = 0;
        Map<String, PerFileStats> perFileStats = new LinkedHashMap<>();

        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;

            validFileCount++;
            String addStr = parts[0];
            String remStr = parts[1];
            String filePath = parts[2]; // may contain tabs in theory, already split at 3

            boolean isBinary = "-".equals(addStr) || "-".equals(remStr);
            int fileAdded = isBinary ? 0 : parseIntSafe(addStr);
            int fileRemoved = isBinary ? 0 : parseIntSafe(remStr);

            added += fileAdded;
            removed += fileRemoved;

            if (perFileStats.size() < MAX_FILES) {
                perFileStats.put(filePath, new PerFileStats(fileAdded, fileRemoved, isBinary));
            }
        }

        return new NumstatResult(
            new GitDiffStats(validFileCount, added, removed),
            perFileStats
        );
    }

    /**
     * Parse unified diff output into per-file hunk maps.
     * Applies MAX_FILES, MAX_DIFF_SIZE_BYTES, and MAX_LINES_PER_FILE limits.
     * Translated from parseGitDiff() in gitDiff.ts
     */
    public static Map<String, List<PatchHunk>> parseGitDiff(String stdout) {
        Map<String, List<PatchHunk>> result = new LinkedHashMap<>();
        if (stdout == null || stdout.isBlank()) return result;

        // Split by "diff --git "
        String[] fileDiffs = stdout.split("(?m)^diff --git ");
        for (String fileDiff : fileDiffs) {
            if (fileDiff.isBlank()) continue;
            if (result.size() >= MAX_FILES) break;

            // Skip files larger than 1 MB
            if (fileDiff.length() > MAX_DIFF_SIZE_BYTES) continue;

            String[] lines = fileDiff.split("\n");
            if (lines.length == 0) continue;

            // Extract filename from header: "a/path b/path"
            Matcher headerMatch = Pattern.compile("^a/(.+?) b/(.+)$").matcher(lines[0]);
            if (!headerMatch.matches()) continue;
            String filePath = headerMatch.group(2);

            List<PatchHunk> fileHunks = new ArrayList<>();
            PatchHunk currentHunk = null;
            int lineCount = 0;
            List<String> currentLines = null;

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];

                Matcher hunkMatch = Pattern.compile(
                    "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@"
                ).matcher(line);

                if (hunkMatch.find()) {
                    if (currentHunk != null) fileHunks.add(currentHunk);
                    currentLines = new ArrayList<>();
                    currentHunk = new PatchHunk(
                        parseIntSafe(hunkMatch.group(1)),
                        hunkMatch.group(2) != null ? parseIntSafe(hunkMatch.group(2)) : 1,
                        parseIntSafe(hunkMatch.group(3)),
                        hunkMatch.group(4) != null ? parseIntSafe(hunkMatch.group(4)) : 1,
                        currentLines
                    );
                    lineCount = 0;
                    continue;
                }

                // Skip metadata lines
                if (line.startsWith("index ") || line.startsWith("---") || line.startsWith("+++")
                    || line.startsWith("new file") || line.startsWith("deleted file")
                    || line.startsWith("old mode") || line.startsWith("new mode")
                    || line.startsWith("Binary files")) {
                    continue;
                }

                // Add diff lines
                if (currentHunk != null && currentLines != null
                    && (line.startsWith("+") || line.startsWith("-")
                        || line.startsWith(" ") || line.isEmpty())) {
                    if (lineCount < MAX_LINES_PER_FILE) {
                        currentLines.add(line);
                        lineCount++;
                    }
                }
            }

            if (currentHunk != null) fileHunks.add(currentHunk);
            if (!fileHunks.isEmpty()) result.put(filePath, fileHunks);
        }

        return result;
    }

    /**
     * Parse git diff --shortstat output.
     * Format: " 1648 files changed, 52341 insertions(+), 8123 deletions(-)"
     * Translated from parseShortstat() in gitDiff.ts
     */
    public static Optional<GitDiffStats> parseShortstat(String stdout) {
        if (stdout == null || stdout.isBlank()) return Optional.empty();
        Matcher m = Pattern.compile(
            "(\\d+)\\s+files?\\s+changed(?:,\\s+(\\d+)\\s+insertions?\\(\\+\\))?(?:,\\s+(\\d+)\\s+deletions?\\(-\\))?"
        ).matcher(stdout);
        if (!m.find()) return Optional.empty();
        return Optional.of(new GitDiffStats(
            parseIntSafe(m.group(1)),
            m.group(2) != null ? parseIntSafe(m.group(2)) : 0,
            m.group(3) != null ? parseIntSafe(m.group(3)) : 0
        ));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Check if we're in a transient git state (merge, rebase, cherry-pick, revert).
     */
    private static boolean isInTransientGitState(String repoPath) {
        Optional<String> gitDir = runGit(repoPath, "rev-parse", "--git-dir");
        if (gitDir.isEmpty()) return false;

        for (String file : List.of("MERGE_HEAD", "REBASE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD")) {
            if (Files.exists(Paths.get(gitDir.get(), file))) return true;
        }
        return false;
    }

    /**
     * Fetch untracked file names (file path only, no content).
     */
    private static Map<String, PerFileStats> fetchUntrackedFiles(String repoPath, int maxFiles) {
        Optional<String> output = runGit(repoPath,
            "--no-optional-locks", "ls-files", "--others", "--exclude-standard");
        if (output.isEmpty() || output.get().isBlank()) return Map.of();

        Map<String, PerFileStats> stats = new LinkedHashMap<>();
        String[] paths = output.get().trim().split("\n");
        for (String filePath : paths) {
            if (filePath.isBlank() || stats.size() >= maxFiles) break;
            stats.put(filePath, new PerFileStats(0, 0, false, true));
        }
        return stats;
    }

    /**
     * Determine diff ref (merge base or HEAD).
     */
    private static String getDiffRef(String gitRoot) {
        String baseRef = System.getenv("CLAUDE_CODE_BASE_REF");
        if (baseRef == null || baseRef.isBlank()) {
            baseRef = GitUtils.getDefaultBranch(gitRoot).orElse("HEAD");
        }
        Optional<String> mergeBase = runGitWithTimeout(gitRoot, SINGLE_FILE_DIFF_TIMEOUT_MS,
            "--no-optional-locks", "merge-base", "HEAD", baseRef);
        if (mergeBase.isPresent() && !mergeBase.get().isBlank()) {
            return mergeBase.get().trim();
        }
        return "HEAD";
    }

    private static ToolUseDiff parseRawDiffToToolUseDiff(
            String filename, String rawDiff, String status) {

        String[] lines = rawDiff.split("\n");
        List<String> patchLines = new ArrayList<>();
        boolean inHunks = false;
        int additions = 0, deletions = 0;

        for (String line : lines) {
            if (line.startsWith("@@")) inHunks = true;
            if (inHunks) {
                patchLines.add(line);
                if (line.startsWith("+") && !line.startsWith("+++")) additions++;
                else if (line.startsWith("-") && !line.startsWith("---")) deletions++;
            }
        }

        return new ToolUseDiff(
            filename, status, additions, deletions,
            additions + deletions, String.join("\n", patchLines), null
        );
    }

    private static Optional<ToolUseDiff> generateSyntheticDiff(
            String gitPath, String absoluteFilePath) {
        try {
            Path p = Paths.get(absoluteFilePath);
            if (Files.size(p) > MAX_DIFF_SIZE_BYTES) return Optional.empty();
            String content = Files.readString(p);
            String[] lines = content.split("\n", -1);
            // Remove trailing empty line from split if file ends with newline
            int lineCount = lines.length;
            if (lineCount > 0 && lines[lineCount - 1].isEmpty()) lineCount--;

            StringBuilder addedLines = new StringBuilder();
            for (int i = 0; i < lineCount; i++) {
                if (i > 0) addedLines.append("\n");
                addedLines.append("+").append(lines[i]);
            }
            String patch = "@@ -0,0 +1," + lineCount + " @@\n" + addedLines;
            return Optional.of(new ToolUseDiff(
                gitPath, "added", lineCount, 0, lineCount, patch, null
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> runGit(String repoPath, String... args) {
        return runGitWithTimeout(repoPath, GIT_TIMEOUT_MS, args);
    }

    private static Optional<String> runGitWithTimeout(
            String repoPath, long timeoutMs, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(repoPath));
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) { p.destroyForcibly(); return Optional.empty(); }
            if (p.exitValue() != 0) return Optional.empty();
            return Optional.of(stdout);
        } catch (Exception e) {
            log.debug("git {} failed: {}", Arrays.toString(args), e.getMessage());
            return Optional.empty();
        }
    }

    private static int runGitExitCode(String repoPath, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(repoPath));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean finished = p.waitFor((long) GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) { p.destroyForcibly(); return -1; }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private GitDiffUtils() {}
}
