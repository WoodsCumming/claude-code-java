package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for collapsing consecutive completed background bash task notifications
 * into a single synthetic summary notification.
 *
 * Translated from src/utils/collapseBackgroundBashNotifications.ts
 *
 * Collapses consecutive completed-background-bash task-notifications into a
 * single synthetic "N background commands completed" notification. Failed/killed
 * tasks and agent/workflow notifications are left alone. Monitor stream
 * events have no status tag and never match.
 *
 * Pass-through in verbose mode so all completions are shown individually.
 */
@Slf4j
@Service
public class CollapseBackgroundBashNotificationsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CollapseBackgroundBashNotificationsService.class);


    // XML tag constants mirroring constants/xml.ts
    public static final String STATUS_TAG = "status";
    public static final String SUMMARY_TAG = "summary";
    public static final String TASK_NOTIFICATION_TAG = "task-notification";

    // Prefix that distinguishes bash-kind LocalShellTask completions from
    // agent/workflow/monitor notifications (mirrors BACKGROUND_BASH_SUMMARY_PREFIX).
    public static final String BACKGROUND_BASH_SUMMARY_PREFIX = "Ran background bash command";

    /**
     * Represents a renderable message with role and text content.
     * Simplified model for the collapse logic.
     */
    public record RenderableMessage(
            String type,   // "user" or "assistant"
            String role,
            String text    // raw text content of the first content block
    ) {}

    /**
     * Returns true when the message is a completed background bash task notification.
     * Only "completed" status collapses — failed/killed messages stay visible individually.
     * Mirrors isCompletedBackgroundBash() in collapseBackgroundBashNotifications.ts
     */
    public boolean isCompletedBackgroundBash(RenderableMessage msg) {
        if (!"user".equals(msg.type())) return false;
        String text = msg.text();
        if (text == null) return false;
        if (!text.contains("<" + TASK_NOTIFICATION_TAG)) return false;
        if (!"completed".equals(extractTag(text, STATUS_TAG))) return false;
        String summary = extractTag(text, SUMMARY_TAG);
        return summary != null && summary.startsWith(BACKGROUND_BASH_SUMMARY_PREFIX);
    }

    /**
     * Collapses consecutive completed-background-bash task-notifications into a
     * single synthetic "N background commands completed" notification.
     *
     * Mirrors collapseBackgroundBashNotifications() in collapseBackgroundBashNotifications.ts
     *
     * @param messages the full list of renderable messages
     * @param verbose  when true, no collapsing is applied (ctrl+O verbose mode)
     * @param isFullscreenEnabled whether fullscreen env is enabled (no-op when false)
     * @return processed message list, potentially with consecutive completions collapsed
     */
    public List<RenderableMessage> collapseBackgroundBashNotifications(
            List<RenderableMessage> messages,
            boolean verbose,
            boolean isFullscreenEnabled) {

        if (!isFullscreenEnabled) return messages;
        if (verbose) return messages;

        List<RenderableMessage> result = new ArrayList<>();
        int i = 0;

        while (i < messages.size()) {
            RenderableMessage msg = messages.get(i);
            if (isCompletedBackgroundBash(msg)) {
                int count = 0;
                while (i < messages.size() && isCompletedBackgroundBash(messages.get(i))) {
                    count++;
                    i++;
                }
                if (count == 1) {
                    result.add(msg);
                } else {
                    // Synthesize a collapsed task-notification
                    String syntheticText = String.format(
                            "<%s><%s>completed</%s><%s>%d background commands completed</%s></%s>",
                            TASK_NOTIFICATION_TAG,
                            STATUS_TAG, STATUS_TAG,
                            SUMMARY_TAG, count, SUMMARY_TAG,
                            TASK_NOTIFICATION_TAG
                    );
                    result.add(new RenderableMessage(
                            msg.type(),
                            msg.role(),
                            syntheticText
                    ));
                }
            } else {
                result.add(msg);
                i++;
            }
        }

        return result;
    }

    /**
     * Extracts the text content of a named XML tag from the given text.
     * Mirrors extractTag() in messages.ts
     *
     * @param text    the source text
     * @param tagName the XML tag name to extract
     * @return the inner text of the first matching tag, or null if not found
     */
    public String extractTag(String text, String tagName) {
        if (text == null || tagName == null) return null;
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int start = text.indexOf(openTag);
        if (start < 0) return null;
        int contentStart = start + openTag.length();
        int end = text.indexOf(closeTag, contentStart);
        if (end < 0) return null;
        return text.substring(contentStart, end);
    }
}
