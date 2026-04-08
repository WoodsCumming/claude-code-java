package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plan management service.
 * Translated from src/utils/plans.ts
 *
 * Manages plan files for plan-mode sessions. Supports slugged filenames,
 * session-scoped caching, and plan recovery from message history.
 */
@Slf4j
@Service
public class PlanService {



    private static final int MAX_SLUG_RETRIES = 10;

    /** Cache of sessionId -> plan slug. Mirrors getPlanSlugCache() in plans.ts */
    private final Map<String, String> planSlugCache = new ConcurrentHashMap<>();

    private final SessionService sessionService;

    @Autowired
    public PlanService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    // =========================================================================
    // Slug management
    // =========================================================================

    /**
     * Get or generate a word slug for the current session's plan.
     * The slug is generated lazily on first access and cached for the session.
     * If a plan file with the generated slug already exists, retries up to 10 times.
     * Translated from getPlanSlug() in plans.ts
     */
    public String getPlanSlug() {
        return getPlanSlug(sessionService.getCurrentSessionId());
    }

    public String getPlanSlug(String sessionId) {
        return planSlugCache.computeIfAbsent(sessionId, id -> {
            String plansDir = getPlansDirectory();
            new File(plansDir).mkdirs();

            String slug = null;
            for (int i = 0; i < MAX_SLUG_RETRIES; i++) {
                slug = generateWordSlug();
                File planFile = new File(plansDir + "/" + slug + ".md");
                if (!planFile.exists()) {
                    break;
                }
            }
            return slug != null ? slug : UUID.randomUUID().toString().substring(0, 8);
        });
    }

    /**
     * Set a specific plan slug for a session (used when resuming a session).
     * Translated from setPlanSlug() in plans.ts
     */
    public void setPlanSlug(String sessionId, String slug) {
        planSlugCache.put(sessionId, slug);
    }

    /**
     * Clear the plan slug for the current session.
     * Should be called on /clear to ensure a fresh plan file is used.
     * Translated from clearPlanSlug() in plans.ts
     */
    public void clearPlanSlug() {
        clearPlanSlug(sessionService.getCurrentSessionId());
    }

    public void clearPlanSlug(String sessionId) {
        planSlugCache.remove(sessionId);
    }

    /**
     * Clear ALL plan slug entries (all sessions).
     * Use this on /clear to free sub-session slug entries.
     * Translated from clearAllPlanSlugs() in plans.ts
     */
    public void clearAllPlanSlugs() {
        planSlugCache.clear();
    }

    // =========================================================================
    // File paths
    // =========================================================================

    /**
     * Get the plans directory.
     * Translated from getPlansDirectory() in plans.ts
     */
    public String getPlansDirectory() {
        return EnvUtils.getClaudeConfigHomeDir() + "/plans";
    }

    /**
     * Get the file path for the current session's plan.
     * For the main conversation (no agentId), returns {planSlug}.md.
     * Translated from getPlanFilePath() in plans.ts
     */
    public String getPlanFilePath() {
        return getPlanFilePath(null);
    }

    /**
     * Get the file path for a session's plan.
     *
     * @param agentId Optional agent ID for subagents. If null, returns the main session plan.
     *                Main conversation: {planSlug}.md
     *                Subagents: {planSlug}-agent-{agentId}.md
     * Translated from getPlanFilePath() in plans.ts
     */
    public String getPlanFilePath(String agentId) {
        String planSlug = getPlanSlug(sessionService.getCurrentSessionId());
        if (agentId == null || agentId.isBlank()) {
            return getPlansDirectory() + "/" + planSlug + ".md";
        }
        return getPlansDirectory() + "/" + planSlug + "-agent-" + agentId + ".md";
    }

    // =========================================================================
    // Plan content access
    // =========================================================================

    /**
     * Get the plan content for the current session.
     * Translated from getPlan() in plans.ts
     */
    public Optional<String> getPlan() {
        return getPlan(null);
    }

