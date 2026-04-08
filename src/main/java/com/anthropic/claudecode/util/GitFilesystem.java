package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Filesystem-based git state reading.
 * Translated from src/utils/git/gitFilesystem.ts
 *
 * Reads git state directly from files without spawning git subprocesses.
 */
@Slf4j
public class GitFilesystem {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GitFilesystem.class);


    private static final Map<String, Optional<String>> resolveGitDirCache = new ConcurrentHashMap<>();

    /**
     * Resolve the actual .git directory.
     * Translated from resolveGitDir() in gitFilesystem.ts
     */
    public static Optional<String> resolveGitDir(String startPath) {
        if (startPath == null) startPath = System.getProperty("user.dir");

        String cwd = Paths.get(startPath).toAbsolutePath().normalize().toString();

        if (resolveGitDirCache.containsKey(cwd)) {
            return resolveGitDirCache.get(cwd);
        }

        Optional<String> root = GitUtils.findGitRoot(cwd);
        if (root.isEmpty()) {
            resolveGitDirCache.put(cwd, Optional.empty());
            return Optional.empty();
        }

        String gitPath = root.get() + "/.git";
        File gitFile = new File(gitPath);

        Optional<String> result;
        if (gitFile.isFile()) {
            // Worktree or submodule: .git is a file with "gitdir: <path>"
            try {
                String content = Files.readString(gitFile.toPath()).trim();
                if (content.startsWith("gitdir:")) {
                    String gitdir = content.substring(7).trim();
                    Path resolved = Paths.get(gitFile.getParent()).resolve(gitdir).normalize();
                    result = Optional.of(resolved.toString());
                } else {
                    result = Optional.of(gitPath);
                }
            } catch (Exception e) {
                result = Optional.of(gitPath);
            }
        } else if (gitFile.isDirectory()) {
            result = Optional.of(gitPath);
        } else {
            result = Optional.empty();
        }

        resolveGitDirCache.put(cwd, result);
        return result;
    }

    /**
     * Read the current HEAD SHA or branch.
     * Translated from readWorktreeHeadSha() in gitFilesystem.ts
     */
    public static Optional<String> readHeadSha(String repoPath) {
        try {
            Optional<String> gitDir = resolveGitDir(repoPath);
            if (gitDir.isEmpty()) return Optional.empty();

            String headPath = gitDir.get() + "/HEAD";
            String head = Files.readString(Paths.get(headPath)).trim();

            if (head.startsWith("ref: ")) {
                // Symbolic ref
                String refPath = head.substring(5);
                String refFile = gitDir.get() + "/" + refPath;
                if (new File(refFile).exists()) {
                    return Optional.of(Files.readString(Paths.get(refFile)).trim());
                }
            } else {
                // Detached HEAD - direct SHA
                return Optional.of(head);
            }
        } catch (Exception e) {
            log.debug("Could not read HEAD: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Get the remote URL for a repo.
     * Translated from getRemoteUrlForDir() in gitFilesystem.ts
     */
    public static Optional<String> getRemoteUrl(String repoPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "remote", "get-url", "origin");
            pb.directory(new File(repoPath));
            Process p = pb.start();
            String url = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && !url.isEmpty()) {
                return Optional.of(url);
            }
        } catch (Exception e) {
            // No remote
        }
        return Optional.empty();
    }

    /**
     * Clear the git dir cache.
     */
    public static void clearResolveGitDirCache() {
        resolveGitDirCache.clear();
    }

    private GitFilesystem() {}
}
