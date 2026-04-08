package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Gitignore utilities.
 * Translated from src/utils/git/gitignore.ts
 */
@Slf4j
public class GitignoreUtils {



    /**
     * Check if a path is gitignored.
     * Translated from isPathGitignored() in gitignore.ts
     */
    public static CompletableFuture<Boolean> isPathGitignored(String filePath, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "check-ignore", filePath);
                pb.directory(new File(cwd));
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor(5, TimeUnit.SECONDS);
                return p.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Add a glob rule to .gitignore.
     * Translated from addFileGlobRuleToGitignore() in gitignore.ts
     */
    public static void addFileGlobRuleToGitignore(String repoPath, String glob) {
        String gitignorePath = repoPath + "/.gitignore";
        try {
            File gitignore = new File(gitignorePath);
            String content = gitignore.exists()
                ? Files.readString(gitignore.toPath())
                : "";

            if (!content.contains(glob)) {
                String newContent = content.endsWith("\n") || content.isEmpty()
                    ? content + glob + "\n"
                    : content + "\n" + glob + "\n";
                Files.writeString(gitignore.toPath(), newContent);
            }
        } catch (Exception e) {
            log.warn("Could not update .gitignore: {}", e.getMessage());
        }
    }

    private GitignoreUtils() {}
}
