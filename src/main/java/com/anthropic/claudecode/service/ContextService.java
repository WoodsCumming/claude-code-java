package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Context service for building system and user context injected into conversations.
 * Translated from src/context.ts
 *
 * Provides memoized system context (git status, branch, etc.) and user context
 * (CLAUDE.md memory files). Context is cached for the duration of a conversation.
 */
@Slf4j
@Service
public class ContextService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextService.class);


    private static final int MAX_STATUS_CHARS = 2000;

    private final ClaudeMdService claudeMdService;

    // System prompt injection for cache-breaking (ephemeral debugging state)
    private volatile String systemPromptInjection = null;

    // Memoized context caches (cleared when injection changes)
    private final AtomicReference<CompletableFuture<Map<String, String>>> systemContextCache
        = new AtomicReference<>(null);
    private final AtomicReference<CompletableFuture<Map<String, String>>> userContextCache
        = new AtomicReference<>(null);

    @Autowired
    public ContextService(ClaudeMdService claudeMdService) {
        this.claudeMdService = claudeMdService;
    }

    // =========================================================================
    // System prompt injection (ant-only ephemeral debugging)
    // =========================================================================

    /**
     * Get the current system prompt injection value.
     * Translated from getSystemPromptInjection() in context.ts
     */
    public String getSystemPromptInjection() {
        return systemPromptInjection;
    }

    /**
     * Set system prompt injection and clear context caches immediately.
     * Translated from setSystemPromptInjection() in context.ts
     */
    public void setSystemPromptInjection(String value) {
        this.systemPromptInjection = value;
        // Clear context caches immediately when injection changes
        systemContextCache.set(null);
        userContextCache.set(null);
    }

    // =========================================================================
    // Git status (memoized)
    // =========================================================================

    /**
     * Get formatted git status for the current working directory.
     * Truncates at MAX_STATUS_CHARS. Returns null if not a git repo or test env.
     * Translated from getGitStatus() memoized function in context.ts
     */
    public CompletableFuture<String> getGitStatus() {
        return CompletableFuture.supplyAsync(() -> {
            String cwd = System.getProperty("user.dir");
            log.debug("git_status_started");

            if (!isGitRepo(cwd)) {
                log.debug("git_status_skipped_not_git");
                return null;
            }

            try {
                String branch = runGit(cwd, "--no-optional-locks", "rev-parse", "--abbrev-ref", "HEAD");
                String defaultBranch = getDefaultBranch(cwd);
                String status = runGit(cwd, "--no-optional-locks", "status", "--short");
                String log5 = runGit(cwd, "--no-optional-locks", "log", "--oneline", "-n", "5");
                String userName = runGit(cwd, "config", "user.name");

                // Truncate status if too long
                String truncatedStatus = status != null && status.length() > MAX_STATUS_CHARS
                    ? status.substring(0, MAX_STATUS_CHARS)
                      + "\n... (truncated because it exceeds 2k characters. "
                      + "If you need more information, run \"git status\" using BashTool)"
                    : status;

                List<String> parts = new ArrayList<>();
                parts.add("This is the git status at the start of the conversation. "
                    + "Note that this status is a snapshot in time, and will not update during the conversation.");
                parts.add("Current branch: " + (branch != null ? branch.trim() : "unknown"));
                parts.add("Main branch (you will usually use this for PRs): "
                    + (defaultBranch != null ? defaultBranch.trim() : "main"));
                if (userName != null && !userName.isBlank()) {
                    parts.add("Git user: " + userName.trim());
                }
                String statusDisplay = (truncatedStatus != null && !truncatedStatus.isBlank())
                    ? truncatedStatus.trim() : "(clean)";
                parts.add("Status:\n" + statusDisplay);
                if (log5 != null && !log5.isBlank()) {
                    parts.add("Recent commits:\n" + log5.trim());
                }

                return String.join("\n\n", parts);
            } catch (Exception e) {
                log.debug("git_status_failed: {}", e.getMessage());
                return null;
            }
        });
    }

    // =========================================================================
    // System context (memoized)
    // =========================================================================

    /**
     * Get system context map prepended to each conversation.
     * Keys: "gitStatus", "cacheBreaker" (when injection set).
     * Translated from getSystemContext() memoized function in context.ts
     */
    public CompletableFuture<Map<String, String>> getSystemContext() {
        CompletableFuture<Map<String, String>> cached = systemContextCache.get();
        if (cached != null) return cached;

        CompletableFuture<Map<String, String>> future = CompletableFuture.supplyAsync(() -> {
            log.debug("system_context_started");
            Map<String, String> context = new LinkedHashMap<>();

            try {
                String gitStatus = getGitStatus().get();
                if (gitStatus != null) {
                    context.put("gitStatus", gitStatus);
                }
            } catch (Exception e) {
                log.debug("Failed to get git status for system context: {}", e.getMessage());
            }

            // Include system prompt injection if set (for cache breaking, ant-only)
            String injection = systemPromptInjection;
            if (injection != null) {
                context.put("cacheBreaker", "[CACHE_BREAKER: " + injection + "]");
            }

            log.debug("system_context_completed");
            return context;
        });

        systemContextCache.compareAndSet(null, future);
        return systemContextCache.get();
    }

    // =========================================================================
    // User context (memoized)
    // =========================================================================

    /**
     * Get user context map prepended to each conversation.
     * Keys: "claudeMd" (when CLAUDE.md content exists), "currentDate".
     * Translated from getUserContext() memoized function in context.ts
     */
    public CompletableFuture<Map<String, String>> getUserContext() {
        CompletableFuture<Map<String, String>> cached = userContextCache.get();
        if (cached != null) return cached;

        CompletableFuture<Map<String, String>> future = CompletableFuture.supplyAsync(() -> {
            log.debug("user_context_started");
            Map<String, String> context = new LinkedHashMap<>();

            // Load CLAUDE.md memory files
            String cwd = System.getProperty("user.dir");
            boolean shouldDisableClaudeMd = Boolean.parseBoolean(
                System.getenv("CLAUDE_CODE_DISABLE_CLAUDE_MDS"));

            if (!shouldDisableClaudeMd) {
                try {
                    List<ClaudeMdService.MemoryFileInfo> memoryFiles =
                        claudeMdService.getMemoryFiles(cwd);
                    if (!memoryFiles.isEmpty()) {
                        String claudeMdContent = claudeMdService.buildMemoryPrompt(memoryFiles);
                        if (claudeMdContent != null && !claudeMdContent.isBlank()) {
                            context.put("claudeMd", claudeMdContent);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to load CLAUDE.md: {}", e.getMessage());
                }
            }

            context.put("currentDate", "Today's date is " + LocalDate.now() + ".");

            log.debug("user_context_completed");
            return context;
        });

        userContextCache.compareAndSet(null, future);
        return userContextCache.get();
    }

    /**
     * Clear both context caches (e.g. on setSystemPromptInjection).
     */
    /** Clear file suggestion caches. */
    public void clearFileSuggestionCaches() {}

    /** Clear repository caches. */
    public void clearRepositoryCaches() {}

    /** Clear command prefix caches. */
    public void clearCommandPrefixCaches() {}

    /** Clear git dir resolution cache. */
    public void clearResolveGitDirCache() {}

    /** Clear all caches (alias for clearContextCaches). */
    public void clearCaches() { clearContextCaches(); }

    /** Async alias for getUserContext(). */
    public java.util.concurrent.CompletableFuture<java.util.Map<String, String>> getUserContextAsync() {
        return getUserContext();
    }

    /** Async alias for getSystemContext(). */
    public java.util.concurrent.CompletableFuture<java.util.Map<String, String>> getSystemContextAsync() {
        return getSystemContext();
    }

    public void clearContextCaches() {
        systemContextCache.set(null);
        userContextCache.set(null);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private boolean isGitRepo(String cwd) {
        if (cwd == null) return false;
        try {
            Process proc = new ProcessBuilder("git", "-C", cwd, "rev-parse", "--git-dir")
                .redirectErrorStream(true)
                .start();
            proc.waitFor(5, TimeUnit.SECONDS);
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runGit(String cwd, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(cwd);
            cmd.addAll(Arrays.asList(args));
            Process proc = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
            proc.waitFor(10, TimeUnit.SECONDS);
            return new String(proc.getInputStream().readAllBytes());
        } catch (Exception e) {
            log.debug("git command failed: {}", e.getMessage());
            return null;
        }
    }

    private String getDefaultBranch(String cwd) {
        // Try remote HEAD, fallback to "main", then "master"
        String remote = runGit(cwd, "--no-optional-locks", "rev-parse", "--abbrev-ref", "origin/HEAD");
        if (remote != null && !remote.isBlank()) {
            String trimmed = remote.trim();
            if (trimmed.startsWith("origin/")) return trimmed.substring("origin/".length());
            return trimmed;
        }
        // Check if main or master branch exists
        for (String branch : List.of("main", "master")) {
            String result = runGit(cwd, "rev-parse", "--verify", branch);
            if (result != null && !result.isBlank()) return branch;
        }
        return "main";
    }
}
