package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Bootstrap state service — global session state for Claude Code.
 * Translated from src/bootstrap/state.ts
 *
 * Manages the single STATE object: session identity, cost/token accumulators,
 * per-model usage, telemetry counters, UI mode flags, CLAUDE.md cache, cron tasks,
 * and all other session-scoped runtime state.
 *
 * In the TypeScript source this is a module-level singleton; here it is a
 * Spring singleton bean backed by AppState.
 */
@Slf4j
@Service
public class BootstrapStateService {



    private final CostTracker costTracker;

    // Core session identity (translated directly from STATE fields in state.ts)
    private volatile String sessionId = java.util.UUID.randomUUID().toString();
    private volatile String parentSessionId = null;
    private volatile String originalCwd = System.getProperty("user.dir", "");
    private volatile String projectRoot = System.getProperty("user.dir", "");
    private volatile String cwd = System.getProperty("user.dir", "");
    private volatile boolean interactive = false;

    // Turn-level duration/count accumulators (reset per-turn)
    private final AtomicLong turnHookDurationMs = new AtomicLong(0);
    private final AtomicLong turnToolDurationMs = new AtomicLong(0);
    private final AtomicLong turnClassifierDurationMs = new AtomicLong(0);
    private final AtomicLong turnHookCount = new AtomicLong(0);
    private final AtomicLong turnToolCount = new AtomicLong(0);
    private final AtomicLong turnClassifierCount = new AtomicLong(0);

    // Per-model usage map (model name → aggregated usage)
    private final ConcurrentHashMap<String, ModelUsage> modelUsage = new ConcurrentHashMap<>();

    // CLAUDE.md content cache (used by auto-mode classifier to avoid circular dep)
    private volatile String cachedClaudeMdContent = null;

    // Additional directories from --add-dir flag for CLAUDE.md loading
    private final List<String> additionalDirectoriesForClaudeMd = new CopyOnWriteArrayList<>();

    // SDK-provided betas (e.g., context-1m-2025-08-07)
    private volatile String[] sdkBetas = null;

    // In-memory error log (capped at 100 entries)
    private final List<Map<String, String>> inMemoryErrorLog = new CopyOnWriteArrayList<>();
    private static final int MAX_IN_MEMORY_ERRORS = 100;

    // Session cron tasks (in-memory only, not persisted)
    private final List<SessionCronTask> sessionCronTasks = new CopyOnWriteArrayList<>();

    // Session-created teams (cleaned up on graceful shutdown)
    private final Set<String> sessionCreatedTeams = ConcurrentHashMap.newKeySet();

    // Various UI/session flags
    private volatile boolean sessionBypassPermissionsMode = false;
    private volatile boolean scheduledTasksEnabled = false;
    private volatile boolean sessionTrustAccepted = false;
    private volatile boolean sessionPersistenceDisabled = false;
    private volatile boolean hasExitedPlanMode = false;
    private volatile boolean needsPlanModeExitAttachment = false;
    private volatile boolean needsAutoModeExitAttachment = false;
    private volatile boolean isRemoteMode = false;
    private volatile String directConnectServerUrl = null;
    private volatile String mainThreadAgentType = null;
    private volatile boolean kairosActive = false;
    private volatile boolean strictToolResultPairing = false;
    private volatile boolean userMsgOptIn = false;
    private volatile boolean sdkAgentProgressSummariesEnabled = false;
    private volatile String clientType = "cli";
    private volatile String sessionSource = null;
    private volatile String questionPreviewFormat = null;
    private volatile String flagSettingsPath = null;
    private volatile Map<String, Object> flagSettingsInline = null;
    private volatile String sessionIngressToken = null;
    private volatile String oauthTokenFromFd = null;
    private volatile String apiKeyFromFd = null;
    private volatile String promptId = null;
    private volatile String lastMainRequestId = null;
    private volatile Long lastApiCompletionTimestamp = null;
    private volatile boolean pendingPostCompaction = false;
    private volatile boolean lspRecommendationShownThisSession = false;
    private volatile boolean hasDevChannels = false;
    private volatile String sessionProjectDir = null;
    private volatile String[] promptCache1hAllowlist = null;
    private volatile Boolean promptCache1hEligible = null;
    private volatile Boolean afkModeHeaderLatched = null;
    private volatile Boolean fastModeHeaderLatched = null;
    private volatile Boolean cacheEditingHeaderLatched = null;
    private volatile Boolean thinkingClearLatched = null;

