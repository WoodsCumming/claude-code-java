package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.model.ContentBlock;
import com.anthropic.claudecode.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Context compaction service.
 * Translated from src/services/compact/compact.ts
 *
 * When the conversation gets too long, this service compacts/summarizes
 * the conversation history to free up context window space. Supports
 * full compaction, partial compaction, and image-stripping utilities.
 */
@Slf4j
@Service
public class CompactService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompactService.class);


    // =========================================================================
    // Constants (translated from compact.ts)
    // =========================================================================

    public static final int POST_COMPACT_MAX_FILES_TO_RESTORE    = 5;
    public static final int POST_COMPACT_TOKEN_BUDGET            = 50_000;
    public static final int POST_COMPACT_MAX_TOKENS_PER_FILE     = 5_000;
    public static final int POST_COMPACT_MAX_TOKENS_PER_SKILL    = 5_000;
    public static final int POST_COMPACT_SKILLS_TOKEN_BUDGET     = 25_000;

    private static final int MAX_COMPACT_STREAMING_RETRIES = 2;
    private static final int MAX_PTL_RETRIES               = 3;
    private static final String PTL_RETRY_MARKER           = "[earlier conversation truncated for compaction retry]";

    public static final String ERROR_MESSAGE_NOT_ENOUGH_MESSAGES  = "Not enough messages to compact.";
    public static final String ERROR_MESSAGE_PROMPT_TOO_LONG      =
        "Conversation too long. Press esc twice to go up a few messages and try again.";
    public static final String ERROR_MESSAGE_USER_ABORT           = "API Error: Request was aborted.";
    public static final String ERROR_MESSAGE_INCOMPLETE_RESPONSE  =
        "Compaction interrupted \u00b7 This may be due to network issues \u2014 please try again.";

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;
    private final CompactPromptService compactPromptService;
    private final CompactGroupingService compactGroupingService;
    private final PostCompactCleanupService postCompactCleanupService;

    @Autowired
    public CompactService(
            AnthropicClient anthropicClient,
            ObjectMapper objectMapper,
            CompactPromptService compactPromptService,
            CompactGroupingService compactGroupingService,
            PostCompactCleanupService postCompactCleanupService) {
        this.anthropicClient = anthropicClient;
        this.objectMapper = objectMapper;
        this.compactPromptService = compactPromptService;
        this.compactGroupingService = compactGroupingService;
        this.postCompactCleanupService = postCompactCleanupService;
    }

    // =========================================================================
    // Public result types
    // =========================================================================

    /**
     * Represents the outcome of a compaction operation.
     * Translated from the {@code CompactionResult} interface in compact.ts
     */
    public static class CompactionResult {
        /** Compact boundary marker message inserted at the start of the new context. */
        private final Message.SystemMessage boundaryMarker;
        /** Summary messages (one user message containing the formatted compact summary). */
        private final List<Message.UserMessage> summaryMessages;
        /** Attachment messages re-injected post-compact (files, plan, skills, etc.). */
        private final List<Message> attachments;
        /** Results from session-start hooks. */
        private final List<Message> hookResults;
        /** Messages kept verbatim after a partial/session-memory compaction. */
        private final List<Message> messagesToKeep;
        /** Optional message to display to the user after compaction. */
        private final String userDisplayMessage;
        /** Token count immediately before compaction (from last API response usage). */
        private final Integer preCompactTokenCount;
        /**
         * Token count for the compaction API call (input+output).
         * Kept as "postCompactTokenCount" for analytics event continuity
         * even though it now represents the compact API call's total usage.
         */
        private final Integer postCompactTokenCount;
        /** True resulting-context token estimate (payload size after compact). */
        private final Integer truePostCompactTokenCount;

        public CompactionResult(Message.SystemMessage boundaryMarker, List<Message.UserMessage> summaryMessages,
                                 List<Message> attachments, List<Message> hookResults, List<Message> messagesToKeep,
                                 String userDisplayMessage, Integer preCompactTokenCount,
                                 Integer postCompactTokenCount, Integer truePostCompactTokenCount) {
            this.boundaryMarker = boundaryMarker; this.summaryMessages = summaryMessages;
            this.attachments = attachments; this.hookResults = hookResults;
            this.messagesToKeep = messagesToKeep; this.userDisplayMessage = userDisplayMessage;
            this.preCompactTokenCount = preCompactTokenCount; this.postCompactTokenCount = postCompactTokenCount;
            this.truePostCompactTokenCount = truePostCompactTokenCount;
        }

        public Message.SystemMessage getBoundaryMarker() { return boundaryMarker; }
        public List<Message.UserMessage> getSummaryMessages() { return summaryMessages; }
        public List<Message> getAttachments() { return attachments; }
        public List<Message> getHookResults() { return hookResults; }
        public List<Message> getMessagesToKeep() { return messagesToKeep; }
        public String getUserDisplayMessage() { return userDisplayMessage; }
        public Integer getPreCompactTokenCount() { return preCompactTokenCount; }
        public Integer getPostCompactTokenCount() { return postCompactTokenCount; }
        public Integer getTruePostCompactTokenCount() { return truePostCompactTokenCount; }
    }

    /**
     * Diagnosis context for recompaction analytics.
     * Translated from {@code RecompactionInfo} in compact.ts / autoCompact.ts
     */
    public record RecompactionInfo(
        boolean isRecompactionInChain,
        int turnsSincePreviousCompact,
        String previousCompactTurnId,
        int autoCompactThreshold,
        String querySource
    ) {}

    // =========================================================================
    // Image / attachment stripping
    // =========================================================================

    /**
     * Strip image and document blocks from user messages before sending for compaction.
     * Translated from {@code stripImagesFromMessages()} in compact.ts
     *
     * Images are not needed for generating a conversation summary and can cause the
     * compaction API call itself to hit the prompt-too-long limit.
     *
     * @param messages Raw messages that may contain image/document blocks.
     * @return New message list with image/document blocks replaced by text markers.
     */
    public List<Message> stripImagesFromMessages(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof Message.UserMessage um)) {
                result.add(message);
                continue;
            }
            List<ContentBlock> content = um.getContent();
            if (content == null) {
                result.add(message);
                continue;
            }

            boolean hasMediaBlock = false;
            List<ContentBlock> newContent = new ArrayList<>();
            for (ContentBlock block : content) {
                if (block instanceof ContentBlock.ImageBlock) {
                    hasMediaBlock = true;
                    newContent.add(new ContentBlock.TextBlock("[image]"));
                } else if (block instanceof ContentBlock.DocumentBlock) {
                    hasMediaBlock = true;
                    newContent.add(new ContentBlock.TextBlock("[document]"));
                } else if (block instanceof ContentBlock.ToolResultBlock tr
                        && tr.getContent() instanceof List<?> trContent) {
                    boolean toolHasMedia = false;
                    List<Object> newToolContent = new ArrayList<>();
                    for (Object item : trContent) {
                        if (item instanceof ContentBlock.ImageBlock) {
                            toolHasMedia = true;
                            newToolContent.add(new ContentBlock.TextBlock("[image]"));
                        } else if (item instanceof ContentBlock.DocumentBlock) {
                            toolHasMedia = true;
                            newToolContent.add(new ContentBlock.TextBlock("[document]"));
                        } else {
                            newToolContent.add(item);
                        }
                    }
                    if (toolHasMedia) {
                        hasMediaBlock = true;
                        newContent.add(tr.withContent(newToolContent));
                    } else {
                        newContent.add(block);
                    }
                } else {
                    newContent.add(block);
                }
            }

            if (!hasMediaBlock) {
                result.add(message);
            } else {
                result.add(um.withContent(newContent));
            }
        }
        return result;
    }

    // =========================================================================
    // Prompt-too-long retry helper
    // =========================================================================

    /**
     * Drop oldest API-round groups from messages until the tokenGap is covered.
     * Translated from {@code truncateHeadForPTLRetry()} in compact.ts
     *
     * Last-resort escape hatch for CC-1180: when the compact request itself hits
     * prompt-too-long, dropping oldest context is lossy but unblocks the user.
     *
     * @param messages    Messages to truncate.
     * @param tokenGap    Tokens that need to be freed; pass -1 to use 20% fallback.
     * @return Truncated messages, or {@code null} when nothing can be dropped.
     */
    public List<Message> truncateHeadForPTLRetry(List<Message> messages, int tokenGap) {
        // Strip our own synthetic marker from a previous retry before grouping.
        List<Message> input = messages;
        if (!messages.isEmpty()
                && messages.get(0) instanceof Message.UserMessage um
                && Boolean.TRUE.equals(um.isMeta())
                && PTL_RETRY_MARKER.equals(getPlainTextContent(um))) {
            input = messages.subList(1, messages.size());
        }

        List<List<Message>> groups = compactGroupingService.groupMessagesByApiRound(input);
        if (groups.size() < 2) {
            return null;
        }

        int dropCount;
        if (tokenGap > 0) {
            int acc = 0;
            dropCount = 0;
            for (List<Message> g : groups) {
                acc += roughTokenCount(g);
                dropCount++;
                if (acc >= tokenGap) break;
            }
        } else {
            dropCount = Math.max(1, (int) Math.floor(groups.size() * 0.2));
        }

        // Keep at least one group so there's something to summarize.
        dropCount = Math.min(dropCount, groups.size() - 1);
        if (dropCount < 1) {
            return null;
        }

        List<Message> sliced = new ArrayList<>();
        for (int i = dropCount; i < groups.size(); i++) {
            sliced.addAll(groups.get(i));
        }

        // If the sliced list starts with an assistant message the API rejects it.
        // Prepend a synthetic user marker.
        if (!sliced.isEmpty() && sliced.get(0) instanceof Message.AssistantMessage) {
            List<Message> withMarker = new ArrayList<>();
            withMarker.add(createMetaUserMessage(PTL_RETRY_MARKER));
            withMarker.addAll(sliced);
            return withMarker;
        }

        return sliced;
    }

    // =========================================================================
    // Post-compact message assembly
    // =========================================================================

    /**
     * Build the base post-compact messages array from a CompactionResult.
     * Translated from {@code buildPostCompactMessages()} in compact.ts
     *
     * Order: boundaryMarker, summaryMessages, messagesToKeep, attachments, hookResults.
     */
    public List<Message> buildPostCompactMessages(CompactionResult result) {
        List<Message> out = new ArrayList<>();
        if (result.getBoundaryMarker() != null)    out.add(result.getBoundaryMarker());
        if (result.getSummaryMessages() != null)   out.addAll(result.getSummaryMessages());
        if (result.getMessagesToKeep() != null)    out.addAll(result.getMessagesToKeep());
        if (result.getAttachments() != null)       out.addAll(result.getAttachments());
        if (result.getHookResults() != null)       out.addAll(result.getHookResults());
        return out;
    }

    /**
     * Merge user-supplied custom instructions with hook-provided instructions.
     * Translated from {@code mergeHookInstructions()} in compact.ts
     *
     * User instructions come first; hook instructions are appended. Empty strings
     * normalize to {@code null}.
     */
    public String mergeHookInstructions(String userInstructions, String hookInstructions) {
        if (hookInstructions == null || hookInstructions.isBlank()) {
            return (userInstructions != null && !userInstructions.isBlank()) ? userInstructions : null;
        }
        if (userInstructions == null || userInstructions.isBlank()) {
            return hookInstructions;
        }
        return userInstructions + "\n\n" + hookInstructions;
    }

    // =========================================================================
    // Main compaction entry point
    // =========================================================================

    /**
     * Create a compact version of a conversation by summarising older messages.
     * Translated from {@code compactConversation()} in compact.ts
     *
     * @param messages                  Current conversation messages.
     * @param model                     Model to use for summarisation.
     * @param suppressFollowUpQuestions When {@code true}, instruct the model to resume
     *                                  immediately without asking questions.
     * @param customInstructions        Optional custom summarisation instructions.
     * @param isAutoCompact             Whether this was triggered automatically.
     * @param querySource               Query source for cleanup routing.
     * @return A {@link CompletableFuture} that resolves to a {@link CompactionResult}.
     */
    public CompletableFuture<CompactionResult> compactConversation(
            List<Message> messages,
            String model,
            boolean suppressFollowUpQuestions,
            String customInstructions,
            boolean isAutoCompact,
            String querySource) {

        return CompletableFuture.supplyAsync(() -> {
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException(ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
            }

            log.info("Compacting conversation with {} messages (auto={})", messages.size(), isAutoCompact);

            try {
                // Strip images from messages before summarising to avoid PTL on large sessions.
                List<Message> stripped = stripImagesFromMessages(messages);

                // Build the compact prompt (with optional custom instructions).
                String compactPrompt = compactPromptService.getCompactPrompt(customInstructions);

                // Call the API to generate a summary.
                String conversationText = buildConversationText(stripped);
                List<Map<String, Object>> apiMessages = List.of(
                    Map.of("role", "user", "content",
                        compactPrompt + "\n\n---\n\nConversation to summarise:\n\n" + conversationText)
                );

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                    .model(model)
                    .maxTokens(8192)
                    .messages(apiMessages)
                    .build();

                AnthropicClient.MessageResponse response = anthropicClient
                    .createMessage(request)
                    .join();

                String summary = extractTextFromResponse(response);

                if (summary == null || summary.isBlank()) {
                    throw new RuntimeException(
                        "Failed to generate conversation summary - response did not contain valid text content");
                }

                // Build the compact boundary marker.
                String lastMsgUuid = messages.isEmpty() ? null
                        : messages.get(messages.size() - 1).getUuid();
                Message.SystemMessage boundaryMarker = createCompactBoundaryMessage(
                        isAutoCompact ? "auto" : "manual",
                        0, // preCompactTokenCount — TODO: pass from caller
                        lastMsgUuid);

                // Build the summary user message.
                String summaryContent = compactPromptService.getCompactUserSummaryMessage(
                        summary, suppressFollowUpQuestions);
                Message.UserMessage summaryMsg = Message.UserMessage.builder()
                    .type("user")
                    .uuid(UUID.randomUUID().toString())
                    .content(List.of(new ContentBlock.TextBlock(summaryContent)))
                    .build();
                List<Message.UserMessage> summaryMessages = List.of(summaryMsg);

                // Run post-compact cleanup.
                postCompactCleanupService.runPostCompactCleanup(querySource);

                log.info("Compacted {} messages to summary", messages.size());

                return new CompactionResult(
                    boundaryMarker,
                    summaryMessages,
                    List.of(),  // attachments — TODO: generate post-compact file attachments
                    List.of(),  // hookResults — TODO: run session-start hooks
                    null,       // messagesToKeep
                    null,       // userDisplayMessage
                    null,       // preCompactTokenCount
                    null,       // postCompactTokenCount
                    null        // truePostCompactTokenCount
                );

            } catch (Exception e) {
                log.error("Compaction failed: {}", e.getMessage(), e);
                throw new RuntimeException("Compaction failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Convenience entry point that wraps the result as a simple message list.
     * Used by callers that don't need the full {@link CompactionResult} structure.
     */
    public CompletableFuture<List<Message>> compact(List<Message> messages, String model) {
        return compactConversation(messages, model, true, null, false, null)
            .thenApply(this::buildPostCompactMessages);
    }

    /**
     * Check if compaction is needed based on rough token count.
     * Delegates detailed calculation to {@link AutoCompactService}.
     */
    public boolean shouldCompact(List<Message> messages, int contextWindowSize) {
        long estimatedTokens = messages.stream()
            .mapToLong(this::estimateMessageTokens)
            .sum();
        // Compact when at 80% of context window.
        return estimatedTokens > contextWindowSize * 0.8;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Message.SystemMessage createCompactBoundaryMessage(
            String trigger, int preCompactTokenCount, String lastMessageUuid) {
        return Message.SystemMessage.builder()
            .type("system")
            .uuid(UUID.randomUUID().toString())
            .subtype(Message.SystemMessageSubtype.COMPACT_BOUNDARY)
            .content("Conversation compacted (" + trigger + ", ~" + preCompactTokenCount + " tokens)")
            .build();
    }

    private Message.UserMessage createMetaUserMessage(String content) {
        return Message.UserMessage.builder()
            .type("user")
            .uuid(UUID.randomUUID().toString())
            .content(List.of(new ContentBlock.TextBlock(content)))
            .build();
    }

    private String buildConversationText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage um) {
                sb.append("User: ");
                appendContentText(sb, um.getContent());
                sb.append("\n\n");
            } else if (msg instanceof Message.AssistantMessage am) {
                sb.append("Assistant: ");
                appendContentText(sb, am.getContent());
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }

    private void appendContentText(StringBuilder sb, List<ContentBlock> content) {
        if (content == null) return;
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.TextBlock text) {
                sb.append(text.getText());
            } else if (block instanceof ContentBlock.ToolUseBlock toolUse) {
                sb.append("[Tool: ").append(toolUse.getName()).append("]");
            } else if (block instanceof ContentBlock.ToolResultBlock) {
                sb.append("[Tool Result]");
            }
        }
    }

    private String extractTextFromResponse(AnthropicClient.MessageResponse response) {
        if (response.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : response.getContent()) {
            if ("text".equals(block.get("type"))) {
                sb.append(block.get("text"));
            }
        }
        return sb.toString();
    }

    private long estimateMessageTokens(Message msg) {
        if (msg instanceof Message.UserMessage um) {
            return estimateContentTokens(um.getContent());
        } else if (msg instanceof Message.AssistantMessage am) {
            return estimateContentTokens(am.getContent());
        }
        return 0;
    }

    private long estimateContentTokens(List<ContentBlock> content) {
        if (content == null) return 0;
        return content.stream()
            .mapToLong(block -> {
                if (block instanceof ContentBlock.TextBlock text) {
                    return text.getText() != null ? text.getText().length() / 4 : 0;
                }
                return 50; // Estimate for non-text blocks.
            })
            .sum();
    }

    private int roughTokenCount(List<Message> messages) {
        return (int) messages.stream().mapToLong(this::estimateMessageTokens).sum();
    }

    private String getPlainTextContent(Message.UserMessage um) {
        if (um.getContent() == null) return null;
        for (ContentBlock block : um.getContent()) {
            if (block instanceof ContentBlock.TextBlock t) {
                return t.getText();
            }
        }
        return null;
    }
}
