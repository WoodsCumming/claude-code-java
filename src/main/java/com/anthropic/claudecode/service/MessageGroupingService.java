package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Message grouping service for compact operations.
 * Translated from src/services/compact/grouping.ts
 *
 * Groups messages at API-round boundaries.
 */
@Slf4j
@Service
public class MessageGroupingService {



    /**
     * Group messages by API round.
     * Translated from groupMessagesByApiRound() in grouping.ts
     *
     * Groups messages at API-round boundaries: one group per API round-trip.
     */
    public List<List<Message>> groupMessagesByApiRound(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return List.of();

        List<List<Message>> groups = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        String lastAssistantId = null;

        for (Message msg : messages) {
            if (msg instanceof Message.AssistantMessage assistantMsg) {
                String msgId = assistantMsg.getUuid();
                if (msgId != null && !msgId.equals(lastAssistantId) && !current.isEmpty()) {
                    groups.add(new ArrayList<>(current));
                    current.clear();
                }
                current.add(msg);
                lastAssistantId = msgId;
            } else {
                current.add(msg);
            }
        }

        if (!current.isEmpty()) {
            groups.add(current);
        }

        return groups;
    }

    /**
     * Split messages at compact boundary.
     * Translated from getMessagesAfterCompactBoundary() in messages.ts
     */
    public List<Message> getMessagesAfterCompactBoundary(List<Message> messages) {
        if (messages == null) return List.of();

        // Find the last compact boundary marker
        int lastBoundary = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof Message.SystemMessage sysMsg
                && sysMsg.getSubtypeEnum() == Message.SystemMessageSubtype.COMPACT_BOUNDARY) {
                lastBoundary = i;
                break;
            }
        }

        if (lastBoundary < 0) return new ArrayList<>(messages);
        return new ArrayList<>(messages.subList(lastBoundary + 1, messages.size()));
    }
}