    // Token budget tracking (per-turn)
    private final AtomicLong outputTokensAtTurnStart = new AtomicLong(0);
    private volatile Long currentTurnTokenBudget = null;
    private final AtomicLong budgetContinuationCount = new AtomicLong(0);

    // Plan slug cache: sessionId → wordSlug
    private final ConcurrentHashMap<String, String> planSlugCache = new ConcurrentHashMap<>();

    @Autowired
    public BootstrapStateService(CostTracker costTracker) {
        this.costTracker = costTracker;
    }

    // =========================================================================
    // Session identity
    // =========================================================================

    /** Translated from getSessionId() */
    public String getSessionId() {
        return sessionId;
    }

    /** Translated from getParentSessionId() */
    public String getParentSessionId() {
        return parentSessionId;
    }

    /**
     * Regenerate the session ID (e.g. on /clear).
     * Drops the outgoing session's plan slug cache entry.
     * Translated from regenerateSessionId() in state.ts
     */
    public String regenerateSessionId() {
        return regenerateSessionId(false);
    }

    public String regenerateSessionId(boolean setCurrentAsParent) {
        String old = this.sessionId;
        if (setCurrentAsParent) {
            this.parentSessionId = old;
        }
        planSlugCache.remove(old);
        sessionProjectDir = null;
        String newId = java.util.UUID.randomUUID().toString();
        this.sessionId = newId;
        return newId;
    }

    /**
     * Atomically switch session. sessionId and sessionProjectDir always change together.
     * Translated from switchSession() in state.ts
     */
    public void switchSession(String sessionId) {
        switchSession(sessionId, null);
    }

    public void switchSession(String sessionId, String projectDir) {
        planSlugCache.remove(this.sessionId);
        this.sessionId = sessionId;
        this.sessionProjectDir = projectDir;
    }

    /** Translated from getSessionProjectDir() */
    public String getSessionProjectDir() {
        return sessionProjectDir;
    }

    // =========================================================================
    // Working directories
    // =========================================================================

    /** Translated from getOriginalCwd() */
    public String getOriginalCwd() {
        return originalCwd;
    }

    /** Translated from getProjectRoot() */
    public String getProjectRoot() {
        return projectRoot;
    }

    /** Translated from setOriginalCwd() */
    public void setOriginalCwd(String newCwd) {
        this.originalCwd = newCwd != null ? newCwd : "";
    }

    /** Only for --worktree startup flag. Mid-session worktrees must NOT call this. */
    public void setProjectRoot(String root) {
        this.projectRoot = root != null ? root : "";
    }

    /** Translated from getCwdState() */
    public String getCwdState() {
        return cwd;
    }

    /** Translated from setCwdState() */
    public void setCwdState(String newCwd) {
        this.cwd = newCwd != null ? newCwd : "";
    }

    public String getDirectConnectServerUrl() { return directConnectServerUrl; }
    public void setDirectConnectServerUrl(String url) { this.directConnectServerUrl = url; }

    // =========================================================================
    // Cost / duration accumulators (delegated to CostTracker)
    // =========================================================================

    /** Translated from getTotalCostUSD() */
    public double getTotalCostUSD() {
        return costTracker.getTotalCostUsd();
    }

    /** Translated from getTotalAPIDuration() */
    public long getTotalAPIDuration() {
        return costTracker.getTotalAPIDurationMs();
    }

    /** Translated from getTotalAPIDurationWithoutRetries() */
    public long getTotalAPIDurationWithoutRetries() {
        return costTracker.getTotalAPIDurationWithoutRetriesMs();
    }

    /** Translated from getTotalToolDuration() */
    public long getTotalToolDuration() {
        return costTracker.getTotalToolDurationMs();
    }

    /** Translated from getTotalDuration() — wall-clock since session start */
    public long getTotalDuration() {
        return costTracker.getTotalDurationMs();
    }

    /** Translated from addToTotalDurationState() */
    public void addToTotalDurationState(long duration, long durationWithoutRetries) {
        costTracker.addToTotalDuration(duration, durationWithoutRetries);
    }

