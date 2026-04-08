package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Collapse hook summaries service.
 * Translated from src/utils/collapseHookSummaries.ts
 *
 * Collapses consecutive hook summary messages with the same label.
 */
@Slf4j
@Service
public class CollapseHookSummariesService {



    /**
     * Collapse consecutive hook summary messages with the same label.
     * Translated from collapseHookSummaries() in collapseHookSummaries.ts
     */
    public List<Message> collapseHookSummaries(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        List<Message> result = new ArrayList<>();
        int i = 0;

        while (i < messages.size()) {
            Message msg = messages.get(i);

            // Check if this is a labeled hook summary
            if (isLabeledHookSummary(msg)) {
                String label = getHookLabel(msg);
                List<Message> group = new ArrayList<>();
                group.add(msg);

                // Collect consecutive summaries with the same label
                while (i + 1 < messages.size()) {
                    Message next = messages.get(i + 1);
                    if (isLabeledHookSummary(next) && label.equals(getHookLabel(next))) {
                        group.add(next);
                        i++;
                    } else {
                        break;
                    }
                }

                if (group.size() > 1) {
                    // Merge into a single summary
                    result.add(createMergedHookSummary(group, label));
                } else {
                    result.add(msg);
                }
            } else {
                result.add(msg);
            }

            i++;
        }

        return result;
    }

    private boolean isLabeledHookSummary(Message msg) {
        if (!(msg instanceof Message.SystemMessage systemMsg)) return false;
        return "stop_hook_summary".equals(systemMsg.getSubtype())
            && systemMsg.getHookLabel() != null;
    }

    private String getHookLabel(Message msg) {
        if (!(msg instanceof Message.SystemMessage systemMsg)) return null;
        return systemMsg.getHookLabel();
    }

    private Message createMergedHookSummary(List<Message> group, String label) {
        // Return the last message as the merged summary
        return group.get(group.size() - 1);
    }
}