    /**
     * Get the plan content for a session.
     *
     * @param agentId Optional agent ID for subagents.
     * Translated from getPlan() in plans.ts
     */
    public Optional<String> getPlan(String agentId) {
        String filePath = getPlanFilePath(agentId);
        File planFile = new File(filePath);
        if (!planFile.exists()) return Optional.empty();

        try {
            return Optional.of(Files.readString(planFile.toPath()));
        } catch (IOException e) {
            log.warn("Could not read plan file {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Save plan content.
     */
    public void savePlan(String content) {
        savePlan(content, null);
    }

    public void savePlan(String content, String agentId) {
        String planPath = getPlanFilePath(agentId);
        try {
            new File(planPath).getParentFile().mkdirs();
            Files.writeString(Paths.get(planPath), content);
        } catch (IOException e) {
            log.error("Could not save plan: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Resume / fork helpers
    // =========================================================================

    /**
     * Restore plan slug from a resumed session.
     * Sets the slug in the session cache so getPlanSlug() returns it.
     * If the plan file is missing, attempts recovery from file snapshots
     * or message history.
     * Returns true if a plan file exists (or was recovered) for the slug.
     *
     * Translated from copyPlanForResume() in plans.ts
     *
     * @param slug            The plan slug extracted from the log's message history
     * @param recoveredContent The recovered plan content (may be null)
     * @param targetSessionId  The session ID to associate the plan slug with
     */
    public CompletableFuture<Boolean> copyPlanForResume(
            String slug, String recoveredContent, String targetSessionId) {

        return CompletableFuture.supplyAsync(() -> {
            if (slug == null || slug.isBlank()) {
                return false;
            }

            String sessionId = (targetSessionId != null)
                    ? targetSessionId
                    : sessionService.getCurrentSessionId();
            setPlanSlug(sessionId, slug);

            String planPath = getPlansDirectory() + "/" + slug + ".md";

            try {
                Files.readString(Paths.get(planPath));
                return true;
            } catch (IOException e) {
                // File missing — attempt recovery if content is provided
                if (recoveredContent != null && !recoveredContent.isEmpty()) {
                    try {
                        Files.writeString(Paths.get(planPath), recoveredContent);
                        log.info("Plan recovered from provided content, {} chars", recoveredContent.length());
                        return true;
                    } catch (IOException writeError) {
                        log.error("Failed to write recovered plan: {}", writeError.getMessage());
                        return false;
                    }
                }
                log.debug("Plan file missing during resume and no recovery content available: {}", planPath);
                return false;
            }
        });
    }

    /**
     * Copy a plan file for a forked session. Unlike copyPlanForResume (which reuses
     * the original slug), this generates a NEW slug for the forked session and
     * writes the original plan content to the new file. This prevents the original
     * and forked sessions from clobbering each other's plan files.
     *
     * Translated from copyPlanForFork() in plans.ts
     *
     * @param originalSlug    The slug from the original (source) session
     * @param targetSessionId The session ID for the forked session
     */
    public CompletableFuture<Boolean> copyPlanForFork(String originalSlug, String targetSessionId) {
        return CompletableFuture.supplyAsync(() -> {
            if (originalSlug == null || originalSlug.isBlank()) {
                return false;
            }

            String plansDir = getPlansDirectory();
            String originalPlanPath = plansDir + "/" + originalSlug + ".md";

            // Generate a new slug for the forked session (do NOT reuse the original)
            String newSlug = getPlanSlug(targetSessionId);
            String newPlanPath = plansDir + "/" + newSlug + ".md";

            try {
                Files.copy(Paths.get(originalPlanPath), Paths.get(newPlanPath));
                return true;
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("No such file")) {
                    return false;
                }
                log.error("Failed to copy plan for fork: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Clear the plan for the current session (removes file and cache entry).
     */
    public void clearPlan() {
        String sessionId = sessionService.getCurrentSessionId();
        String planPath = getPlanFilePath();
        planSlugCache.remove(sessionId);
        new File(planPath).delete();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Generate a word-based slug.
     * Simplified version of generateWordSlug() in words.ts
     */
    private String generateWordSlug() {
        String[] adjectives = {"swift", "brave", "calm", "dark", "epic", "fast", "good", "hard", "kind", "loud"};
        String[] nouns = {"plan", "code", "task", "work", "step", "goal", "path", "mode", "flow", "idea"};

        Random rand = new Random();
        String adj = adjectives[rand.nextInt(adjectives.length)];
        String noun = nouns[rand.nextInt(nouns.length)];
        int num = rand.nextInt(100);

        return adj + "-" + noun + "-" + num;
    }
}