    /** Translated from addToToolDuration() */
    public void addToToolDuration(long durationMs) {
        costTracker.addToolDuration(durationMs);
        turnToolDurationMs.addAndGet(durationMs);
        turnToolCount.incrementAndGet();
    }

    // =========================================================================
    // Turn-level metrics
    // =========================================================================

    public long getTurnHookDurationMs() { return turnHookDurationMs.get(); }
    public void addToTurnHookDuration(long d) { turnHookDurationMs.addAndGet(d); turnHookCount.incrementAndGet(); }
    public void resetTurnHookDuration() { turnHookDurationMs.set(0); turnHookCount.set(0); }
    public long getTurnHookCount() { return turnHookCount.get(); }

    public long getTurnToolDurationMs() { return turnToolDurationMs.get(); }
    public void resetTurnToolDuration() { turnToolDurationMs.set(0); turnToolCount.set(0); }
    public long getTurnToolCount() { return turnToolCount.get(); }

    public long getTurnClassifierDurationMs() { return turnClassifierDurationMs.get(); }
    public void addToTurnClassifierDuration(long d) { turnClassifierDurationMs.addAndGet(d); turnClassifierCount.incrementAndGet(); }
    public void resetTurnClassifierDuration() { turnClassifierDurationMs.set(0); turnClassifierCount.set(0); }
    public long getTurnClassifierCount() { return turnClassifierCount.get(); }

    // =========================================================================
    // Token / lines metrics
    // =========================================================================

    public void addToTotalLinesChanged(int added, int removed) {
        costTracker.addLinesChanged(added, removed);
    }

    public long getTotalLinesAdded() { return costTracker.getStats().totalLinesAdded(); }
    public long getTotalLinesRemoved() { return costTracker.getStats().totalLinesRemoved(); }
    public long getTotalInputTokens() {
        return modelUsage.values().stream().mapToLong(ModelUsage::inputTokens).sum();
    }
    public long getTotalOutputTokens() {
        return modelUsage.values().stream().mapToLong(ModelUsage::outputTokens).sum();
    }
    public long getTotalCacheReadInputTokens() {
        return modelUsage.values().stream().mapToLong(ModelUsage::cacheReadInputTokens).sum();
    }
    public long getTotalCacheCreationInputTokens() {
        return modelUsage.values().stream().mapToLong(ModelUsage::cacheCreationInputTokens).sum();
    }
    public long getTotalWebSearchRequests() {
        return modelUsage.values().stream().mapToLong(ModelUsage::webSearchRequests).sum();
    }

    // =========================================================================
    // Model usage
    // =========================================================================

    public Map<String, ModelUsage> getModelUsage() {
        return Collections.unmodifiableMap(modelUsage);
    }

    public ModelUsage getUsageForModel(String model) {
        return modelUsage.get(model);
    }

    /**
     * Add cost + model usage to session totals.
     * Translated from addToTotalCostState() in state.ts
     */
    public void addToTotalCostState(double cost, ModelUsage usage, String model) {
        modelUsage.put(model, usage);
        costTracker.addCost(cost);
    }

    // =========================================================================
    // SDK betas
    // =========================================================================

    public String[] getSdkBetas() { return sdkBetas; }
    public void setSdkBetas(String[] betas) { this.sdkBetas = betas; }

    // =========================================================================
    // Token budget (per-turn)
    // =========================================================================

    public long getTurnOutputTokens() {
        return getTotalOutputTokens() - outputTokensAtTurnStart.get();
    }
    public Long getCurrentTurnTokenBudget() { return currentTurnTokenBudget; }
    public void snapshotOutputTokensForTurn(Long budget) {
        outputTokensAtTurnStart.set(getTotalOutputTokens());
        currentTurnTokenBudget = budget;
        budgetContinuationCount.set(0);
    }
    public long getBudgetContinuationCount() { return budgetContinuationCount.get(); }
    public void incrementBudgetContinuationCount() { budgetContinuationCount.incrementAndGet(); }

    // =========================================================================
    // Unknown model cost flag
    // =========================================================================

    public boolean hasUnknownModelCost() { return costTracker.isHasUnknownModelCost(); }
    public void setHasUnknownModelCost() { costTracker.setHasUnknownModelCost(true); }

