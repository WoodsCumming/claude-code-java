package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git utility functions.
 * Translated from src/utils/git.ts
 */
@Slf4j
public class GitUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GitUtils.class);


    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Git file status result.
     * Translated from GitFileStatus in git.ts
     */
    public record GitFileStatus(List<String> tracked, List<String> untracked) {}

    /**
     * Git repository state.
     * Translated from GitRepoState in git.ts
     */
    public record GitRepoState(
        String commitHash,
        String branchName,
        String remoteUrl,
        boolean isHeadOnRemote,
        boolean isClean,
        int worktreeCount
    ) {}

    /**
     * Preserved git state for issue submission.
     * Translated from PreservedGitState in git.ts
     */
    public record PreservedGitState(
        String remoteSha,
        String remoteBase,
        String patch,
        List<UntrackedFile> untrackedFiles,
        String formatPatch,
        String headSha,
        String branchName
    ) {}

    public record UntrackedFile(String path, String content) {}

    // =========================================================================
    // Constants
    // =========================================================================

    private static final long MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE_BYTES = 5L * 1024 * 1024 * 1024;
    private static final int MAX_FILE_COUNT = 20000;
    private static final int SNIFF_BUFFER_SIZE = 64 * 1024;

    // =========================================================================
    // Core git root discovery
    // =========================================================================

    /**
     * Find the git root by walking up the directory tree.
     * Looks for a .git directory or file (worktrees/submodules use a file).
     * Returns the directory containing .git, or empty if not found.
     * Translated from findGitRoot() in git.ts
     */
    public static Optional<String> findGitRoot(String startPath) {
        if (startPath == null) return Optional.empty();

        long startTime = System.currentTimeMillis();
        log.debug("find_git_root_started");

        Path current = Paths.get(startPath).toAbsolutePath().normalize();
        int statCount = 0;

        while (current != null) {
            Path gitPath = current.resolve(".git");
            statCount++;
            if (Files.exists(gitPath) && (Files.isDirectory(gitPath) || Files.isRegularFile(gitPath))) {
                log.debug("find_git_root_completed duration_ms={} stat_count={} found=true",
                    System.currentTimeMillis() - startTime, statCount);
                return Optional.of(current.toString());
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }

        log.debug("find_git_root_completed duration_ms={} stat_count={} found=false",
            System.currentTimeMillis() - startTime, statCount);
        return Optional.empty();
    }

    /**
     * Find the canonical git repository root, resolving through worktrees.
     * For a worktree, follows the .git file -> gitdir: -> commondir chain.
     * Translated from findCanonicalGitRoot() in git.ts
     */
    public static Optional<String> findCanonicalGitRoot(String startPath) {
        Optional<String> root = findGitRoot(startPath);
        if (root.isEmpty()) return Optional.empty();
        return Optional.of(resolveCanonicalRoot(root.get()));
    }

    private static String resolveCanonicalRoot(String gitRoot) {
        try {
            Path gitFile = Paths.get(gitRoot, ".git");
            if (!Files.isRegularFile(gitFile)) return gitRoot;

            String gitContent = Files.readString(gitFile).trim();
            if (!gitContent.startsWith("gitdir:")) return gitRoot;

            Path worktreeGitDir = Paths.get(gitRoot).resolve(
                gitContent.substring("gitdir:".length()).trim()
            ).normalize();

            Path commondirFile = worktreeGitDir.resolve("commondir");
            if (!Files.exists(commondirFile)) return gitRoot;

            Path commonDir = worktreeGitDir.resolve(
                Files.readString(commondirFile).trim()
            ).normalize();

            // Security validation: worktreeGitDir must be a direct child of commonDir/worktrees/
            if (!worktreeGitDir.getParent().equals(commonDir.resolve("worktrees"))) {
                return gitRoot;
            }

            // Validate backlink
            Path backlinkFile = worktreeGitDir.resolve("gitdir");
            if (!Files.exists(backlinkFile)) return gitRoot;
            String backlinkContent = Files.readString(backlinkFile).trim();
            Path backlink = Paths.get(backlinkContent).toRealPath();
            Path expectedBacklink = Paths.get(gitRoot).toRealPath().resolve(".git");
            if (!backlink.equals(expectedBacklink)) return gitRoot;

            // Bare-repo worktrees
            if (!commonDir.getFileName().toString().equals(".git")) {
                return commonDir.toString();
            }
            return commonDir.getParent().toString();
        } catch (Exception e) {
            return gitRoot;
        }
    }

    /**
     * Returns the git executable name.
     * Translated from gitExe() in git.ts
     */
    public static String gitExe() {
        return "git";
    }

    /**
     * Check if currently in a git repo.
     * Translated from getIsGit() in git.ts
     */
    public static boolean getIsGit(String cwd) {
        return findGitRoot(cwd).isPresent();
    }

    /**
     * Check if currently at git root (cwd == git root).
     * Translated from isAtGitRoot() in git.ts
     */
    public static CompletableFuture<Boolean> isAtGitRoot(String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<String> gitRoot = findGitRoot(cwd);
            if (gitRoot.isEmpty()) return false;
            try {
                Path resolvedCwd = Paths.get(cwd).toRealPath();
                Path resolvedRoot = Paths.get(gitRoot.get()).toRealPath();
                return resolvedCwd.equals(resolvedRoot);
            } catch (Exception e) {
                return cwd.equals(gitRoot.get());
            }
        });
    }

    /**
     * Check if a directory is in a git repo.
     * Translated from dirIsInGitRepo() in git.ts
     */
    public static boolean dirIsInGitRepo(String cwd) {
        return findGitRoot(cwd).isPresent();
    }

    // =========================================================================
    // Branch / HEAD / remote
    // =========================================================================

    /**
     * Get the current HEAD commit hash.
     * Translated from getHead() in git.ts
     */
    public static Optional<String> getHead(String repoPath) {
        return runGit(repoPath, "rev-parse", "HEAD");
    }

    /**
     * Get the current branch name.
     * Translated from getBranch() in git.ts
     */
    public static Optional<String> getBranch(String repoPath) {
        return runGit(repoPath, "rev-parse", "--abbrev-ref", "HEAD");
    }

    /**
     * Get the default branch for origin.
     * Translated from getDefaultBranch() in git.ts
     */
    public static Optional<String> getDefaultBranch(String repoPath) {
        // Try symbolic-ref first
        Optional<String> symRef = runGit(repoPath, "symbolic-ref", "refs/remotes/origin/HEAD");
        if (symRef.isPresent()) {
            String[] parts = symRef.get().split("/");
            return Optional.of(parts[parts.length - 1]);
        }
        return Optional.empty();
    }

    /**
     * Get the remote URL for origin.
     * Translated from getRemoteUrl() in git.ts
     */
    public static Optional<String> getRemoteUrl(String repoPath) {
        return runGit(repoPath, "remote", "get-url", "origin");
    }

    /**
     * Normalizes a git remote URL to a canonical form for hashing.
     * Converts SSH and HTTPS URLs to the same format: host/owner/repo (lowercase, no .git)
     * Translated from normalizeGitRemoteUrl() in git.ts
     */
    public static Optional<String> normalizeGitRemoteUrl(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        String trimmed = url.trim();

        // SSH format: git@host:owner/repo.git
        Pattern sshPattern = Pattern.compile("^git@([^:]+):(.+?)(?:\\.git)?$");
        Matcher sshMatcher = sshPattern.matcher(trimmed);
        if (sshMatcher.matches()) {
            return Optional.of((sshMatcher.group(1) + "/" + sshMatcher.group(2)).toLowerCase());
        }

        // HTTPS/SSH URL format
        Pattern urlPattern = Pattern.compile(
            "^(?:https?|ssh)://(?:[^@]+@)?([^/]+)/(.+?)(?:\\.git)?$"
        );
        Matcher urlMatcher = urlPattern.matcher(trimmed);
        if (urlMatcher.matches()) {
            String host = urlMatcher.group(1);
            String path = urlMatcher.group(2);

            if (isLocalHost(host) && path.startsWith("git/")) {
                String proxyPath = path.substring(4);
                String[] segments = proxyPath.split("/");
                if (segments.length >= 3 && segments[0].contains(".")) {
                    return Optional.of(proxyPath.toLowerCase());
                }
                return Optional.of(("github.com/" + proxyPath).toLowerCase());
            }

            return Optional.of((host + "/" + path).toLowerCase());
        }

        return Optional.empty();
    }

    /**
     * Returns a SHA256 hash (first 16 chars) of the normalized git remote URL.
     * Translated from getRepoRemoteHash() in git.ts
     */
    public static Optional<String> getRepoRemoteHash(String repoPath) {
        Optional<String> remoteUrl = getRemoteUrl(repoPath);
        if (remoteUrl.isEmpty()) return Optional.empty();

        Optional<String> normalized = normalizeGitRemoteUrl(remoteUrl.get());
        if (normalized.isEmpty()) return Optional.empty();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalized.get().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return Optional.of(sb.toString().substring(0, 16));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // =========================================================================
    // Status checks
    // =========================================================================

    /**
     * Check if HEAD is on a remote tracking branch.
     * Translated from getIsHeadOnRemote() in git.ts
     */
    public static boolean getIsHeadOnRemote(String repoPath) {
        return runGitExitCode(repoPath, "rev-parse", "@{u}") == 0;
    }

    /**
     * Check if there are unpushed commits.
     * Translated from hasUnpushedCommits() in git.ts
     */
    public static boolean hasUnpushedCommits(String repoPath) {
        Optional<String> result = runGit(repoPath, "rev-list", "--count", "@{u}..HEAD");
        if (result.isEmpty()) return false;
        try {
            return Integer.parseInt(result.get().trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Check if the working tree is clean.
     * Translated from getIsClean() in git.ts
     */
    public static boolean getIsClean(String repoPath, boolean ignoreUntracked) {
        List<String> args = new ArrayList<>(List.of("--no-optional-locks", "status", "--porcelain"));
        if (ignoreUntracked) args.add("-uno");
        Optional<String> result = runGitArgs(repoPath, args);
        return result.map(s -> s.trim().isEmpty()).orElse(false);
    }

    /**
     * Get changed files from porcelain status output.
     * Translated from getChangedFiles() in git.ts
     */
    public static List<String> getChangedFiles(String repoPath) {
        Optional<String> output = runGitArgs(repoPath,
            List.of("--no-optional-locks", "status", "--porcelain"));
        if (output.isEmpty() || output.get().isBlank()) return List.of();

        List<String> files = new ArrayList<>();
        for (String line : output.get().trim().split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && !parts[1].isBlank()) {
                files.add(parts[1].trim());
            }
        }
        return files;
    }

    /**
     * Get file status split into tracked and untracked.
     * Translated from getFileStatus() in git.ts
     */
    public static GitFileStatus getFileStatus(String repoPath) {
        Optional<String> output = runGitArgs(repoPath,
            List.of("--no-optional-locks", "status", "--porcelain"));

        List<String> tracked = new ArrayList<>();
        List<String> untracked = new ArrayList<>();

        if (output.isEmpty() || output.get().isBlank()) {
            return new GitFileStatus(tracked, untracked);
        }

        for (String line : output.get().trim().split("\n")) {
            if (line.length() < 3) continue;
            String status = line.substring(0, 2);
            String filename = line.substring(2).trim();
            if ("??".equals(status)) {
                untracked.add(filename);
            } else if (!filename.isEmpty()) {
                tracked.add(filename);
            }
        }

        return new GitFileStatus(tracked, untracked);
    }

    /**
     * Get count of worktrees.
     * Translated from getWorktreeCount() in git.ts
     */
    public static int getWorktreeCount(String repoPath) {
        List<String> paths = WorktreePaths.getWorktreePaths(repoPath);
        return paths.isEmpty() ? 1 : paths.size();
    }

    // =========================================================================
    // Stash
    // =========================================================================

    /**
     * Stash all changes (including untracked) to return git to a clean state.
     * Translated from stashToCleanState() in git.ts
     */
    public static boolean stashToCleanState(String repoPath, String message) {
        try {
            String stashMessage = message != null ? message
                : "Claude Code auto-stash - " + new java.util.Date().toInstant();

            GitFileStatus status = getFileStatus(repoPath);

            // Stage untracked files first
            if (!status.untracked().isEmpty()) {
                List<String> addArgs = new ArrayList<>();
                addArgs.add("add");
                addArgs.addAll(status.untracked());
                if (runGitArgs(repoPath, addArgs).isEmpty() && runGitExitCode(repoPath,
                    addArgs.toArray(new String[0])) != 0) {
                    return false;
                }
            }

            // Stash everything
            return runGitExitCode(repoPath, "stash", "push", "--message", stashMessage) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Full state snapshot
    // =========================================================================

    /**
     * Get full git repository state.
     * Translated from getGitState() in git.ts
     */
    public static Optional<GitRepoState> getGitState(String repoPath) {
        try {
            Optional<String> commitHash = getHead(repoPath);
            Optional<String> branchName = getBranch(repoPath);
            Optional<String> remoteUrl = getRemoteUrl(repoPath);
            boolean isHeadOnRemote = getIsHeadOnRemote(repoPath);
            boolean isClean = getIsClean(repoPath, false);
            int worktreeCount = getWorktreeCount(repoPath);

            return Optional.of(new GitRepoState(
                commitHash.orElse(""),
                branchName.orElse(""),
                remoteUrl.orElse(null),
                isHeadOnRemote,
                isClean,
                worktreeCount
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // =========================================================================
    // Remote base detection (for issue submission)
    // =========================================================================

    /**
     * Find the best remote branch to use as a base.
     * Priority: tracking branch > origin/main > origin/staging > origin/master
     * Translated from findRemoteBase() in git.ts
     */
    public static Optional<String> findRemoteBase(String repoPath) {
        // Try tracking branch
        Optional<String> tracking = runGit(repoPath,
            "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
        if (tracking.isPresent() && !tracking.get().isBlank()) {
            return tracking;
        }

        // Try remote show origin to detect default branch
        Optional<String> remoteShow = runGitArgs(repoPath,
            List.of("remote", "show", "origin", "--", "HEAD"));
        if (remoteShow.isPresent()) {
            Matcher m = Pattern.compile("HEAD branch: (\\S+)").matcher(remoteShow.get());
            if (m.find()) {
                return Optional.of("origin/" + m.group(1));
            }
        }

        // Try common candidates
        for (String candidate : List.of("origin/main", "origin/staging", "origin/master")) {
            if (runGitExitCode(repoPath, "rev-parse", "--verify", candidate) == 0) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    /**
     * Preserve git state for issue submission.
     * Translated from preserveGitStateForIssue() in git.ts
     */
    public static Optional<PreservedGitState> preserveGitStateForIssue(String repoPath) {
        try {
            if (!getIsGit(repoPath)) return Optional.empty();

            // Check shallow clone
            if (isShallowClone(repoPath)) {
                log.debug("Shallow clone detected, using HEAD-only mode for issue");
                Optional<String> patch = runGit(repoPath, "diff", "HEAD");
                List<UntrackedFile> untracked = captureUntrackedFiles(repoPath);
                return Optional.of(new PreservedGitState(
                    null, null, patch.orElse(""), untracked, null, null, null
                ));
            }

            Optional<String> remoteBase = findRemoteBase(repoPath);

            if (remoteBase.isEmpty()) {
                log.debug("No remote found, using HEAD-only mode for issue");
                Optional<String> patch = runGit(repoPath, "diff", "HEAD");
                List<UntrackedFile> untracked = captureUntrackedFiles(repoPath);
                return Optional.of(new PreservedGitState(
                    null, null, patch.orElse(""), untracked, null, null, null
                ));
            }

            Optional<String> mergeBaseResult = runGit(repoPath, "merge-base", "HEAD", remoteBase.get());
            if (mergeBaseResult.isEmpty() || mergeBaseResult.get().isBlank()) {
                log.debug("Merge-base failed, using HEAD-only mode for issue");
                Optional<String> patch = runGit(repoPath, "diff", "HEAD");
                List<UntrackedFile> untracked = captureUntrackedFiles(repoPath);
                return Optional.of(new PreservedGitState(
                    null, null, patch.orElse(""), untracked, null, null, null
                ));
            }

            String remoteBaseSha = mergeBaseResult.get().trim();

            Optional<String> patch = runGit(repoPath, "diff", remoteBaseSha);
            List<UntrackedFile> untracked = captureUntrackedFiles(repoPath);
            Optional<String> formatPatchOut = runGitArgs(repoPath,
                List.of("format-patch", remoteBaseSha + "..HEAD", "--stdout"));
            Optional<String> headSha = runGit(repoPath, "rev-parse", "HEAD");
            Optional<String> branchNameOut = runGit(repoPath, "rev-parse", "--abbrev-ref", "HEAD");

            String formatPatch = formatPatchOut
                .filter(s -> !s.isBlank())
                .orElse(null);

            String branchName = branchNameOut
                .map(String::trim)
                .filter(s -> !s.equals("HEAD") && !s.isBlank())
                .orElse(null);

            return Optional.of(new PreservedGitState(
                remoteBaseSha,
                remoteBase.get(),
                patch.orElse(""),
                untracked,
                formatPatch,
                headSha.map(String::trim).orElse(null),
                branchName
            ));

        } catch (Exception e) {
            log.error("Failed to preserve git state: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Security: bare repo detection
    // =========================================================================

    /**
     * Check if the current directory appears to be a bare git repository
     * (sandbox escape attack vector).
     * Translated from isCurrentDirectoryBareGitRepo() in git.ts
     */
    public static boolean isCurrentDirectoryBareGitRepo(String cwd) {
        Path cwdPath = Paths.get(cwd);
        Path gitPath = cwdPath.resolve(".git");

        try {
            if (Files.isRegularFile(gitPath)) return false; // worktree/submodule
            if (Files.isDirectory(gitPath)) {
                Path gitHeadPath = gitPath.resolve("HEAD");
                try {
                    if (Files.isRegularFile(gitHeadPath)) return false; // normal repo
                } catch (Exception ignored) {}
                // .git exists but HEAD invalid — fall through
            }
        } catch (Exception ignored) {
            // no .git — fall through
        }

        // Check bare repo indicators
        try { if (Files.isRegularFile(cwdPath.resolve("HEAD"))) return true; } catch (Exception ignored) {}
        try { if (Files.isDirectory(cwdPath.resolve("objects"))) return true; } catch (Exception ignored) {}
        try { if (Files.isDirectory(cwdPath.resolve("refs"))) return true; } catch (Exception ignored) {}

        return false;
    }

    // =========================================================================
    // GitHub repo extraction
    // =========================================================================

    /**
     * Extract GitHub "owner/repo" from remote URL (github.com only).
     * Translated from getGithubRepo() in git.ts
     */
    public static Optional<String> getGithubRepo(String repoPath) {
        Optional<String> remoteUrl = getRemoteUrl(repoPath);
        if (remoteUrl.isEmpty()) return Optional.empty();

        Optional<String> normalized = normalizeGitRemoteUrl(remoteUrl.get());
        if (normalized.isEmpty()) return Optional.empty();

        String n = normalized.get();
        if (n.startsWith("github.com/")) {
            String ownerRepo = n.substring("github.com/".length());
            return Optional.of(ownerRepo);
        }

        return Optional.empty();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static boolean isLocalHost(String host) {
        String hostWithoutPort = host.split(":")[0];
        return "localhost".equals(hostWithoutPort)
            || hostWithoutPort.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    private static boolean isShallowClone(String repoPath) {
        try {
            Optional<String> gitDir = getGitDir(repoPath);
            if (gitDir.isEmpty()) return false;
            return Files.exists(Paths.get(gitDir.get(), "shallow"));
        } catch (Exception e) {
            return false;
        }
    }

    private static Optional<String> getGitDir(String repoPath) {
        return runGit(repoPath, "rev-parse", "--git-dir");
    }

    private static List<UntrackedFile> captureUntrackedFiles(String repoPath) {
        Optional<String> output = runGitArgs(repoPath,
            List.of("ls-files", "--others", "--exclude-standard"));
        if (output.isEmpty() || output.get().isBlank()) return List.of();

        List<UntrackedFile> result = new ArrayList<>();
        long totalSize = 0;

        for (String filePath : output.get().trim().split("\n")) {
            if (filePath.isBlank()) continue;
            if (result.size() >= MAX_FILE_COUNT) break;

            Path path = Paths.get(repoPath).resolve(filePath);
            try {
                long fileSize = Files.size(path);
                if (fileSize > MAX_FILE_SIZE_BYTES) continue;
                if (totalSize + fileSize > MAX_TOTAL_SIZE_BYTES) break;
                if (fileSize == 0) {
                    result.add(new UntrackedFile(filePath, ""));
                    continue;
                }

                byte[] content = Files.readAllBytes(path);
                // Simple binary sniff
                if (isBinaryContent(content, (int) Math.min(fileSize, SNIFF_BUFFER_SIZE))) continue;

                result.add(new UntrackedFile(filePath, new String(content, StandardCharsets.UTF_8)));
                totalSize += fileSize;
            } catch (Exception e) {
                log.debug("Failed to read untracked file {}: {}", filePath, e.getMessage());
            }
        }

        return result;
    }

    /**
     * Simple binary content detection: look for null bytes in the first N bytes.
     */
    private static boolean isBinaryContent(byte[] content, int limit) {
        int check = Math.min(limit, content.length);
        for (int i = 0; i < check; i++) {
            if (content[i] == 0) return true;
        }
        return false;
    }

    /**
     * Run git with individual string args, return trimmed stdout or empty on non-zero exit.
     */
    private static Optional<String> runGit(String repoPath, String... args) {
        return runGitArgs(repoPath, Arrays.asList(args));
    }

    /**
     * Run git with a list of args, return trimmed stdout or empty on non-zero exit.
     */
    private static Optional<String> runGitArgs(String repoPath, List<String> args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return Optional.empty(); }
            if (p.exitValue() != 0) return Optional.empty();
            return Optional.of(stdout);
        } catch (Exception e) {
            log.debug("git {} failed: {}", args, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Run git and return exit code (-1 on exception).
     */
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
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return -1; }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private GitUtils() {}
}
