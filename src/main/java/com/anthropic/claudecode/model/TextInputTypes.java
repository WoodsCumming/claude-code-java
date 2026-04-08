package com.anthropic.claudecode.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Text input types.
 * Translated from src/types/textInputTypes.ts
 *
 * Covers all input-component props, state types, queue types, and
 * the two utility functions isValidImagePaste() / getImagePasteIds().
 */
public final class TextInputTypes {

    // =========================================================================
    // InlineGhostText
    // =========================================================================

    /**
     * Inline ghost text for mid-input command autocomplete.
     * Corresponds to TypeScript: type InlineGhostText
     */
    public record InlineGhostText(
            /** The ghost text to display (e.g. "mit" for /commit). */
            String text,
            /** The full command name (e.g. "commit"). */
            String fullCommand,
            /** Position in the input where the ghost text should appear. */
            int insertPosition
    ) {}

    // =========================================================================
    // VimMode
    // =========================================================================

    /**
     * Vim editor modes.
     * Corresponds to TypeScript: type VimMode = 'INSERT' | 'NORMAL'
     */
    public enum VimMode {
        INSERT, NORMAL
    }

    // =========================================================================
    // QueuePriority
    // =========================================================================

    /**
     * Queue priority levels.
     *
     *  - NOW   — Interrupt and send immediately; aborts any in-flight tool call.
     *  - NEXT  — Mid-turn drain; let the current tool call finish, then send.
     *  - LATER — End-of-turn drain; wait for the current turn to finish.
     *
     * Corresponds to TypeScript: type QueuePriority = 'now' | 'next' | 'later'
     */
    public enum QueuePriority {
        NOW("now"),
        NEXT("next"),
        LATER("later");

        private final String value;

        QueuePriority(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // =========================================================================
    // PromptInputMode
    // =========================================================================

    /**
     * Input modes for the prompt component.
     * Corresponds to TypeScript:
     *   type PromptInputMode = 'bash' | 'prompt' | 'orphaned-permission' | 'task-notification'
     */
    public enum PromptInputMode {
        BASH("bash"),
        PROMPT("prompt"),
        ORPHANED_PERMISSION("orphaned-permission"),
        TASK_NOTIFICATION("task-notification");

        private final String value;

        PromptInputMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        /** Returns true for modes that are not notification-only (i.e. editable). */
        public boolean isEditable() {
            return this != TASK_NOTIFICATION;
        }
    }

    // =========================================================================
    // BaseTextInputProps
    // =========================================================================

    /**
     * Base props for text input components.
     * In Java these are expressed as a builder-friendly value type rather than
     * a React props interface. UI frameworks that wrap terminal rendering can
     * consume this record to configure a text input widget.
     *
     * Corresponds to TypeScript: type BaseTextInputProps
     */
    public record BaseTextInputProps(
            String value,
            int columns,
            String placeholder,
            Boolean multiline,
            Boolean focus,
            String mask,
            Boolean showCursor,
            Boolean highlightPastedText,
            Integer maxVisibleLines,
            Boolean disableCursorMovementForUpDownKeys,
            Boolean disableEscapeDoublePress,
            int cursorOffset,
            String argumentHint,
            Boolean dimColor,
            InlineGhostText inlineGhostText
    ) {}

    // =========================================================================
    // VimTextInputProps
    // =========================================================================

    /**
     * Extended props for VimTextInput.
     * Corresponds to TypeScript: type VimTextInputProps = BaseTextInputProps & { ... }
     */
    public record VimTextInputProps(
            BaseTextInputProps base,
            VimMode initialMode,         // optional
            Runnable onModeChange        // optional callback — VimMode → void
    ) {}

    // =========================================================================
    // BaseInputState
    // =========================================================================

    /**
     * Common properties for input hook results.
     * Corresponds to TypeScript: type BaseInputState
     */
    public record BaseInputState(
            String renderedValue,
            int offset,
            /** Cursor line (0-indexed) within the rendered text, accounting for wrapping. */
            int cursorLine,
            /** Cursor column (display-width) within the current line. */
            int cursorColumn,
            /** Character offset in the full text where the viewport starts (0 when no windowing). */
            int viewportCharOffset,
            /** Character offset in the full text where the viewport ends. */
            int viewportCharEnd,
            Boolean isPasting,
            PasteState pasteState
    ) {
        /** Paste buffer state used during multi-chunk paste operations. */
        public record PasteState(List<String> chunks) {}
    }

    /** State for a plain text input. */
    public record TextInputState(BaseInputState base) {}

    /** State for a vim-mode input. */
    public record VimInputState(BaseInputState base, VimMode mode) {}

    // =========================================================================
    // QueuedCommand
    // =========================================================================

    /**
     * A command placed on the input queue.
     * Corresponds to TypeScript: type QueuedCommand
     */
    public record QueuedCommand(
            /** String value or list of content-block param maps. */
            Object value,
            PromptInputMode mode,
            /** Defaults to the priority implied by mode when enqueued. */
            QueuePriority priority,
            UUID uuid,
            /** Raw pasted contents indexed by insertion order. */
            Map<Integer, Map<String, Object>> pastedContents,
            /**
             * Input string before [Pasted text #N] placeholders were expanded.
             * Used for ultraplan keyword detection.
             */
            String preExpansionValue,
            Boolean skipSlashCommands,
            Boolean bridgeOrigin,
            Boolean isMeta,
            /** Provenance of this command (null = human keyboard). */
            String origin,
            /** Workload tag for billing-header attribution. */
            String workload,
            /** Target agent ID; null means the main thread. */
            String agentId,
            OrphanedPermission orphanedPermission
    ) {}

    // =========================================================================
    // OrphanedPermission
    // =========================================================================

    /**
     * An unresolved permission request that has been orphaned (the agent
     * that issued it is no longer active).
     * Corresponds to TypeScript: type OrphanedPermission
     */
    public record OrphanedPermission(
            Object permissionResult,    // PermissionsTypes.PermissionResult
            Object assistantMessage     // full AssistantMessage
    ) {}

    // =========================================================================
    // Utility functions
    // =========================================================================

    /**
     * Return true when the pasted content map represents a valid (non-empty) image.
     * Corresponds to TypeScript: function isValidImagePaste(c: PastedContent): boolean
     */
    public static boolean isValidImagePaste(Map<String, Object> pastedContent) {
        if (pastedContent == null) return false;
        if (!"image".equals(pastedContent.get("type"))) return false;
        Object content = pastedContent.get("content");
        if (content instanceof String s) return !s.isEmpty();
        return content != null;
    }

    /**
     * Extract image paste IDs from a QueuedCommand's pastedContents map.
     * Returns null when there are no valid image pastes.
     * Corresponds to TypeScript: function getImagePasteIds(pastedContents): number[] | undefined
     */
    public static List<Integer> getImagePasteIds(Map<Integer, Map<String, Object>> pastedContents) {
        if (pastedContents == null || pastedContents.isEmpty()) return null;
        List<Integer> ids = pastedContents.entrySet().stream()
                .filter(e -> isValidImagePaste(e.getValue()))
                .map(e -> {
                    Object id = e.getValue().get("id");
                    return id instanceof Number n ? n.intValue() : e.getKey();
                })
                .toList();
        return ids.isEmpty() ? null : ids;
    }

    private TextInputTypes() {}
}