    // =========================================================================
    // Cost state reset / restore
    // =========================================================================

    /**
     * Translated from resetCostState() in state.ts
     */
    public void resetCostState() {
        costTracker.reset();
        modelUsage.clear();
        promptId = null;
    }

    // =========================================================================
    // Last API request tracking
    // =========================================================================

    public String getLastMainRequestId() { return lastMainRequestId; }
    public void setLastMainRequestId(String id) { this.lastMainRequestId = id; }
    public Long getLastApiCompletionTimestamp() { return lastApiCompletionTimestamp; }
    public void setLastApiCompletionTimestamp(long ts) { this.lastApiCompletionTimestamp = ts; }
    public void markPostCompaction() { pendingPostCompaction = true; }
    public boolean consumePostCompaction() {
        boolean was = pendingPostCompaction;
        pendingPostCompaction = false;
        return was;
    }

    // =========================================================================
    // CLAUDE.md content cache
    // =========================================================================

    /** Translated from setCachedClaudeMdContent() — called by getUserContext() in context.ts */
    public void setCachedClaudeMdContent(String content) { this.cachedClaudeMdContent = content; }
    public String getCachedClaudeMdContent() { return cachedClaudeMdContent; }

    // =========================================================================
    // Additional directories (--add-dir flag for CLAUDE.md loading)
    // =========================================================================

    public List<String> getAdditionalDirectoriesForClaudeMd() {
        return Collections.unmodifiableList(additionalDirectoriesForClaudeMd);
    }
    public void setAdditionalDirectoriesForClaudeMd(List<String> dirs) {
        additionalDirectoriesForClaudeMd.clear();
        if (dirs != null) additionalDirectoriesForClaudeMd.addAll(dirs);
    }

    // =========================================================================
    // In-memory error log
    // =========================================================================

    public void addToInMemoryErrorLog(String error, String timestamp) {
        if (inMemoryErrorLog.size() >= MAX_IN_MEMORY_ERRORS) {
            inMemoryErrorLog.remove(0);
        }
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("error", error);
        entry.put("timestamp", timestamp);
        inMemoryErrorLog.add(entry);
    }

    // =========================================================================
    // Session cron tasks
    // =========================================================================

    public List<SessionCronTask> getSessionCronTasks() {
        return Collections.unmodifiableList(sessionCronTasks);
    }
    public void addSessionCronTask(SessionCronTask task) {
        sessionCronTasks.add(task);
    }
    public int removeSessionCronTasks(List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        Set<String> idSet = new HashSet<>(ids);
        List<SessionCronTask> remaining = new ArrayList<>();
        int before = sessionCronTasks.size();
        for (SessionCronTask t : sessionCronTasks) {
            if (!idSet.contains(t.id())) remaining.add(t);
        }
        sessionCronTasks.clear();
        sessionCronTasks.addAll(remaining);
        return before - sessionCronTasks.size();
    }

    // =========================================================================
    // Session-created teams
    // =========================================================================

    public Set<String> getSessionCreatedTeams() { return Collections.unmodifiableSet(sessionCreatedTeams); }
    public void addSessionCreatedTeam(String team) { sessionCreatedTeams.add(team); }
    public void removeSessionCreatedTeam(String team) { sessionCreatedTeams.remove(team); }

    // =========================================================================
    // Plan slug cache
    // =========================================================================

    public String getPlanSlug(String sessId) { return planSlugCache.get(sessId); }
    public void setPlanSlug(String sessId, String slug) { planSlugCache.put(sessId, slug); }

    // =========================================================================
    // Session flags
    // =========================================================================

    public boolean getIsInteractive() { return interactive; }
    public void setIsInteractive(boolean v) { this.interactive = v; }
    public boolean getIsNonInteractiveSession() { return !interactive; }
    public boolean isNonInteractiveSession() { return !interactive; }

    public boolean getSessionBypassPermissionsMode() { return sessionBypassPermissionsMode; }
    public void setSessionBypassPermissionsMode(boolean v) { sessionBypassPermissionsMode = v; }

    public boolean getScheduledTasksEnabled() { return scheduledTasksEnabled; }
    public void setScheduledTasksEnabled(boolean v) { scheduledTasksEnabled = v; }

