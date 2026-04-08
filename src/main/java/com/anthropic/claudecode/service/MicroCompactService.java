package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.service.QueryEngine.QueryEngineConfig;
import com.anthropic.claudecode.service.TimeBasedMCConfigService.TimeBasedMCConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Micro-compact service — clears old tool results without full compaction.
 * Translated from src/services/compact/microCompact.ts
 *
 * <p>Supports two paths:
 * <ol>
 *   <li><b>Time-based</b>: when the gap since the last main-loop assistant message
 *       exceeds the configured threshold, content-clear all but the most recent N
 *       compactable tool results. The cache is cold — we mutate message content directly.</li>
 *   <li><b>Cached MC</b> (ant-only, behind feature flag): uses the cache-editing API to
 *       remove tool results without invalidating the cached prefix. Not yet ported to Java;
 *       tracked in TODO comments.</li>
 * </ol>
 */
@Slf4j
@Service
public class MicroCompactService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MicroCompactService.class);


    /**
     * Sentinel value substituted for cleared tool-result content.
     * Exported so callers can detect already-cleared results and avoid double-clearing.
     */
    public static final String TIME_BASED_MC_CLEARED_MESSAGE = "[Old tool result content cleared]";

    private static final int IMAGE_MAX_TOKEN_SIZE = 2000;

    // Tools whose results can be micro-compacted (mirrors COMPACTABLE_TOOLS in microCompact.ts).
    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
        "Read",
        "Bash",
        "Grep",
        "Glob",
        "WebSearch",
        "WebFetch",
        "Edit",
        "Write"
    );

    // =========================================================================
    // Module-level cached-MC state (simplified — full port deferred)
    // =========================================================================

    // pendingCacheEdits — tracks cache edits to be included in the next API request.
    // Null when there are no pending edits.
    private final AtomicReference<PendingCacheEdits> pendingCacheEdits = new AtomicReference<>(null);

    private final TimeBasedMCConfigService timeBasedMCConfigService;
    private final CompactWarningService compactWarningService;

    @Autowired
    public MicroCompactService(
            TimeBasedMCConfigService timeBasedMCConfigService,
            CompactWarningService compactWarningService) {
        this.timeBasedMCConfigService = timeBasedMCConfigService;
        this.compactWarningService = compactWarningService;
    }

    // =========================================================================
    // Public result / info types
    // =========================================================================

    /**
     * Pending cache-edit metadata returned as part of a {@link MicrocompactResult}.
     * Translated from {@code PendingCacheEdits} in microCompact.ts.
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class PendingCacheEdits {
        private final String trigger;              // always "auto"
        private final List<String> deletedToolIds;
        private final long baselineCacheDeletedTokens;

        public String getTrigger() { return trigger; }
        public List<String> getDeletedToolIds() { return deletedToolIds; }
        public long getBaselineCacheDeletedTokens() { return baselineCacheDeletedTokens; }
    }

    /**
     * Compaction info embedded in a {@link MicrocompactResult}.
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class CompactionInfo {
        private final PendingCacheEdits pendingCacheEdits;

        public PendingCacheEdits getPendingCacheEdits() { return pendingCacheEdits; }
    }

    /**
     * Result of a microcompact operation.
     * Translated from {@code MicrocompactResult} in microCompact.ts.
     */
    public static class MicrocompactResult {
        private final List<Message> messages;
        private final CompactionInfo compactionInfo;

        public MicrocompactResult(List<Message> messages, CompactionInfo compactionInfo) {
            this.messages = messages; this.compactionInfo = compactionInfo;
        }
        public List<Message> getMessages() { return messages; }
        public CompactionInfo getCompactionInfo() { return compactionInfo; }
        /** Returns a user-visible message describing the compaction, if any. */
        public String userDisplayMessage() { return null; }
    }

    /**
     * Time-based trigger evaluation result.
     * Translated from the return type of {@code evaluateTimeBasedTrigger()} in microCompact.ts.
     */
    public record TimeBasedTriggerResult(double gapMinutes, TimeBasedMCConfig config) {}

    // =========================================================================
    // Core entry point
    // =========================================================================

    /**
     * Apply microcompaction to messages if needed.
     * Translated from {@code microcompactMessages()} in microCompact.ts.
     *
     * <p>Steps (in order):
     * <ol>
     *   <li>Clear the compact-warning suppression flag.</li>
     *   <li>Evaluate the time-based trigger; if it fires, content-clear old tool
     *       results and return early.</li>
     *   <li>(Cached MC — ant-only, deferred to TODO).</li>
     *   <li>If nothing applies, return messages unchanged.</li>
     * </ol>
     *
     * @param messages    Current conversation messages.
     * @param querySource Source of the query (e.g. "repl_main_thread", "session_memory").
     * @return {@link MicrocompactResult} with (possibly modified) messages.
     */
    public MicrocompactResult microcompactMessages(List<Message> messages, String querySource) {
        // Clear suppression flag at start of new microcompact attempt.
        compactWarningService.clearCompactWarningSuppression();

        // Time-based trigger runs first and short-circuits.
        MicrocompactResult timeBasedResult = maybeTimeBasedMicrocompact(messages, querySource);
        if (timeBasedResult != null) {
            return timeBasedResult;
        }

        // TODO: cached-MC path (ant-only, feature('CACHED_MICROCOMPACT')).
        // For non-ant / external builds no compaction happens here;
        // autocompact handles context pressure instead.

        return new MicrocompactResult(messages, null);
    }

    /**
     * Convenience overload with no querySource (treated as main-thread for backward compat).
     */
    public MicrocompactResult microcompactMessages(List<Message> messages) {
        return microcompactMessages(messages, null);
    }

    /**
     * Compact messages for a query. Returns CompletableFuture<List<Message>>.
     * Used by QueryEngine.
     */
    public java.util.concurrent.CompletableFuture<List<Message>> compact(
            List<Message> messages,
            QueryEngine.QueryEngineConfig config) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            MicrocompactResult result = microcompactMessages(messages,
                config != null ? config.getQuerySource() : null);
            return result != null ? result.getMessages() : messages;
        });
    }

    // =========================================================================
    // Time-based microcompact
    // =========================================================================

    /**
     * Check whether the time-based trigger should fire for this request.
     * Translated from {@code evaluateTimeBasedTrigger()} in microCompact.ts.
     *
     * @return The gap and config when the trigger fires, or {@code null} when it doesn't.
     */
    public TimeBasedTriggerResult evaluateTimeBasedTrigger(List<Message> messages, String querySource) {
        TimeBasedMCConfig config = timeBasedMCConfigService.getTimeBasedMCConfig();

        // Require an explicit main-thread querySource.
        if (!config.isEnabled() || querySource == null || !isMainThreadSource(querySource)) {
            return null;
        }

        // Find the last assistant message.
        Message lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage) {
                lastAssistant = messages.get(i);
                break;
            }
        }
        if (lastAssistant == null) {
            return null;
        }

        // Compute gap in minutes.
        Instant msgTime = parseTimestamp(lastAssistant);
        if (msgTime == null) {
            return null;
        }
        double gapMinutes = (System.currentTimeMillis() - msgTime.toEpochMilli()) / 60_000.0;

        if (!Double.isFinite(gapMinutes) || gapMinutes < config.getGapThresholdMinutes()) {
            return null;
        }

        return new TimeBasedTriggerResult(gapMinutes, config);
    }

    // =========================================================================
    // Pending cache edits (simplified cached-MC state)
    // =========================================================================

    /**
     * Get and clear pending cache edits.
     * Translated from {@code consumePendingCacheEdits()} in microCompact.ts.
     *
     * @return The pending cache edits, or {@code null} if none.
     */
    public PendingCacheEdits consumePendingCacheEdits() {
        return pendingCacheEdits.getAndSet(null);
    }

    /**
     * Reset all microcompact state (cached-MC registrations, pending edits).
     * Translated from {@code resetMicrocompactState()} in microCompact.ts.
     *
     * Called by {@link PostCompactCleanupService} after every compaction.
     */
    public void resetMicrocompactState() {
        pendingCacheEdits.set(null);
        log.debug("Microcompact state reset");
    }

    // =========================================================================
    // Token estimation
    // =========================================================================

    /**
     * Estimate total token count for a list of messages.
     * Translated from {@code estimateMessageTokens()} in microCompact.ts.
     *
     * <p>Pads the estimate by 4/3 to be conservative since we're approximating.
     *
     * @param messages Messages to estimate.
     * @return Estimated token count (padded by 4/3).
     */
    public int estimateMessageTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            if (!(message instanceof Message.UserMessage) && !(message instanceof Message.AssistantMessage)) {
                continue;
            }

            List<ContentBlock> content = getMessageContent(message);
            if (content == null) {
                continue;
            }

            for (ContentBlock block : content) {
                total += estimateBlockTokens(block);
            }
        }
        // Pad estimate by 4/3 (same as TypeScript source).
        return (int) Math.ceil(total * (4.0 / 3.0));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Apply time-based microcompact if the trigger fires.
     * Translated from {@code maybeTimeBasedMicrocompact()} in microCompact.ts.
     */
    private MicrocompactResult maybeTimeBasedMicrocompact(List<Message> messages, String querySource) {
        TimeBasedTriggerResult trigger = evaluateTimeBasedTrigger(messages, querySource);
        if (trigger == null) {
            return null;
        }

        double gapMinutes = trigger.gapMinutes();
        TimeBasedMCConfig config = trigger.config();

        List<String> compactableIds = collectCompactableToolIds(messages);

        // Floor at 1: slice(-0) returns the full array (paradoxically keeps
        // everything), and clearing ALL results leaves the model with zero working
        // context. Always keep at least the last result.
        int keepRecent = Math.max(1, config.getKeepRecent());
        Set<String> keepSet = new HashSet<>(
            compactableIds.subList(Math.max(0, compactableIds.size() - keepRecent), compactableIds.size())
        );
        Set<String> clearSet = new HashSet<>();
        for (String id : compactableIds) {
            if (!keepSet.contains(id)) {
                clearSet.add(id);
            }
        }

        if (clearSet.isEmpty()) {
            return null;
        }

        int[] tokensSaved = {0};
        List<Message> result = new ArrayList<>();
        for (Message message : messages) {
            if (!(message instanceof Message.UserMessage) || !hasArrayContent(message)) {
                result.add(message);
                continue;
            }

            boolean[] touched = {false};
            List<ContentBlock> newContent = new ArrayList<>();
            for (ContentBlock block : getMessageContent(message)) {
                if (block instanceof ContentBlock.ToolResultBlock toolResult
                        && clearSet.contains(toolResult.getToolUseId())
                        && !TIME_BASED_MC_CLEARED_MESSAGE.equals(toolResult.getContentAsString())) {
                    tokensSaved[0] += calculateToolResultTokens(toolResult);
                    touched[0] = true;
                    // Replace content with cleared-marker string.
                    ContentBlock.ToolResultBlock cleared = toolResult.withContent(TIME_BASED_MC_CLEARED_MESSAGE);
                    newContent.add(cleared);
                } else {
                    newContent.add(block);
                }
            }

            if (!touched[0]) {
                result.add(message);
            } else {
                result.add(((Message.UserMessage) message).withContent(newContent));
            }
        }

        if (tokensSaved[0] == 0) {
            return null;
        }

        log.debug("[TIME-BASED MC] gap {}min > {}min, cleared {} tool results (~{} tokens), kept last {}",
                Math.round(gapMinutes), config.getGapThresholdMinutes(),
                clearSet.size(), tokensSaved[0], keepSet.size());

        compactWarningService.suppressCompactWarning();
        // Reset cached-MC state (if present) since we just changed prompt content.
        resetMicrocompactState();

        return new MicrocompactResult(result, null);
    }

    /**
     * Walk messages and collect tool_use IDs whose tool name is in COMPACTABLE_TOOLS,
     * in encounter order.
     * Translated from {@code collectCompactableToolIds()} in microCompact.ts.
     */
    private List<String> collectCompactableToolIds(List<Message> messages) {
        List<String> ids = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof Message.AssistantMessage) {
                List<ContentBlock> content = getMessageContent(message);
                if (content == null) continue;
                for (ContentBlock block : content) {
                    if (block instanceof ContentBlock.ToolUseBlock toolUse
                            && COMPACTABLE_TOOLS.contains(toolUse.getName())) {
                        ids.add(toolUse.getId());
                    }
                }
            }
        }
        return ids;
    }

    /**
     * Calculate approximate token count for a single tool-result block.
     * Translated from {@code calculateToolResultTokens()} in microCompact.ts.
     */
    private int calculateToolResultTokens(ContentBlock.ToolResultBlock block) {
        Object content = block.getContent();
        if (content == null) {
            return 0;
        }
        if (content instanceof String s) {
            return roughTokenCountEstimation(s);
        }
        if (content instanceof List<?> list) {
            int sum = 0;
            for (Object item : list) {
                if (item instanceof ContentBlock.TextBlock t) {
                    sum += roughTokenCountEstimation(t.getText());
                } else if (item instanceof ContentBlock.ImageBlock || item instanceof ContentBlock.DocumentBlock) {
                    sum += IMAGE_MAX_TOKEN_SIZE;
                }
            }
            return sum;
        }
        return roughTokenCountEstimation(content.toString());
    }

    /**
     * Estimate token count for a single content block.
     * Mirrors {@code roughTokenCountEstimationForBlock()} from tokenEstimation.ts.
     */
    private int estimateBlockTokens(ContentBlock block) {
        if (block instanceof ContentBlock.TextBlock t) {
            return roughTokenCountEstimation(t.getText());
        } else if (block instanceof ContentBlock.ToolResultBlock tr) {
            return calculateToolResultTokens(tr);
        } else if (block instanceof ContentBlock.ImageBlock || block instanceof ContentBlock.DocumentBlock) {
            return IMAGE_MAX_TOKEN_SIZE;
        } else if (block instanceof ContentBlock.ThinkingBlock thinking) {
            return roughTokenCountEstimation(thinking.getThinking());
        } else if (block instanceof ContentBlock.ToolUseBlock toolUse) {
            String text = toolUse.getName() != null ? toolUse.getName() : "";
            // input is JSON-stringified in the TS source; use toString() approximation here.
            String inputStr = toolUse.getInput() != null ? toolUse.getInput().toString() : "{}";
            return roughTokenCountEstimation(text + inputStr);
        }
        return roughTokenCountEstimation(block.toString());
    }

    /**
     * Rough token count: 1 token ≈ 4 characters.
     * Mirrors {@code roughTokenCountEstimation()} from tokenEstimation.ts.
     */
    private int roughTokenCountEstimation(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }

    /**
     * Returns true for querySource values that identify the main REPL thread.
     * Translated from {@code isMainThreadSource()} in microCompact.ts.
     *
     * Prefix-match because promptCategory.ts sets querySource to
     * 'repl_main_thread:outputStyle:&lt;style&gt;' when a non-default output style is active.
     */
    private boolean isMainThreadSource(String querySource) {
        return querySource == null || querySource.startsWith("repl_main_thread");
    }

    private boolean hasArrayContent(Message message) {
        return getMessageContent(message) != null;
    }

    @SuppressWarnings("unchecked")
    private List<ContentBlock> getMessageContent(Message message) {
        if (message instanceof Message.UserMessage um) {
            return um.getContent();
        }
        if (message instanceof Message.AssistantMessage am) {
            return am.getContent();
        }
        return null;
    }

    private Instant parseTimestamp(Message message) {
        String ts = message.getTimestamp();
        if (ts == null || ts.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(ts);
        } catch (Exception e) {
            log.debug("Could not parse message timestamp '{}'", ts);
            return null;
        }
    }
}
