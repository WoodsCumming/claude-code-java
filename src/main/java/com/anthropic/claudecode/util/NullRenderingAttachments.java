package com.anthropic.claudecode.util;

import java.util.Set;

/**
 * Null-rendering attachment type utilities.
 * Translated from src/components/messages/nullRenderingAttachments.ts
 *
 * Attachment types that the UI renders as {@code null} (no visible output).
 * Filtering these out before the render cap / message count ensures they do
 * not inflate the "N messages" counter or consume the 200-message render
 * budget (CC-724).
 */
public class NullRenderingAttachments {

    /**
     * The set of attachment {@code type} strings that produce no visible UI output.
     * Corresponds to NULL_RENDERING_TYPES in TypeScript.
     *
     * <p>TypeScript's {@code as const satisfies readonly Attachment['type'][]} constraint
     * is enforced here by ensuring every value matches a known attachment type.
     */
    public static final Set<String> NULL_RENDERING_ATTACHMENT_TYPES = Set.of(
        "hook_success",
        "hook_additional_context",
        "hook_cancelled",
        "command_permissions",
        "agent_mention",
        "budget_usd",
        "critical_system_reminder",
        "edited_image_file",
        "edited_text_file",
        "opened_file_in_ide",
        "output_style",
        "plan_mode",
        "plan_mode_exit",
        "plan_mode_reentry",
        "structured_output",
        "team_context",
        "todo_reminder",
        "context_efficiency",
        "deferred_tools_delta",
        "mcp_instructions_delta",
        "companion_intro",
        "token_usage",
        "ultrathink_effort",
        "max_turns_reached",
        "task_reminder",
        "auto_mode",
        "auto_mode_exit",
        "output_token_usage",
        "pen_mode_enter",
        "pen_mode_exit",
        "verify_plan_reminder",
        "current_session_memory",
        "compaction_reminder",
        "date_change"
    );

    /**
     * Returns {@code true} when the message is an attachment that the UI
     * renders as null with no visible output.
     *
     * Translated from isNullRenderingAttachment() in nullRenderingAttachments.ts
     *
     * @param messageType     the {@code type} field of the message (e.g. {@code "attachment"})
     * @param attachmentType  the {@code attachment.type} field; ignored when
     *                        {@code messageType} is not {@code "attachment"}
     * @return true when the message is a null-rendering attachment
     */
    public static boolean isNullRenderingAttachment(String messageType, String attachmentType) {
        if (!"attachment".equals(messageType)) return false;
        return NULL_RENDERING_ATTACHMENT_TYPES.contains(attachmentType);
    }

    private NullRenderingAttachments() {}
}
