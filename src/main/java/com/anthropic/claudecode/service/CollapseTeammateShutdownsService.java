package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Collapse teammate shutdowns service.
 * Translated from src/utils/collapseTeammateShutdowns.ts
 *
 * Collapses consecutive in-process teammate shutdown task_status attachments
 * into a single {@code teammate_shutdown_batch} attachment with a count.
 *
 * TypeScript source logic:
 *   isTeammateShutdownAttachment: msg.type === 'attachment'
 *     && msg.attachment.type === 'task_status'
 *     && msg.attachment.taskType === 'in_process_teammate'
 *     && msg.attachment.status === 'completed'
 *
 * The Java model stores the attachment discriminator in AttachmentMessage.attachmentType.
 * Full nested attachment metadata is stored in a Map via the content field or a
 * separate metadata map; here we use the convention that:
 *   attachmentType == "task_status" with taskType == "in_process_teammate" and
 *   status == "completed"  → teammate shutdown attachment
 */
@Slf4j
@Service
public class CollapseTeammateShutdownsService {



    /**
     * Collapse consecutive in-process teammate shutdown task_status attachments
     * into a single {@code teammate_shutdown_batch} attachment with a count.
     *
     * Translated from collapseTeammateShutdowns() in collapseTeammateShutdowns.ts
     *
     * @param messages list of renderable messages
     * @return new list with consecutive shutdown attachments collapsed
     */
    public List<Message> collapseTeammateShutdowns(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages == null ? Collections.emptyList() : messages;

        List<Message> result = new ArrayList<>(messages.size());
        int i = 0;

        while (i < messages.size()) {
            Message msg = messages.get(i);

            if (isTeammateShutdownAttachment(msg)) {
                // Count consecutive shutdown attachments
                int count = 0;
                Message firstMsg = msg;
                while (i < messages.size() && isTeammateShutdownAttachment(messages.get(i))) {
                    count++;
                    i++;
                }

                if (count == 1) {
                    // Single shutdown: keep as-is
                    result.add(firstMsg);
                } else {
                    // Multiple shutdowns: collapse into a batch message
                    result.add(createBatchShutdownMessage(firstMsg.getUuid(), count));
                }
            } else {
                result.add(msg);
                i++;
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true when msg is an attachment of type task_status for a completed
     * in_process_teammate.
     *
     * TypeScript: msg.type === 'attachment'
     *   && msg.attachment.type === 'task_status'
     *   && msg.attachment.taskType === 'in_process_teammate'
     *   && msg.attachment.status === 'completed'
     */
    private static boolean isTeammateShutdownAttachment(Message msg) {
        if (!(msg instanceof Message.AttachmentMessage attachment)) return false;
        // The discriminator fields stored in attachmentType use the convention
        // "task_status:in_process_teammate:completed" or are inspected individually
        // via the metadata map (not shown here for brevity).
        // Primary check: attachmentType == "task_status" with task/status metadata.
        String at = attachment.getAttachmentType();
        if (!"task_status".equals(at)) return false;

        // Secondary check via content metadata convention (stored as first TextBlock text
        // in format "taskType=in_process_teammate;status=completed" when serialised by
        // AttachmentService). Fall back to checking the attachmentType prefix only if
        // no content is present.
        if (attachment.getContent() == null || attachment.getContent().isEmpty()) {
            // Cannot verify taskType/status — treat as non-shutdown
            return false;
        }

        // Inspect the first content block for serialised metadata
        var first = attachment.getContent().get(0);
        if (first instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock tb) {
            String text = tb.getText();
            return text != null
                && text.contains("taskType=in_process_teammate")
                && text.contains("status=completed");
        }

        return false;
    }

    /**
     * Build a teammate_shutdown_batch attachment message with a count.
     *
     * TypeScript output shape:
     * {
     *   type: 'attachment',
     *   uuid: msg.uuid,
     *   timestamp: msg.timestamp,
     *   attachment: { type: 'teammate_shutdown_batch', count }
     * }
     */
    private static Message createBatchShutdownMessage(String uuid, int count) {
        // Encode the batch metadata into a TextBlock using the same convention as above
        String meta = "taskType=teammate_shutdown_batch;count=" + count;
        return Message.AttachmentMessage.builder()
                .type("attachment")
                .uuid(uuid != null ? uuid : UUID.randomUUID().toString())
                .attachmentType("teammate_shutdown_batch")
                .content(List.of(new com.anthropic.claudecode.model.ContentBlock.TextBlock(meta)))
                .build();
    }
}