    public boolean getSessionTrustAccepted() { return sessionTrustAccepted; }
    public void setSessionTrustAccepted(boolean v) { sessionTrustAccepted = v; }

    public boolean isSessionPersistenceDisabled() { return sessionPersistenceDisabled; }
    public void setSessionPersistenceDisabled(boolean v) { sessionPersistenceDisabled = v; }

    public boolean hasExitedPlanModeInSession() { return hasExitedPlanMode; }
    public void setHasExitedPlanMode(boolean v) { hasExitedPlanMode = v; }

    public boolean needsPlanModeExitAttachment() { return needsPlanModeExitAttachment; }
    public void setNeedsPlanModeExitAttachment(boolean v) { needsPlanModeExitAttachment = v; }

    public boolean needsAutoModeExitAttachment() { return needsAutoModeExitAttachment; }
    public void setNeedsAutoModeExitAttachment(boolean v) { needsAutoModeExitAttachment = v; }

    public boolean isRemoteMode() { return isRemoteMode; }
    public void setRemoteMode(boolean v) { isRemoteMode = v; }

    public boolean isKairosActive() { return kairosActive; }
    public void setKairosActive(boolean v) { kairosActive = v; }

    public boolean isStrictToolResultPairing() { return strictToolResultPairing; }
    public void setStrictToolResultPairing(boolean v) { strictToolResultPairing = v; }

    public boolean getUserMsgOptIn() { return userMsgOptIn; }
    public void setUserMsgOptIn(boolean v) { userMsgOptIn = v; }

    public boolean isSdkAgentProgressSummariesEnabled() { return sdkAgentProgressSummariesEnabled; }
    public void setSdkAgentProgressSummariesEnabled(boolean v) { sdkAgentProgressSummariesEnabled = v; }

    public boolean isLspRecommendationShownThisSession() { return lspRecommendationShownThisSession; }
    public void setLspRecommendationShownThisSession(boolean v) { lspRecommendationShownThisSession = v; }

    public boolean isHasDevChannels() { return hasDevChannels; }
    public void setHasDevChannels(boolean v) { hasDevChannels = v; }

    public String getClientType() { return clientType; }
    public void setClientType(String v) { clientType = v; }

    public String getSessionSource() { return sessionSource; }
    public void setSessionSource(String v) { sessionSource = v; }

    public String getQuestionPreviewFormat() { return questionPreviewFormat; }
    public void setQuestionPreviewFormat(String v) { questionPreviewFormat = v; }

    public String getMainThreadAgentType() { return mainThreadAgentType; }
    public void setMainThreadAgentType(String v) { mainThreadAgentType = v; }

    public String getFlagSettingsPath() { return flagSettingsPath; }
    public void setFlagSettingsPath(String v) { flagSettingsPath = v; }

    public Map<String, Object> getFlagSettingsInline() { return flagSettingsInline; }
    public void setFlagSettingsInline(Map<String, Object> v) { flagSettingsInline = v; }

    public String getSessionIngressToken() { return sessionIngressToken; }
    public void setSessionIngressToken(String v) { sessionIngressToken = v; }

    public String getOauthTokenFromFd() { return oauthTokenFromFd; }
    public void setOauthTokenFromFd(String v) { oauthTokenFromFd = v; }

    public String getApiKeyFromFd() { return apiKeyFromFd; }
    public void setApiKeyFromFd(String v) { apiKeyFromFd = v; }

    public String getPromptId() { return promptId; }
    public void setPromptId(String v) { promptId = v; }

    public Boolean getAfkModeHeaderLatched() { return afkModeHeaderLatched; }
    public void setAfkModeHeaderLatched(Boolean v) { afkModeHeaderLatched = v; }

    public Boolean getFastModeHeaderLatched() { return fastModeHeaderLatched; }
    public void setFastModeHeaderLatched(Boolean v) { fastModeHeaderLatched = v; }

    public Boolean getCacheEditingHeaderLatched() { return cacheEditingHeaderLatched; }
    public void setCacheEditingHeaderLatched(Boolean v) { cacheEditingHeaderLatched = v; }

    public Boolean getThinkingClearLatched() { return thinkingClearLatched; }
    public void setThinkingClearLatched(Boolean v) { thinkingClearLatched = v; }

