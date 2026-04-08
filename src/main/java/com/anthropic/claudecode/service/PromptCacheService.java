package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt cache break detection service.
 * Translated from src/services/api/promptCacheBreakDetection.ts
 *
 * Two-phase detection:
 *  Phase 1 (pre-call)  – recordPromptState(): stores a snapshot of
 *    system prompt, tool schemas, model, etc. and computes a diff
 *    vs the previous call.
 *  Phase 2 (post-call) – checkResponseForCacheBreak(): compares the
 *    API response's cache-read-token count to the previous value and,
 *    if a drop is detected, uses the pending diff to explain the break.
 *
 * Supports per-source tracking (repl_main_thread, sdk, agent:*, compact),
 * with a cap of MAX_TRACKED_SOURCES to bound memory growth.
 */
@Slf4j
@Service
public class PromptCacheService {



    // -----------------------------------------------------------------------
    // Constants  (from promptCacheBreakDetection.ts)
    // -----------------------------------------------------------------------

    private static final long CACHE_TTL_5MIN_MS  = 5L  * 60 * 1000;
    public  static final long CACHE_TTL_1HOUR_MS = 60L * 60 * 1000;
    private static final int  MIN_CACHE_MISS_TOKENS = 2_000;
    private static final int  MAX_TRACKED_SOURCES   = 10;

