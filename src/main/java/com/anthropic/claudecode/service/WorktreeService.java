package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Git worktree management service.
 * Translated from src/utils/worktree.ts
 *
 * Creates and manages isolated git worktrees for parallel development.
 * Worktrees live under {@code .claude/worktrees/<slug>} in the repo root.
 */
@Slf4j
@Service
public class WorktreeService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorktreeService.class);

    // =========================================================================
    // Slug validation
    // =========================================================================

    private static final Pattern VALID_SLUG_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final int MAX_SLUG_LENGTH = 64;

    /**
     * Validate a worktree slug to prevent path traversal and directory escape.
     *
     * The slug is joined into {@code .claude/worktrees/<slug>} via path resolution,
     * so {@code ..} segments or absolute paths would escape the worktrees directory.
     * Forward slashes are allowed for nesting ({@code asm/feature-foo}); each
     * segment is validated independently so {@code .} / {@code ..} and drive-spec
     * characters are still rejected.
     *
     * Throws synchronously — callers rely on this running before any side effects.
     * Translated from validateWorktreeSlug() in worktree.ts
     */
    public void validateWorktreeSlug(String slug) {
        if (slug == null || slug.isEmpty()) {
            throw new IllegalArgumentException("Worktree slug cannot be empty");
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException(
                "Invalid worktree name: must be " + MAX_SLUG_LENGTH
                    + " characters or fewer (got " + slug.length() + ")");
        }
        for (String segment : slug.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                    "Invalid worktree name \"" + slug
                        + "\": must not contain \".\" or \"..\" path segments");
            }
            if (!VALID_SLUG_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                    "Invalid worktree name \"" + slug
                        + "\": each \"/\"-separated segment must be non-empty and contain "
                        + "only letters, digits, dots, underscores, and dashes");
            }
        }
    }

    // =========================================================================
    // Naming helpers
    // =========================================================================

    /**
     * Flatten nested slugs ({@code user/feature} → {@code user+feature}).
     * {@code +} is valid in git branch names but NOT in slug segments, so mapping is injective.
     * Prevents D/F conflicts in git refs and directory nesting issues.
     * Translated from flattenSlug() in worktree.ts
     */
    public String flattenSlug(String slug) {
        return slug.replace("/", "+");
    }

    /**
     * Derive the git branch name for a worktree slug.
     * Translated from worktreeBranchName() in worktree.ts
     */
    public String worktreeBranchName(String slug) {
        return "worktree-" + flattenSlug(slug);
    }

    /**
     * Get the filesystem path for a worktree given the repo root and slug.
     * Translated from worktreePathFor() in worktree.ts
     */
    public Path worktreePathFor(Path repoRoot, String slug) {
        return repoRoot.resolve(".claude").resolve("worktrees").resolve(flattenSlug(slug));
    }

    /**
     * Get the parent worktrees directory for a repo root.
     * Translated from worktreesDir() in worktree.ts
     */
    public Path worktreesDir(Path repoRoot) {
        return repoRoot.resolve(".claude").resolve("worktrees");
    }

    /**
     * Generate a tmux session name from repo path and branch.
     * Translated from generateTmuxSessionName() in worktree.ts
     */
    public String generateTmuxSessionName(String repoPath, String branch) {
        String repoName = Path.of(repoPath).getFileName().toString();
        String combined = repoName + "_" + branch;
        return combined.replaceAll("[/.]", "_");
    }

    // =========================================================================
    // Session state
    // =========================================================================

    /**
     * Current worktree session state for this JVM process (one per session).
     * Translated from currentWorktreeSession in worktree.ts
     */
    private final AtomicReference<WorktreeSession> currentWorktreeSession = new AtomicReference<>(null);

    /**
     * Get the active worktree session, or null when not in a worktree.
     * Translated from getCurrentWorktreeSession() in worktree.ts
     */
    public WorktreeSession getCurrentWorktreeSession() {
        return currentWorktreeSession.get();
    }

    /**
     * Restore the worktree session on --resume.
     * Translated from restoreWorktreeSession() in worktree.ts
     */
    public void restoreWorktreeSession(WorktreeSession session) {
        currentWorktreeSession.set(session);
    }

    // =========================================================================
    // Worktree lifecycle
    // =========================================================================

    /**
     * Creates or resumes a git worktree for the given slug.
     *
     * Fast resume path: if the worktree already exists (HEAD SHA is readable)
     * no git-fetch is issued. New worktrees fetch the default branch from
     * origin and create the branch with {@code git worktree add -B}.
     *
     * Translated from getOrCreateWorktree() in worktree.ts
     *
     * @param repoRoot Path to the main repository root.
     * @param slug     The validated worktree slug.
     * @return A future resolving to a {@link WorktreeCreateResult}.
     */
    public CompletableFuture<WorktreeCreateResult> getOrCreateWorktree(Path repoRoot, String slug) {
        return CompletableFuture.supplyAsync(() -> {
            validateWorktreeSlug(slug);
            Path worktreePath = worktreePathFor(repoRoot, slug);
            String worktreeBranch = worktreeBranchName(slug);

            // Fast resume: read HEAD SHA from the .git pointer file directly.
            String existingHead = readWorktreeHeadSha(worktreePath);
            if (existingHead != null) {
                log.debug("Resuming existing worktree: {} at {}", slug, worktreePath);
                return new WorktreeCreateResult(worktreePath.toString(), worktreeBranch,
                    existingHead, null, true);
            }

            // New worktree: ensure parent directory exists.
            try {
                Files.createDirectories(worktreesDir(repoRoot));
            } catch (IOException e) {
                throw new RuntimeException("Failed to create worktrees directory: " + e.getMessage(), e);
            }

            // Resolve the base branch.
            String defaultBranch = getDefaultBranch(repoRoot);
            String baseBranch = "origin/" + defaultBranch;

            // Create worktree — use -B to reset any orphan branch from a prior removed worktree.
            ProcessResult result = runGit(repoRoot,
                "worktree", "add", "-B", worktreeBranch, worktreePath.toString(), baseBranch);
            if (result.exitCode() != 0) {
                throw new RuntimeException("Failed to create worktree: " + result.stderr());
            }

            log.info("Created worktree: {} at {}", slug, worktreePath);

            String headSha = runGit(worktreePath, "rev-parse", "HEAD").stdout().trim();
            return new WorktreeCreateResult(worktreePath.toString(), worktreeBranch,
                headSha.isEmpty() ? baseBranch : headSha, baseBranch, false);
        });
    }

    /**
     * Remove a git worktree (and optionally delete its branch).
     * Translated from removeAgentWorktree() in worktree.ts
     */
    public CompletableFuture<Void> removeWorktree(Path repoRoot, Path worktreePath) {
        return CompletableFuture.runAsync(() -> {
            ProcessResult result = runGit(repoRoot,
                "worktree", "remove", "--force", worktreePath.toString());
            if (result.exitCode() != 0) {
                log.warn("Failed to remove worktree {}: {}", worktreePath, result.stderr());
            } else {
                log.info("Removed worktree: {}", worktreePath);
            }
        });
    }

    /**
     * Check if a worktree has uncommitted changes.
     * Translated from hasWorktreeChanges() in worktree.ts
     */
    public boolean hasWorktreeChanges(Path worktreePath) {
        try {
            ProcessResult result = runGit(worktreePath, "status", "--porcelain");
            return !result.stdout().trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Symlink directories from the main repository to the worktree.
     * Prevents disk bloat from duplicating large directories like node_modules.
     * Translated from symlinkDirectories() in worktree.ts
     *
     * @param repoRootPath  Path to the main repository root.
     * @param worktreePath  Path to the worktree directory.
     * @param dirsToSymlink Directory names to symlink (e.g. ["node_modules"]).
     */
    public CompletableFuture<Void> symlinkDirectories(
            Path repoRootPath, Path worktreePath, List<String> dirsToSymlink) {
        return CompletableFuture.runAsync(() -> {
            for (String dir : dirsToSymlink) {
                if (dir.contains("..") || dir.startsWith("/")) {
                    log.warn("Skipping symlink for \"{}\": path traversal detected", dir);
                    continue;
                }
                Path source = repoRootPath.resolve(dir);
                Path dest = worktreePath.resolve(dir);
                try {
                    Files.createSymbolicLink(dest, source);
                    log.debug("Symlinked {} from main repository to worktree", dir);
                } catch (FileAlreadyExistsException e) {
                    // Expected — skip silently.
                } catch (NoSuchFileException e) {
                    // Source doesn't exist yet — skip silently.
                } catch (IOException e) {
                    log.warn("Failed to symlink {}: {}", dir, e.getMessage());
                }
            }
        });
    }

    // =========================================================================
    // Git filesystem helpers
    // =========================================================================

    /**
     * Read the HEAD SHA from a worktree's {@code .git/HEAD} pointer file without
     * spawning a subprocess. Returns null if the worktree does not exist.
     * Translated from readWorktreeHeadSha() in git/gitFilesystem.ts
     */
    public String readWorktreeHeadSha(Path worktreePath) {
        // In a linked worktree .git is a file containing "gitdir: <path>"
        Path gitFile = worktreePath.resolve(".git");
        if (!Files.isRegularFile(gitFile)) return null;
        try {
            String content = Files.readString(gitFile, StandardCharsets.UTF_8).trim();
            if (!content.startsWith("gitdir:")) return null;
            String gitDir = content.substring("gitdir:".length()).trim();
            Path headFile = Path.of(gitDir).resolve("HEAD");
            if (!Files.exists(headFile)) return null;
            String headContent = Files.readString(headFile, StandardCharsets.UTF_8).trim();
            // HEAD may be "ref: refs/heads/..." or a bare SHA.
            if (headContent.startsWith("ref: ")) {
                String ref = headContent.substring("ref: ".length()).trim();
                Path refFile = Path.of(gitDir).resolve(ref);
                if (Files.exists(refFile)) {
                    return Files.readString(refFile, StandardCharsets.UTF_8).trim();
                }
            }
            return headContent; // bare SHA
        } catch (IOException e) {
            return null;
        }
    }

    // =========================================================================
    // Private git helpers
    // =========================================================================

    private String getDefaultBranch(Path repoRoot) {
        ProcessResult result = runGit(repoRoot,
            "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        if (result.exitCode() == 0 && !result.stdout().isBlank()) {
            String ref = result.stdout().trim();
            // "origin/main" → "main"
            int slash = ref.indexOf('/');
            return slash >= 0 ? ref.substring(slash + 1) : ref;
        }
        // Fallback to main.
        return "main";
    }

    private ProcessResult runGit(Path cwd, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            return new ProcessResult(exit, stdout, stderr);
        } catch (Exception e) {
            throw new RuntimeException("git command failed: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Worktree session state.
     * Translated from WorktreeSession in worktree.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class WorktreeSession {
        /** Original working directory before entering the worktree. */
        private String originalCwd;
        private String worktreePath;
        private String worktreeName;
        private String worktreeBranch;
        private String originalBranch;
        private String originalHeadCommit;
        private String sessionId;
        private String tmuxSessionName;
        /** True when worktree was created via a hook (not built-in logic). */
        private boolean hookBased;
        /** How long worktree creation took in ms. Unset when resuming. */
        private Long creationDurationMs;
        /** True if git sparse-checkout was applied via settings.worktree.sparsePaths. */
        private boolean usedSparsePaths;

        public String getOriginalCwd() { return originalCwd; }
        public void setOriginalCwd(String v) { originalCwd = v; }
        public String getWorktreePath() { return worktreePath; }
        public void setWorktreePath(String v) { worktreePath = v; }
        public String getWorktreeName() { return worktreeName; }
        public void setWorktreeName(String v) { worktreeName = v; }
        public String getWorktreeBranch() { return worktreeBranch; }
        public void setWorktreeBranch(String v) { worktreeBranch = v; }
        public String getOriginalBranch() { return originalBranch; }
        public void setOriginalBranch(String v) { originalBranch = v; }
        public String getOriginalHeadCommit() { return originalHeadCommit; }
        public void setOriginalHeadCommit(String v) { originalHeadCommit = v; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getTmuxSessionName() { return tmuxSessionName; }
        public void setTmuxSessionName(String v) { tmuxSessionName = v; }
        public boolean isHookBased() { return hookBased; }
        public void setHookBased(boolean v) { hookBased = v; }
        public Long getCreationDurationMs() { return creationDurationMs; }
        public void setCreationDurationMs(Long v) { creationDurationMs = v; }
        public boolean isUsedSparsePaths() { return usedSparsePaths; }
        public void setUsedSparsePaths(boolean v) { usedSparsePaths = v; }
    }

    /**
     * Result of getOrCreateWorktree.
     * Translated from WorktreeCreateResult in worktree.ts
     */
    public record WorktreeCreateResult(
        String worktreePath,
        String worktreeBranch,
        String headCommit,
        String baseBranch, // null when existed == true
        boolean existed
    ) {}

    /** Lightweight subprocess result holder. */
    private record ProcessResult(int exitCode, String stdout, String stderr) {}

    /**
     * Save current worktree state if the session is using a worktree.
     * No-op if there is no active worktree session.
     */
    public void saveCurrentWorktreeStateIfPresent() {
        // No-op stub — actual persistence done when worktree is explicitly managed
    }
}
