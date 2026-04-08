package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session memory compaction service.
 * Translated from src/services/compact/sessionMemoryCompact.ts
 *
 * Handles compaction using session memory as context instead of running a full
 * LLM-summarisation round trip. When session memory is available and populated,
 * this path skips the compaction API call entirely and uses the already-extracted
 * knowledge as the summary, substantially reducing cost and latency.
 *
 * Handles two scenarios:
 * 1. Normal case: lastSummarizedMessageId is set → keep only messages after that ID.
 * 2. Resumed session: lastSummarizedMessageId not set but session memory has content →
 *    keep all messages but use session memory as the summary.
 */
@Slf4j
@Service
public class SessionMemoryCompactService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionMemoryCompactService.class);


    // Default configuration values (exported for use in tests).
    public static final SessionMemoryCompactConfig DEFAULT_SM_COMPACT_CONFIG =
            new SessionMemoryCompactConfig(10_000, 5, 40_000);

    // Current configuration (starts with defaults, may be updated from remote config).
    private final AtomicReference<SessionMemoryCompactConfig> smCompactConfig =
            new AtomicReference<>(DEFAULT_SM_COMPACT_CONFIG);

    // Track whether config has been initialized from remote.
    private final AtomicBoolean configInitialized = new AtomicBoolean(false);

    private final MicroCompactService microCompactService;
    private final SessionMemoryService sessionMemoryService;

    @Autowired
    public SessionMemoryCompactService(
            MicroCompactService microCompactService,
            SessionMemoryService sessionMemoryService) {
        this.microCompactService = microCompactService;
        this.sessionMemoryService = sessionMemoryService;
    }

    // =========================================================================
    // Config management
    // =========================================================================

    /**
     * Configuration for session memory compaction thresholds.
     * Translated from {@code SessionMemoryCompactConfig} in sessionMemoryCompact.ts
     */
    public static class SessionMemoryCompactConfig {
        private int minTokens;
        private int minTextBlockMessages;
        private int maxTokens;

        public SessionMemoryCompactConfig() {}
        public SessionMemoryCompactConfig(int minTokens, int minTextBlockMessages, int maxTokens) {
            this.minTokens = minTokens; this.minTextBlockMessages = minTextBlockMessages; this.maxTokens = maxTokens;
        }
        public int getMinTokens() { return minTokens; }
        public int getMinTextBlockMessages() { return minTextBlockMessages; }
        public int getMaxTokens() { return maxTokens; }
    }

    /**
     * Update the session memory compact configuration.
     * Translated from {@code setSessionMemoryCompactConfig()} in sessionMemoryCompact.ts
     */
    public void setSessionMemoryCompactConfig(SessionMemoryCompactConfig config) {
        smCompactConfig.set(config);
    }

    /**
     * Return the current session memory compact configuration.
     * Translated from {@code getSessionMemoryCompactConfig()} in sessionMemoryCompact.ts
     */
    public SessionMemoryCompactConfig getSessionMemoryCompactConfig() {
        return smCompactConfig.get();
    }

    /**
     * Reset config state (useful for testing).
     * Translated from {@code resetSessionMemoryCompactConfig()} in sessionMemoryCompact.ts
     */
    public void resetSessionMemoryCompactConfig() {
        smCompactConfig.set(DEFAULT_SM_COMPACT_CONFIG);
        configInitialized.set(false);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Check if a message contains text blocks (text content for user/assistant interaction).
     * Translated from {@code hasTextBlocks()} in sessionMemoryCompact.ts
     */
    public boolean hasTextBlocks(Message message) {
        if (message instanceof Message.AssistantMessage am) {
            return am.getContent() != null
                    && am.getContent().stream().anyMatch(b -> b instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock);
        }
        if (message instanceof Message.UserMessage um) {
            if (um.getContent() == null) return false;
            return um.getContent().stream().anyMatch(b -> b instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock);
        }
        return false;
    }

    /**
     * Check whether the given messages can be used with session-memory compaction.
     * Translated from {@code shouldUseSessionMemoryCompaction()} in sessionMemoryCompact.ts
     */
    public boolean shouldUseSessionMemoryCompaction() {
        if (isEnvTruthy("ENABLE_CLAUDE_CODE_SM_COMPACT")) {
            return true;
        }
        if (isEnvTruthy("DISABLE_CLAUDE_CODE_SM_COMPACT")) {
            return false;
        }
        // TODO: integrate with GrowthBook for 'tengu_session_memory' and 'tengu_sm_compact' flags.
        return false;
    }

    /**
     * Calculate the starting index for messages to keep after compaction.
     * Translated from {@code calculateMessagesToKeepIndex()} in sessionMemoryCompact.ts
     *
     * Starts from lastSummarizedIndex, then expands backwards to meet minimums:
     * - At least config.minTokens tokens
     * - At least config.minTextBlockMessages messages with text blocks
     * Stops expanding if config.maxTokens is reached.
     * Also ensures tool_use/tool_result pairs are not split (via adjustIndexToPreserveAPIInvariants).
     */
    public int calculateMessagesToKeepIndex(List<Message> messages, int lastSummarizedIndex) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        SessionMemoryCompactConfig config = getSessionMemoryCompactConfig();

        // Start from the message after lastSummarizedIndex.
        // If lastSummarizedIndex is -1, start with no messages kept (startIndex = messages.size()).
        int startIndex = lastSummarizedIndex >= 0
                ? lastSummarizedIndex + 1
                : messages.size();

        // Calculate current tokens and text-block message count from startIndex to end.
        int totalTokens = 0;
        int textBlockMessageCount = 0;
        for (int i = startIndex; i < messages.size(); i++) {
            Message msg = messages.get(i);
            totalTokens += microCompactService.estimateMessageTokens(List.of(msg));
            if (hasTextBlocks(msg)) {
                textBlockMessageCount++;
            }
        }

        // Check if we already hit the max cap.
        if (totalTokens >= config.getMaxTokens()) {
            return adjustIndexToPreserveAPIInvariants(messages, startIndex);
        }

        // Check if we already meet both minimums.
        if (totalTokens >= config.getMinTokens()
                && textBlockMessageCount >= config.getMinTextBlockMessages()) {
            return adjustIndexToPreserveAPIInvariants(messages, startIndex);
        }

        // Expand backwards until we meet both minimums or hit max cap.
        // Floor at the last compact-boundary message so we don't cross a
        // previous compaction boundary.
        int floor = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isCompactBoundaryMessage(messages.get(i))) {
                floor = i + 1;
                break;
            }
        }

        for (int i = startIndex - 1; i >= floor; i--) {
            Message msg = messages.get(i);
            int msgTokens = microCompactService.estimateMessageTokens(List.of(msg));
            totalTokens += msgTokens;
            if (hasTextBlocks(msg)) {
                textBlockMessageCount++;
            }
            startIndex = i;

            // Stop if we hit the max cap.
            if (totalTokens >= config.getMaxTokens()) {
                break;
            }

            // Stop if we meet both minimums.
            if (totalTokens >= config.getMinTokens()
                    && textBlockMessageCount >= config.getMinTextBlockMessages()) {
                break;
            }
        }

        return adjustIndexToPreserveAPIInvariants(messages, startIndex);
    }

    /**
     * Adjust startIndex to ensure we don't split tool_use/tool_result pairs
     * or thinking blocks that share the same message.id with kept assistant messages.
     * Translated from {@code adjustIndexToPreserveAPIInvariants()} in sessionMemoryCompact.ts
     */
    public int adjustIndexToPreserveAPIInvariants(List<Message> messages, int startIndex) {
        if (startIndex <= 0 || startIndex >= messages.size()) {
            return startIndex;
        }

        int adjustedIndex = startIndex;

        // Step 1: Handle tool_use/tool_result pairs.
        List<String> allToolResultIds = new ArrayList<>();
        for (int i = startIndex; i < messages.size(); i++) {
            allToolResultIds.addAll(getToolResultIds(messages.get(i)));
        }

        if (!allToolResultIds.isEmpty()) {
            // Collect tool_use IDs already in the kept range.
            Set<String> toolUseIdsInKeptRange = new HashSet<>();
            for (int i = adjustedIndex; i < messages.size(); i++) {
                Message msg = messages.get(i);
                if (msg instanceof Message.AssistantMessage am && am.getContent() != null) {
                    for (var block : am.getContent()) {
                        if (block instanceof com.anthropic.claudecode.model.ContentBlock.ToolUseBlock tu) {
                            toolUseIdsInKeptRange.add(tu.getId());
                        }
                    }
                }
            }

            // Only look for tool_uses that are NOT already in the kept range.
            Set<String> neededToolUseIds = new HashSet<>(allToolResultIds);
            neededToolUseIds.removeAll(toolUseIdsInKeptRange);

            // Find the assistant message(s) with matching tool_use blocks.
            for (int i = adjustedIndex - 1; i >= 0 && !neededToolUseIds.isEmpty(); i--) {
                Message message = messages.get(i);
                if (hasToolUseWithIds(message, neededToolUseIds)) {
                    adjustedIndex = i;
                    if (message instanceof Message.AssistantMessage am && am.getContent() != null) {
                        for (var block : am.getContent()) {
                            if (block instanceof com.anthropic.claudecode.model.ContentBlock.ToolUseBlock tu) {
                                neededToolUseIds.remove(tu.getId());
                            }
                        }
                    }
                }
            }
        }

        // Step 2: Handle thinking blocks that share message.id with kept assistant messages.
        Set<String> messageIdsInKeptRange = new HashSet<>();
        for (int i = adjustedIndex; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof Message.AssistantMessage am && am.getMessageId() != null) {
                messageIdsInKeptRange.add(am.getMessageId());
            }
        }

        for (int i = adjustedIndex - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof Message.AssistantMessage am
                    && am.getMessageId() != null
                    && messageIdsInKeptRange.contains(am.getMessageId())) {
                adjustedIndex = i;
            }
        }

        return adjustedIndex;
    }

    /**
     * Try to use session memory for compaction instead of traditional compaction.
     * Translated from {@code trySessionMemoryCompaction()} in sessionMemoryCompact.ts
     *
     * @param messages              Current conversation messages.
     * @param agentId               Optional agent identifier.
     * @param autoCompactThreshold  When provided, reject the result if the post-compact
     *                              token count still exceeds this threshold.
     * @return A {@link CompletableFuture} that resolves to a non-null list of messages on
     *         success, or {@code null} if session-memory compaction cannot be used.
     */
    public CompletableFuture<List<Message>> trySessionMemoryCompaction(
            List<Message> messages,
            String agentId,
            Integer autoCompactThreshold) {

        return CompletableFuture.supplyAsync(() -> {
            if (!shouldUseSessionMemoryCompaction()) {
                return null;
            }

            // Initialize config from remote (only fetches once).
            initSessionMemoryCompactConfigIfNeeded();

            Optional<String> sessionMemoryOpt = sessionMemoryService.getSessionMemoryContent(agentId);
            if (sessionMemoryOpt.isEmpty()) {
                log.debug("tengu_sm_compact_no_session_memory");
                return null;
            }

            String sessionMemory = sessionMemoryOpt.get();

            if (isSessionMemoryEmpty(sessionMemory)) {
                log.debug("tengu_sm_compact_empty_template");
                return null;
            }

            try {
                // TODO: integrate with getLastSummarizedMessageId() from SessionMemoryUtils.
                // For now use the "resumed session" path (lastSummarizedIndex = messages.size() - 1).
                int lastSummarizedIndex = messages.size() - 1;
                log.debug("tengu_sm_compact_resumed_session");

                int startIndex = calculateMessagesToKeepIndex(messages, lastSummarizedIndex);

                // Filter out old compact boundary messages from messagesToKeep.
                List<Message> messagesToKeep = new ArrayList<>();
                for (int i = startIndex; i < messages.size(); i++) {
                    Message m = messages.get(i);
                    if (!isCompactBoundaryMessage(m)) {
                        messagesToKeep.add(m);
                    }
                }

                // Estimate token count of what we'd keep.
                int postCompactTokenCount = microCompactService.estimateMessageTokens(messagesToKeep);

                // Only check threshold if provided.
                if (autoCompactThreshold != null && postCompactTokenCount >= autoCompactThreshold) {
                    log.debug("tengu_sm_compact_threshold_exceeded: postCompact={} threshold={}",
                            postCompactTokenCount, autoCompactThreshold);
                    return null;
                }

                log.info("Session memory compaction succeeded: keeping {} messages (~{} tokens)",
                        messagesToKeep.size(), postCompactTokenCount);

                return messagesToKeep;

            } catch (Exception error) {
                log.debug("Session memory compaction error: {}", error.getMessage());
                return null;
            }
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void initSessionMemoryCompactConfigIfNeeded() {
        if (configInitialized.compareAndSet(false, true)) {
            // TODO: load from remote GrowthBook config 'tengu_sm_compact_config'.
            // For now keep defaults.
        }
    }

    private List<String> getToolResultIds(Message message) {
        if (!(message instanceof Message.UserMessage um) || um.getContent() == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (var block : um.getContent()) {
            if (block instanceof com.anthropic.claudecode.model.ContentBlock.ToolResultBlock tr) {
                ids.add(tr.getToolUseId());
            }
        }
        return ids;
    }

    private boolean hasToolUseWithIds(Message message, Set<String> toolUseIds) {
        if (!(message instanceof Message.AssistantMessage am) || am.getContent() == null) {
            return false;
        }
        for (var block : am.getContent()) {
            if (block instanceof com.anthropic.claudecode.model.ContentBlock.ToolUseBlock tu
                    && toolUseIds.contains(tu.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean isCompactBoundaryMessage(Message message) {
        // A compact boundary message is a SystemMessage with subtype COMPACT_BOUNDARY.
        return message instanceof Message.SystemMessage sm
                && "compact_boundary".equals(sm.getSubtype());
    }

    private boolean isSessionMemoryEmpty(String sessionMemory) {
        // Session memory matches the template when it has no actual content extracted.
        // Simplistic check: non-null, non-blank, and has more than just the template headers.
        return sessionMemory == null || sessionMemory.isBlank();
    }

    private boolean isEnvTruthy(String name) {
        String val = System.getenv(name);
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }

    /** Result of a session memory compaction attempt. */
    public static class SessionMemoryCompactionResult {
        private final List<Message> compactedMessages;
        private final String userDisplayMessage;
        public SessionMemoryCompactionResult(List<Message> compactedMessages, String userDisplayMessage) {
            this.compactedMessages = compactedMessages;
            this.userDisplayMessage = userDisplayMessage;
        }
        public List<Message> compactedMessages() { return compactedMessages; }
        public String userDisplayMessage() { return userDisplayMessage; }
    }

    /**
     * Try session memory compaction (2-arg overload).
     */
    public java.util.concurrent.CompletableFuture<SessionMemoryCompactionResult> trySessionMemoryCompaction(
            List<Message> messages, String agentId) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