    private static final List<String> TRACKED_SOURCE_PREFIXES = List.of(
        "repl_main_thread", "sdk", "agent:custom", "agent:default", "agent:builtin"
    );

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** Tracks prompt-state history per (querySource, agentId) key. */
    private final Map<String, PreviousState> previousStateBySource = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /** Snapshot of everything that can affect the server-side cache key. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PromptStateSnapshot {
        private List<String> systemTextBlocks;  // text of each TextBlockParam
        private List<String> toolNames;         // tool names in order
        private List<Integer> toolHashes;       // per-tool schema hashes
        private String querySource;
        private String model;
        private String agentId;
        private boolean fastMode;
        private String globalCacheStrategy = "";
        private List<String> betas = List.of();
        private boolean autoModeActive;
        private boolean isUsingOverage;
        private boolean cachedMcEnabled;
        private String effortValue = "";
        private int extraBodyHash;

        public List<String> getSystemTextBlocks() { return systemTextBlocks; }
        public void setSystemTextBlocks(List<String> v) { systemTextBlocks = v; }
        public List<String> getToolNames() { return toolNames; }
        public void setToolNames(List<String> v) { toolNames = v; }
        public List<Integer> getToolHashes() { return toolHashes; }
        public void setToolHashes(List<Integer> v) { toolHashes = v; }
        public String getQuerySource() { return querySource; }
        public void setQuerySource(String v) { querySource = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public boolean isFastMode() { return fastMode; }
        public void setFastMode(boolean v) { fastMode = v; }
        public String getGlobalCacheStrategy() { return globalCacheStrategy; }
        public void setGlobalCacheStrategy(String v) { globalCacheStrategy = v; }
        public List<String> getBetas() { return betas; }
        public void setBetas(List<String> v) { betas = v; }
        public boolean isAutoModeActive() { return autoModeActive; }
        public void setAutoModeActive(boolean v) { autoModeActive = v; }
        public boolean isIsUsingOverage() { return isUsingOverage; }
        public void setIsUsingOverage(boolean v) { isUsingOverage = v; }
        public boolean isCachedMcEnabled() { return cachedMcEnabled; }
        public void setCachedMcEnabled(boolean v) { cachedMcEnabled = v; }
        public String getEffortValue() { return effortValue; }
        public void setEffortValue(String v) { effortValue = v; }
        public int getExtraBodyHash() { return extraBodyHash; }
        public void setExtraBodyHash(int v) { extraBodyHash = v; }
    }

    /** Internal per-source tracking state. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class PreviousState {
        private int  systemHash;
        private int  toolsHash;
        private int  cacheControlHash;
        private List<String> toolNames;
        private Map<String, Integer> perToolHashes;
        private int  systemCharCount;
        private String model;
        private boolean fastMode;
        private String globalCacheStrategy;
        private List<String> betas;
        private boolean autoModeActive;
        private boolean isUsingOverage;
        private boolean cachedMcEnabled;
        private String effortValue;
        private int extraBodyHash;
        private int callCount;
        private PendingChanges pendingChanges;
        private Integer prevCacheReadTokens;  // null = first call
        private boolean cacheDeletionsPending;
        private String diffableContent;        // lazily set

        public boolean isAutoModeActive() { return autoModeActive; }
        public void setAutoModeActive(boolean v) { autoModeActive = v; }
        public boolean isIsUsingOverage() { return isUsingOverage; }
        public void setIsUsingOverage(boolean v) { isUsingOverage = v; }
        public boolean isCachedMcEnabled() { return cachedMcEnabled; }
        public void setCachedMcEnabled(boolean v) { cachedMcEnabled = v; }
        public String getEffortValue() { return effortValue; }
        public void setEffortValue(String v) { effortValue = v; }
        public int getExtraBodyHash() { return extraBodyHash; }
        public void setExtraBodyHash(int v) { extraBodyHash = v; }
        public int getCallCount() { return callCount; }
        public void setCallCount(int v) { callCount = v; }
        public PendingChanges getPendingChanges() { return pendingChanges; }
        public void setPendingChanges(PendingChanges v) { pendingChanges = v; }
        public Integer getPrevCacheReadTokens() { return prevCacheReadTokens; }
        public void setPrevCacheReadTokens(Integer v) { prevCacheReadTokens = v; }
        public boolean isCacheDeletionsPending() { return cacheDeletionsPending; }
        public void setCacheDeletionsPending(boolean v) { cacheDeletionsPending = v; }
        public String getDiffableContent() { return diffableContent; }
        public void setDiffableContent(String v) { diffableContent = v; }
        public int getSystemHash() { return systemHash; }
        public void setSystemHash(int v) { systemHash = v; }
        public int getToolsHash() { return toolsHash; }
        public void setToolsHash(int v) { toolsHash = v; }
        public int getCacheControlHash() { return cacheControlHash; }
        public void setCacheControlHash(int v) { cacheControlHash = v; }
        public List<String> getToolNames() { return toolNames; }
        public void setToolNames(List<String> v) { toolNames = v; }
        public Map<String, Integer> getPerToolHashes() { return perToolHashes; }
        public void setPerToolHashes(Map<String, Integer> v) { perToolHashes = v; }
        public int getSystemCharCount() { return systemCharCount; }
        public void setSystemCharCount(int v) { systemCharCount = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public boolean isFastMode() { return fastMode; }
        public void setFastMode(boolean v) { fastMode = v; }
        public String getGlobalCacheStrategy() { return globalCacheStrategy; }
        public void setGlobalCacheStrategy(String v) { globalCacheStrategy = v; }
        public List<String> getBetas() { return betas; }
        public void setBetas(List<String> v) { betas = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class PendingChanges {
        private boolean systemPromptChanged;
        private boolean toolSchemasChanged;
        private boolean modelChanged;
        private boolean fastModeChanged;
        private boolean cacheControlChanged;
        private boolean globalCacheStrategyChanged;
        private boolean betasChanged;
        private boolean autoModeChanged;
        private boolean overageChanged;
        private boolean cachedMcChanged;
        private boolean effortChanged;
        private boolean extraBodyChanged;
        private int addedToolCount;
        private int removedToolCount;
        private int systemCharDelta;
        private List<String> addedTools = List.of();
        private List<String> removedTools = List.of();
        private List<String> changedToolSchemas = List.of();
        private String previousModel;
        private String newModel;
        private String prevGlobalCacheStrategy;
        private String newGlobalCacheStrategy;
        private List<String> addedBetas = List.of();
        private List<String> removedBetas = List.of();
        private String prevEffortValue;
        private String newEffortValue;
    }

    // -----------------------------------------------------------------------
    // getTrackingKey
    // -----------------------------------------------------------------------

    private String getTrackingKey(String querySource, String agentId) {
        if ("compact".equals(querySource)) return "repl_main_thread";
        for (String prefix : TRACKED_SOURCE_PREFIXES) {
            if (querySource != null && querySource.startsWith(prefix)) {
                return (agentId != null && !agentId.isBlank()) ? agentId : querySource;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // recordPromptState  (Phase 1)
    // -----------------------------------------------------------------------

    /**
     * Record the current prompt/tool state before an API call.
     * Detects what changed vs the previous call and stores pending changes
     * for Phase 2 to use.
     * Translated from recordPromptState() in promptCacheBreakDetection.ts
     */
    public void recordPromptState(PromptStateSnapshot snapshot) {
        try {
            String key = getTrackingKey(snapshot.getQuerySource(), snapshot.getAgentId());
            if (key == null) return;

            int systemHash = djb2(snapshot.getSystemTextBlocks().toString());
            int toolsHash  = djb2(snapshot.getToolNames().toString() + snapshot.getToolHashes().toString());
            int systemCharCount = snapshot.getSystemTextBlocks().stream()
                .mapToInt(String::length).sum();
            List<String> sortedBetas = new ArrayList<>(snapshot.getBetas());
            Collections.sort(sortedBetas);

            PreviousState prev = previousStateBySource.get(key);

            if (prev == null) {
                // Evict oldest if at capacity
                while (previousStateBySource.size() >= MAX_TRACKED_SOURCES) {
                    String oldest = previousStateBySource.keySet().iterator().next();
                    previousStateBySource.remove(oldest);
                }

                PreviousState state = new PreviousState();
                state.setSystemHash(systemHash);
                state.setToolsHash(toolsHash);
                state.setCacheControlHash(0);
                state.setToolNames(snapshot.getToolNames());
                state.setPerToolHashes(buildPerToolHashes(snapshot));
                state.setSystemCharCount(systemCharCount);
                state.setModel(snapshot.getModel());
                state.setFastMode(snapshot.isFastMode());
                state.setGlobalCacheStrategy(snapshot.getGlobalCacheStrategy());
                state.setBetas(sortedBetas);
                state.setAutoModeActive(snapshot.isAutoModeActive());
                state.setIsUsingOverage(snapshot.isIsUsingOverage());
                state.setCachedMcEnabled(snapshot.isCachedMcEnabled());
                state.setEffortValue(snapshot.getEffortValue());
                state.setExtraBodyHash(snapshot.getExtraBodyHash());
                state.setCallCount(1);
                state.setPendingChanges(null);
                state.setPrevCacheReadTokens(null);
                state.setCacheDeletionsPending(false);
                previousStateBySource.put(key, state);
                return;
            }

            prev.setCallCount(prev.getCallCount() + 1);

            boolean systemPromptChanged          = systemHash != prev.getSystemHash();
            boolean toolSchemasChanged           = toolsHash != prev.getToolsHash();
            boolean modelChanged                 = !snapshot.getModel().equals(prev.getModel());
            boolean fastModeChanged              = snapshot.isFastMode() != prev.isFastMode();
            boolean globalCacheStrategyChanged   = !snapshot.getGlobalCacheStrategy().equals(prev.getGlobalCacheStrategy());
            boolean betasChanged                 = !sortedBetas.equals(prev.getBetas());
            boolean autoModeChanged              = snapshot.isAutoModeActive() != prev.isAutoModeActive();
            boolean overageChanged               = snapshot.isIsUsingOverage() != prev.isIsUsingOverage();
            boolean cachedMcChanged              = snapshot.isCachedMcEnabled() != prev.isCachedMcEnabled();
            boolean effortChanged                = !snapshot.getEffortValue().equals(prev.getEffortValue());
            boolean extraBodyChanged             = snapshot.getExtraBodyHash() != prev.getExtraBodyHash();

            if (systemPromptChanged || toolSchemasChanged || modelChanged || fastModeChanged
                || globalCacheStrategyChanged || betasChanged || autoModeChanged
                || overageChanged || cachedMcChanged || effortChanged || extraBodyChanged) {

                Set<String> prevToolSet = new HashSet<>(prev.getToolNames());
                Set<String> newToolSet  = new HashSet<>(snapshot.getToolNames());
                Set<String> prevBetaSet = new HashSet<>(prev.getBetas());
                Set<String> newBetaSet  = new HashSet<>(sortedBetas);

                List<String> addedTools   = snapshot.getToolNames().stream().filter(n -> !prevToolSet.contains(n)).toList();
                List<String> removedTools = prev.getToolNames().stream().filter(n -> !newToolSet.contains(n)).toList();

                List<String> changedSchemas = new ArrayList<>();
                if (toolSchemasChanged) {
                    Map<String, Integer> newHashes = buildPerToolHashes(snapshot);
                    for (String name : snapshot.getToolNames()) {
                        if (!prevToolSet.contains(name)) continue;
                        Integer prevHash = prev.getPerToolHashes().get(name);
                        Integer newHash  = newHashes.get(name);
                        if (!Objects.equals(prevHash, newHash)) changedSchemas.add(name);
                    }
                    prev.setPerToolHashes(buildPerToolHashes(snapshot));
                }

                PendingChanges changes = new PendingChanges();
                changes.setSystemPromptChanged(systemPromptChanged);
                changes.setToolSchemasChanged(toolSchemasChanged);
                changes.setModelChanged(modelChanged);
                changes.setFastModeChanged(fastModeChanged);
                changes.setGlobalCacheStrategyChanged(globalCacheStrategyChanged);
                changes.setBetasChanged(betasChanged);
                changes.setAutoModeChanged(autoModeChanged);
                changes.setOverageChanged(overageChanged);
                changes.setCachedMcChanged(cachedMcChanged);
                changes.setEffortChanged(effortChanged);
                changes.setExtraBodyChanged(extraBodyChanged);
                changes.setAddedToolCount(addedTools.size());
                changes.setRemovedToolCount(removedTools.size());
                changes.setAddedTools(addedTools);
                changes.setRemovedTools(removedTools);
                changes.setChangedToolSchemas(changedSchemas);
                changes.setSystemCharDelta(systemCharCount - prev.getSystemCharCount());
                changes.setPreviousModel(prev.getModel());
                changes.setNewModel(snapshot.getModel());
                changes.setPrevGlobalCacheStrategy(prev.getGlobalCacheStrategy());
                changes.setNewGlobalCacheStrategy(snapshot.getGlobalCacheStrategy());
                changes.setAddedBetas(sortedBetas.stream().filter(b -> !prevBetaSet.contains(b)).toList());
                changes.setRemovedBetas(prev.getBetas().stream().filter(b -> !newBetaSet.contains(b)).toList());
                changes.setPrevEffortValue(prev.getEffortValue());
                changes.setNewEffortValue(snapshot.getEffortValue());
                prev.setPendingChanges(changes);
            } else {
                prev.setPendingChanges(null);
            }

            // Advance state
            prev.setSystemHash(systemHash);
            prev.setToolsHash(toolsHash);
            prev.setSystemCharCount(systemCharCount);
            prev.setModel(snapshot.getModel());
            prev.setFastMode(snapshot.isFastMode());
            prev.setGlobalCacheStrategy(snapshot.getGlobalCacheStrategy());
            prev.setBetas(sortedBetas);
            prev.setAutoModeActive(snapshot.isAutoModeActive());
            prev.setIsUsingOverage(snapshot.isIsUsingOverage());
            prev.setCachedMcEnabled(snapshot.isCachedMcEnabled());
            prev.setEffortValue(snapshot.getEffortValue());
            prev.setExtraBodyHash(snapshot.getExtraBodyHash());
            prev.setToolNames(snapshot.getToolNames());
            prev.setDiffableContent(null); // invalidate lazily-built diff

        } catch (Exception e) {
            log.error("[prompt-cache] Error in recordPromptState: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // checkResponseForCacheBreak  (Phase 2)
    // -----------------------------------------------------------------------

    /**
     * Check whether the API response indicates a prompt cache break.
     * Uses pending changes from Phase 1 to explain the reason.
     * Translated from checkResponseForCacheBreak() in promptCacheBreakDetection.ts
     */
    public void checkResponseForCacheBreak(
            String querySource,
            int cacheReadTokens,
            int cacheCreationTokens,
            long lastAssistantMessageTimestamp,
            String agentId,
            String requestId) {
        try {
            String key = getTrackingKey(querySource, agentId);
            if (key == null) return;

            PreviousState state = previousStateBySource.get(key);
            if (state == null) return;

            // Skip models with different caching behaviour (e.g. haiku)
            if (isExcludedModel(state.getModel())) return;

            Integer prevCacheRead = state.getPrevCacheReadTokens();
            state.setPrevCacheReadTokens(cacheReadTokens);

            // First call — no baseline to compare
            if (prevCacheRead == null) return;

            // Cache deletions via cached microcompact are expected drops
            if (state.isCacheDeletionsPending()) {
                state.setCacheDeletionsPending(false);
                log.debug("[PROMPT CACHE] cache deletion applied, cache read: {} → {} (expected drop)",
                    prevCacheRead, cacheReadTokens);
                state.setPendingChanges(null);
                return;
            }

            int tokenDrop = prevCacheRead - cacheReadTokens;
            if (cacheReadTokens >= prevCacheRead * 0.95 || tokenDrop < MIN_CACHE_MISS_TOKENS) {
                state.setPendingChanges(null);
                return;
            }

            // Build explanation from pending changes
            PendingChanges changes = state.getPendingChanges();
            List<String> parts = new ArrayList<>();

            if (changes != null) {
                if (changes.isModelChanged())
                    parts.add("model changed (" + changes.getPreviousModel() + " → " + changes.getNewModel() + ")");
                if (changes.isSystemPromptChanged()) {
                    int delta = changes.getSystemCharDelta();
                    String info = delta == 0 ? "" : (delta > 0 ? " (+" + delta + " chars)" : " (" + delta + " chars)");
                    parts.add("system prompt changed" + info);
                }
                if (changes.isToolSchemasChanged()) {
                    String diff = (changes.getAddedToolCount() > 0 || changes.getRemovedToolCount() > 0)
                        ? " (+" + changes.getAddedToolCount() + "/-" + changes.getRemovedToolCount() + " tools)"
                        : " (tool prompt/schema changed, same tool set)";
                    parts.add("tools changed" + diff);
                }
                if (changes.isFastModeChanged()) parts.add("fast mode toggled");
                if (changes.isGlobalCacheStrategyChanged())
                    parts.add("global cache strategy changed (" + changes.getPrevGlobalCacheStrategy()
                        + " → " + changes.getNewGlobalCacheStrategy() + ")");
                if (changes.isBetasChanged()) {
                    String added   = changes.getAddedBetas().isEmpty()   ? "" : "+" + String.join(",", changes.getAddedBetas());
                    String removed = changes.getRemovedBetas().isEmpty() ? "" : "-" + String.join(",", changes.getRemovedBetas());
                    String diff = List.of(added, removed).stream().filter(s -> !s.isEmpty()).reduce("", (a, b) -> a + " " + b).trim();
                    parts.add("betas changed" + (diff.isEmpty() ? "" : " (" + diff + ")"));
                }
                if (changes.isEffortChanged())
                    parts.add("effort changed (" + changes.getPrevEffortValue() + " → " + changes.getNewEffortValue() + ")");
                if (changes.isExtraBodyChanged()) parts.add("extra body params changed");
            }

            // Time-gap TTL analysis
            long now = System.currentTimeMillis();
            long timeSinceLastMsg = lastAssistantMessageTimestamp > 0
                ? now - lastAssistantMessageTimestamp : -1;

            String reason;
            if (!parts.isEmpty()) {
                reason = String.join(", ", parts);
            } else if (timeSinceLastMsg > CACHE_TTL_1HOUR_MS) {
                reason = "possible 1h TTL expiry (prompt unchanged)";
            } else if (timeSinceLastMsg > CACHE_TTL_5MIN_MS) {
                reason = "possible 5min TTL expiry (prompt unchanged)";
            } else if (timeSinceLastMsg >= 0) {
                reason = "likely server-side (prompt unchanged, <5min gap)";
            } else {
                reason = "unknown cause";
            }

            String summary = "[PROMPT CACHE BREAK] " + reason
                + " [source=" + querySource
                + ", call #" + state.getCallCount()
                + ", cache read: " + prevCacheRead + " → " + cacheReadTokens
                + ", creation: " + cacheCreationTokens + "]";

            log.warn("{}", summary);
            state.setPendingChanges(null);

        } catch (Exception e) {
            log.error("[prompt-cache] Error in checkResponseForCacheBreak: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // notifyCacheDeletion / notifyCompaction / cleanupAgentTracking / reset
    // -----------------------------------------------------------------------

    /**
     * Call when cached microcompact sends cache_edits deletions.
     * Translated from notifyCacheDeletion() in promptCacheBreakDetection.ts
     */
    public void notifyCacheDeletion(String querySource, String agentId) {
        String key = getTrackingKey(querySource, agentId);
        if (key == null) return;
        PreviousState state = previousStateBySource.get(key);
        if (state != null) state.setCacheDeletionsPending(true);
    }

    /**
     * Call after compaction to reset the cache read baseline.
     * Translated from notifyCompaction() in promptCacheBreakDetection.ts
     */
    public void notifyCompaction(String querySource, String agentId) {
        String key = getTrackingKey(querySource, agentId);
        if (key == null) return;
        PreviousState state = previousStateBySource.get(key);
        if (state != null) state.setPrevCacheReadTokens(null);
        log.debug("[prompt-cache] Cache read baseline reset after compaction");
    }

    /**
     * Remove tracking state for a specific agent.
     * Translated from cleanupAgentTracking() in promptCacheBreakDetection.ts
     */
    public void cleanupAgentTracking(String agentId) {
        previousStateBySource.remove(agentId);
    }

    /**
     * Clear all tracking state.
     * Translated from resetPromptCacheBreakDetection() in promptCacheBreakDetection.ts
     */
    public void resetPromptCacheBreakDetection() {
        previousStateBySource.clear();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private boolean isExcludedModel(String model) {
        return model != null && model.contains("haiku");
    }

    private Map<String, Integer> buildPerToolHashes(PromptStateSnapshot snapshot) {
        Map<String, Integer> hashes = new LinkedHashMap<>();
        List<String> names  = snapshot.getToolNames();
        List<Integer> perTH = snapshot.getToolHashes();
        for (int i = 0; i < names.size(); i++) {
            hashes.put(names.get(i), i < perTH.size() ? perTH.get(i) : 0);
        }
        return hashes;
    }

    /** djb2 hash — same algorithm used as fallback in promptCacheBreakDetection.ts */
    private static int djb2(String s) {
        int hash = 5381;
        for (char c : s.toCharArray()) {
            hash = ((hash << 5) + hash) + c;
        }
        return hash;
    }
}
