package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.GitUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Git stash service.
 * Translated from src/utils/git.ts stashToCleanState and related functions
 */
@Slf4j
@Service
public class GitStashService {



    /**
     * Stash changes to get a clean state.
     * Translated from stashToCleanState() in git.ts
     */
    public CompletableFuture<Boolean> stashToCleanState(String repoPath, String message) {
        return CompletableFuture.supplyAsync(() -> {
            if (!GitUtils.getIsGit(repoPath)) return false;

            String stashMessage = message != null ? message
                : "Claude Code auto-stash - " + java.time.Instant.now().toString();

            try {
                // Add all files including untracked
                ProcessBuilder addPb = new ProcessBuilder("git", "add", "-A");
                addPb.directory(new File(repoPath));
                addPb.start().waitFor();

                // Stash
                ProcessBuilder stashPb = new ProcessBuilder(
                    "git", "stash", "push", "-m", stashMessage
                );
                stashPb.directory(new File(repoPath));
                int exitCode = stashPb.start().waitFor();
                return exitCode == 0;
            } catch (Exception e) {
                log.debug("Could not stash: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Pop the most recent stash.
     */
    public CompletableFuture<Boolean> stashPop(String repoPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "stash", "pop");
                pb.directory(new File(repoPath));
                int exitCode = pb.start().waitFor();
                return exitCode == 0;
            } catch (Exception e) {
                log.debug("Could not pop stash: {}", e.getMessage());
                return false;
            }
        });
    }
}
