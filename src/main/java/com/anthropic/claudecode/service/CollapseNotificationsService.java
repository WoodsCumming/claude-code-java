package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.*;

/**
 * Service for collapsing background task notifications.
 * Translated from src/utils/collapseBackgroundBashNotifications.ts,
 * collapseHookSummaries.ts, and collapseTeammateShutdowns.ts
 *
 * Groups consecutive completed background tasks into a single summary notification.
 */
@Slf4j
@Service
public class CollapseNotificationsService {



    private static final Pattern STATUS_TAG_PATTERN = Pattern.compile("<status>([^<]*)</status>");
    private static final Pattern SUMMARY_TAG_PATTERN = Pattern.compile("<summary>([^<]*)</summary>");
    private static final String BACKGROUND_BASH_SUMMARY_PREFIX = "Ran background command";

    /**
     * Collapse consecutive completed background bash notifications.
     * Translated from collapseBackgroundBashNotifications() in collapseBackgroundBashNotifications.ts
     */
    public List<Message> collapseBackgroundBashNotifications(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        List<Message> result = new ArrayList<>();
        List<Message> pendingBashCompletions = new ArrayList<>();

        for (Message msg : messages) {
            if (isCompletedBackgroundBash(msg)) {
                pendingBashCompletions.add(msg);
            } else {
                if (!pendingBashCompletions.isEmpty()) {
                    if (pendingBashCompletions.size() == 1) {
                        result.add(pendingBashCompletions.get(0));
                    } else {
                        result.add(createCollapsedNotification(pendingBashCompletions.size()));
                    }
                    pendingBashCompletions.clear();
                }
                result.add(msg);
            }
        }

        // Flush remaining
        if (!pendingBashCompletions.isEmpty()) {
            if (pendingBashCompletions.size() == 1) {
                result.add(pendingBashCompletions.get(0));
            } else {
                result.add(createCollapsedNotification(pendingBashCompletions.size()));
            }
        }

        return result;
    }

    private boolean isCompletedBackgroundBash(Message msg) {
        if (!(msg instanceof Message.UserMessage userMsg)) return false;
        if (userMsg.getContent() == null || userMsg.getContent().isEmpty()) return false;

        var firstBlock = userMsg.getContent().get(0);
        if (!(firstBlock instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock textBlock)) return false;

        String text = textBlock.getText();
        if (text == null || !text.contains("<task-notification")) return false;

        // Check status
        Matcher statusMatcher = STATUS_TAG_PATTERN.matcher(text);
        if (!statusMatcher.find() || !"completed".equals(statusMatcher.group(1))) return false;

        // Check summary prefix
        Matcher summaryMatcher = SUMMARY_TAG_PATTERN.matcher(text);
        return summaryMatcher.find() && summaryMatcher.group(1).startsWith(BACKGROUND_BASH_SUMMARY_PREFIX);
    }

    private Message createCollapsedNotification(int count) {
        String text = "<task-notification><status>completed</status>" +
            "<summary>" + count + " background commands completed</summary></task-notification>";

        return Message.UserMessage.builder()
            .type("user")
            .uuid(UUID.randomUUID().toString())
            .content(List.of(new com.anthropic.claudecode.model.ContentBlock.TextBlock(text)))
            .build();
    }
}