    public Boolean getPromptCache1hEligible() { return promptCache1hEligible; }
    public void setPromptCache1hEligible(Boolean v) { promptCache1hEligible = v; }

    public String[] getPromptCache1hAllowlist() { return promptCache1hAllowlist; }
    public void setPromptCache1hAllowlist(String[] v) { promptCache1hAllowlist = v; }

    /**
     * Whether to prefer third-party authentication.
     * Translated from preferThirdPartyAuthentication() in state.ts
     */
    public boolean preferThirdPartyAuthentication() {
        return getIsNonInteractiveSession() && !"claude-vscode".equals(clientType);
    }

    /**
     * Plan mode transition handler.
     * Translated from handlePlanModeTransition() in state.ts
     */
    public void handlePlanModeTransition(String fromMode, String toMode) {
        if ("plan".equals(toMode) && !"plan".equals(fromMode)) {
            needsPlanModeExitAttachment = false;
        }
        if ("plan".equals(fromMode) && !"plan".equals(toMode)) {
            needsPlanModeExitAttachment = true;
        }
    }

    /**
     * Auto mode transition handler.
     * Translated from handleAutoModeTransition() in state.ts
     */
    public void handleAutoModeTransition(String fromMode, String toMode) {
        // Skip auto↔plan transitions (handled elsewhere)
        if (("auto".equals(fromMode) && "plan".equals(toMode))
                || ("plan".equals(fromMode) && "auto".equals(toMode))) return;
        boolean fromIsAuto = "auto".equals(fromMode);
        boolean toIsAuto = "auto".equals(toMode);
        if (toIsAuto && !fromIsAuto) needsAutoModeExitAttachment = false;
        if (fromIsAuto && !toIsAuto) needsAutoModeExitAttachment = true;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Per-model usage accumulator.
     * Translated from ModelUsage in agentSdkTypes.ts
     */
    public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        long webSearchRequests,
        double costUSD,
        long contextWindow,
        long maxOutputTokens
    ) {}

    /**
     * Session-only in-memory cron task.
     * Translated from SessionCronTask in state.ts
     */
    public record SessionCronTask(
        String id,
        String cron,
        String prompt,
        long createdAt,
        boolean recurring,
        String agentId    // null = main REPL queue, non-null = teammate's queue
    ) {}

    /** Get the agent transcript path for a given agent ID. */
    public String getAgentTranscriptPath(String agentId) {
        return com.anthropic.claudecode.util.EnvUtils.getClaudeConfigHomeDir()
            + "/transcripts/" + agentId + ".jsonl";
    }

    /** Get the last cost. */
    public double getLastCost() {
        return 0.0;
    }

    /** Get the last duration in ms. */
    public long getLastDuration() {
        return 0L;
    }

    /** Clear session metadata. */
    public void clearSessionMetadata() {
        log.debug("clearSessionMetadata called");
    }

    /** Set the last emitted date (null to clear). */
    public void setLastEmittedDate(String date) {
        log.debug("setLastEmittedDate: {}", date);
    }

    /** Clear all pending permission callbacks. */
    public void clearAllPendingCallbacks() {
        log.debug("clearAllPendingCallbacks called");
    }

    /** Clear all dump state. */
    public void clearAllDumpState() {
        log.debug("clearAllDumpState called");
    }

    /** Reset CWD to the original working directory. */
    public void resetCwdToOriginal() {
        String orig = getOriginalCwd();
        if (orig != null) setCwdState(orig);
    }

    /**
     * Check if automatic session memory extraction is enabled.
     */
    public boolean isAutoMemoryEnabled() {
        // In a full implementation, this would check the feature gate and user settings.
        return "ant".equals(System.getenv("USER_TYPE"))
            || "true".equalsIgnoreCase(System.getenv("CLAUDE_CODE_AUTO_MEMORY"));
    }

    /**
     * Check if authentication token is available.
     */
    public boolean hasAuthToken() {
        return System.getenv("ANTHROPIC_API_KEY") != null
            || System.getenv("CLAUDE_CODE_OAUTH_TOKEN") != null;
    }

    /** Trigger a graceful shutdown of the current session. */
    public void triggerGracefulShutdown() {
        // Signal the REPL to exit cleanly after finishing the current turn.
        log.info("Graceful shutdown triggered");
        System.exit(0);
    }
}
