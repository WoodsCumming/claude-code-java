package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact grouping service for grouping messages by API round.
 * Translated from src/services/compact/grouping.ts
 *
 * Groups messages at API-round boundaries: one group per API round-trip.
 * A boundary fires when a NEW assistant response begins (different
 * message.id from the prior assistant). For well-formed conversations
 * this is an API-safe split point — the API contract requires every
 * tool_use to be resolved before the next assistant turn, so pairing
 * validity falls out of the assistant-id boundary. For malformed inputs
 * (dangling tool_use after resume/truncation) the fork's
 * ensureToolResultPairing repairs the split at API time.
 */
@Slf4j
@Service
public class CompactGroupingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompactGroupingService.class);


    /**
     * Group messages by API round (one group per API round-trip).
     * Translated from groupMessagesByApiRound() in grouping.ts
     *
     * A boundary fires when a NEW assistant response begins (different message.id).
     * Streaming chunks from the same API response share an id, so boundaries only
     * fire at the start of a genuinely new round.
     *
     * @param messages Flat list of conversation messages.
     * @return List of groups, each group representing one API round-trip.
     */
    public List<List<Message>> groupMessagesByApiRound(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<List<Message>> groups = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        // message.id of the most recently seen assistant. This is the sole
        // boundary gate — same logic as the TypeScript source.
        String lastAssistantId = null;

        for (Message msg : messages) {
            if (msg instanceof Message.AssistantMessage assistantMsg) {
                String msgId = assistantMsg.getMessageId();

                // A non-null id that differs from the previous assistant id
                // means a new API round is starting.
                if (msgId != null && !msgId.equals(lastAssistantId) && !current.isEmpty()) {
                    groups.add(new ArrayList<>(current));
                    current = new ArrayList<>();
                }
                lastAssistantId = msgId;
            }
            current.add(msg);
        }

        if (!current.isEmpty()) {
            groups.add(current);
        }

        return groups;
    }

    /**
     * Split messages into head (messages to compact) and tail (messages to keep).
     * Utility helper used by callers that need to preserve the most recent groups.
     *
     * @param messages   Full message list.
     * @param keepGroups Number of most-recent API-round groups to keep intact.
     * @return {@link MessageSplit} with {@code head} = groups to summarise,
     *         {@code tail} = groups to keep verbatim.
     */
    public MessageSplit splitForCompaction(List<Message> messages, int keepGroups) {
        List<List<Message>> groups = groupMessagesByApiRound(messages);

        if (groups.size() <= keepGroups) {
            return new MessageSplit(List.of(), messages);
        }

        int splitPoint = groups.size() - keepGroups;
        List<Message> head = new ArrayList<>();
        List<Message> tail = new ArrayList<>();

        for (int i = 0; i < groups.size(); i++) {
            if (i < splitPoint) {
                head.addAll(groups.get(i));
            } else {
                tail.addAll(groups.get(i));
            }
        }

        return new MessageSplit(head, tail);
    }

    /**
     * Flat pair of message lists representing the result of splitting for compaction.
     *
     * @param head Messages to be summarized (oldest).
     * @param tail Messages to be preserved verbatim (most recent).
     */
    public record MessageSplit(List<Message> head, List<Message> tail) {}
}
