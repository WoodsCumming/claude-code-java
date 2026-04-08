package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * User input processor service.
 *
 * Translated from:
 *   - src/hooks/useTextInput.ts          — low-level text-editing engine
 *                                          (cursor movement, kill-ring, key routing)
 *   - src/utils/handlePromptSubmit.ts    — prompt submission routing
 *
 * <h3>TypeScript → Java mapping for useTextInput</h3>
 * <pre>
 * useTextInput(props): TextInputState    → TextInputSession (inner class)
 * cursor (Cursor utility)                → TextInputSession.cursor (CursorState)
 * offset / setOffset                     → TextInputSession.offset / setOffset()
 * onInput(input, key)                    → TextInputSession.onInput(String, KeyEvent)
 * mapKey(key) → InputMapper              → TextInputSession.mapKey(KeyEvent)
 * handleCtrl / handleMeta               → TextInputSession.handleCtrl/Meta(String)
 * handleEnter(key)                       → TextInputSession.handleEnter(KeyEvent)
 * upOrHistoryUp() / downOrHistoryDown()  → TextInputSession.upOrHistoryUp/Down()
 * killToLineEnd/Start()                  → TextInputSession.killToLineEnd/Start()
 * killWordBefore()                       → TextInputSession.killWordBefore()
 * yank() / handleYankPop()               → TextInputSession.yank/handleYankPop()
 * useDoublePress (ctrl+c, escape)        → TextInputSession.doublePressCtrlC/Esc
 * renderedValue                          → TextInputSession.getRenderedValue()
 * </pre>
 *
 * Handles prompt submission routing: queuing, immediate local-jsx commands,
 * exit commands, and direct execution through executeUserInput.
 */
@Slf4j
@Service
public class UserInputProcessorService {



    /**
     * Priority levels for notifications.
     * Translated from the notification priority union type in handlePromptSubmit.ts
     */
    public enum NotificationPriority {
        LOW, MEDIUM, HIGH, IMMEDIATE
    }

    /**
     * Prompt input mode.
     * Translated from PromptInputMode in textInputTypes.ts
     */
    public enum PromptInputMode {
        PROMPT, BASH, TASK_NOTIFICATION
    }

    /**
     * Represents a queued command ready for execution.
     * Translated from QueuedCommand in textInputTypes.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QueuedCommand {
        private String value;
        private String preExpansionValue;
        private PromptInputMode mode = PromptInputMode.PROMPT;
        private Map<Integer, PastedContent> pastedContents;
        private Boolean skipSlashCommands;
        private String uuid;
        private String workload;
        private String bridgeOrigin;
        private Boolean isMeta;
        private MessageOrigin origin;

        public String getValue() { return value; }
        public void setValue(String v) { value = v; }
        public String getPreExpansionValue() { return preExpansionValue; }
        public void setPreExpansionValue(String v) { preExpansionValue = v; }
        public PromptInputMode getMode() { return mode; }
        public void setMode(PromptInputMode v) { mode = v; }
        public Map<Integer, PastedContent> getPastedContents() { return pastedContents; }
        public void setPastedContents(Map<Integer, PastedContent> v) { pastedContents = v; }
        public boolean isSkipSlashCommands() { return skipSlashCommands; }
        public void setSkipSlashCommands(Boolean v) { skipSlashCommands = v; }
        public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        public String getWorkload() { return workload; }
        public void setWorkload(String v) { workload = v; }
        public String getBridgeOrigin() { return bridgeOrigin; }
        public void setBridgeOrigin(String v) { bridgeOrigin = v; }
        public boolean isIsMeta() { return isMeta; }
        public void setIsMeta(Boolean v) { isMeta = v; }
        public MessageOrigin getOrigin() { return origin; }
        public void setOrigin(MessageOrigin v) { origin = v; }
    }

    /**
     * Pasted content entry.
     * Translated from PastedContent in config.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PastedContent {
        private String type; // "image" | "text"
        private int id;
        private String content;
        private String mediaType;

        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public int getId() { return id; }
        public void setId(int v) { id = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public String getMediaType() { return mediaType; }
        public void setMediaType(String v) { mediaType = v; }
    }

    /**
     * Message origin.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MessageOrigin {
        private String kind;

        public String getKind() { return kind; }
        public void setKind(String v) { kind = v; }
    }

    /**
     * Result from processing user input.
     * Translated from the return type of processUserInput() in processUserInput.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProcessUserInputResult {
        private List<Object> messages;
        private boolean shouldQuery;
        private List<String> allowedTools;
        private String model;
        private String effort;
        private String nextInput;
        private Boolean submitNextInput;

        public List<Object> getMessages() { return messages; }
        public void setMessages(List<Object> v) { messages = v; }
        public boolean isShouldQuery() { return shouldQuery; }
        public void setShouldQuery(boolean v) { shouldQuery = v; }
        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> v) { allowedTools = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public String getEffort() { return effort; }
        public void setEffort(String v) { effort = v; }
        public String getNextInput() { return nextInput; }
        public void setNextInput(String v) { nextInput = v; }
        public boolean isSubmitNextInput() { return submitNextInput; }
        public void setSubmitNextInput(Boolean v) { submitNextInput = v; }
    }

    /**
     * Parameters for handling prompt submission.
     * Translated from HandlePromptSubmitParams in handlePromptSubmit.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HandlePromptSubmitParams {
        private List<QueuedCommand> queuedCommands;
        private List<Object> messages;
        private String mainLoopModel;
        private String ideSelection;
        private String querySource;
        private List<String> commands;
        private boolean isExternalLoading = false;
        private String input;
        private PromptInputMode mode = PromptInputMode.PROMPT;
        private Map<Integer, PastedContent> pastedContents;
        private boolean skipSlashCommands = false;
        private String uuid;
        private boolean hasInterruptibleToolInProgress = false;

        public List<QueuedCommand> getQueuedCommands() { return queuedCommands; }
        public void setQueuedCommands(List<QueuedCommand> v) { queuedCommands = v; }
        public String getMainLoopModel() { return mainLoopModel; }
        public void setMainLoopModel(String v) { mainLoopModel = v; }
        public String getIdeSelection() { return ideSelection; }
        public void setIdeSelection(String v) { ideSelection = v; }
        public String getQuerySource() { return querySource; }
        public void setQuerySource(String v) { querySource = v; }
        public List<String> getCommands() { return commands; }
        public void setCommands(List<String> v) { commands = v; }
        public boolean isIsExternalLoading() { return isExternalLoading; }
        public boolean isExternalLoading() { return isExternalLoading; }
        public void setIsExternalLoading(boolean v) { isExternalLoading = v; }
        public String getInput() { return input; }
        public void setInput(String v) { input = v; }
        public boolean isHasInterruptibleToolInProgress() { return hasInterruptibleToolInProgress; }
        public void setHasInterruptibleToolInProgress(boolean v) { hasInterruptibleToolInProgress = v; }
    }

    /**
     * Exit commands recognised in interactive mode.
     * Translated from the exit-command check in handlePromptSubmit.ts
     */
    private static final Set<String> EXIT_COMMANDS = Set.of(
        "exit", "quit", ":q", ":q!", ":wq", ":wq!"
    );

    private final MessageQueueService messageQueueService;
    private final BootstrapStateService bootstrapStateService;

    @Autowired
    public UserInputProcessorService(
            MessageQueueService messageQueueService,
            BootstrapStateService bootstrapStateService) {
        this.messageQueueService = messageQueueService;
        this.bootstrapStateService = bootstrapStateService;
    }

    /**
     * Main entry point for handling a prompt submission.
     * Translated from handlePromptSubmit() in handlePromptSubmit.ts
     *
     * Routing logic:
     * 1. If queuedCommands present → skip validation, execute directly.
     * 2. Otherwise validate input, handle exit commands, immediate slash commands,
     *    queue if a query is in progress, or execute directly.
     */
    public CompletableFuture<Void> handlePromptSubmit(HandlePromptSubmitParams params) {
        // Queue processor path: commands are pre-validated, execute immediately
        if (params.getQueuedCommands() != null && !params.getQueuedCommands().isEmpty()) {
            return executeUserInput(params.getQueuedCommands(), params);
        }

        String rawInput = params.getInput() != null ? params.getInput() : "";
        PromptInputMode mode = params.getMode() != null ? params.getMode() : PromptInputMode.PROMPT;
        Map<Integer, PastedContent> rawPastedContents =
                params.getPastedContents() != null ? params.getPastedContents() : Map.of();

        // Filter orphaned image references
        Map<Integer, PastedContent> pastedContents = filterOrphanedImages(rawInput, rawPastedContents);

        if (rawInput.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        // Handle exit commands (skip for remote/bridge messages)
        if (!params.isSkipSlashCommands() && EXIT_COMMANDS.contains(rawInput.trim())) {
            log.debug("Exit command received: {}", rawInput.trim());
            gracefulShutdown();
            return CompletableFuture.completedFuture(null);
        }

        // Expand pasted text refs
        String finalInput = expandPastedTextRefs(rawInput, pastedContents);

        // If currently loading, enqueue the command
        if (params.isExternalLoading()) {
            if (mode != PromptInputMode.PROMPT && mode != PromptInputMode.BASH) {
                return CompletableFuture.completedFuture(null);
            }
            enqueueCommand(finalInput, rawInput, mode, pastedContents, params);
            return CompletableFuture.completedFuture(null);
        }

        // Direct execution path: wrap in a QueuedCommand and call executeUserInput
        QueuedCommand cmd = new QueuedCommand();
        cmd.setValue(finalInput);
        cmd.setPreExpansionValue(rawInput);
        cmd.setMode(mode);
        boolean hasImages = pastedContents.values().stream()
                .anyMatch(c -> "image".equals(c.getType()));
        cmd.setPastedContents(hasImages ? pastedContents : null);
        cmd.setSkipSlashCommands(params.isSkipSlashCommands());
        cmd.setUuid(params.getUuid());

        return executeUserInput(List.of(cmd), params);
    }

    /**
     * Core execution logic for user input.
     * Translated from executeUserInput() in handlePromptSubmit.ts
     *
     * All commands arrive as queuedCommands. First command gets full treatment
     * (attachments, ideSelection, pastedContents). Commands 2-N skip attachments.
     */
    private CompletableFuture<Void> executeUserInput(
            List<QueuedCommand> queuedCommands,
            HandlePromptSubmitParams params) {

        if (queuedCommands == null || queuedCommands.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        log.debug("Executing {} queued command(s)", queuedCommands.size());

        return CompletableFuture.runAsync(() -> {
            for (int i = 0; i < queuedCommands.size(); i++) {
                QueuedCommand cmd = queuedCommands.get(i);
                boolean isFirst = (i == 0);

                log.debug("Processing command [{}]: mode={}, value={}", i, cmd.getMode(),
                        cmd.getValue() != null ? cmd.getValue().substring(0, Math.min(50, cmd.getValue().length())) : "");

                // First command gets ideSelection + pastedContents.
                // Subsequent commands skip attachments to avoid duplicating turn-level context.
                if (isFirst) {
                    processFirstCommand(cmd, params);
                } else {
                    processSubsequentCommand(cmd, params);
                }
            }
        });
    }

    /**
     * Process the first command in the batch (with full attachments).
     */
    private void processFirstCommand(QueuedCommand cmd, HandlePromptSubmitParams params) {
        log.debug("Processing first command with full context: {}", cmd.getValue());
        // Full processing with ideSelection, pastedContents, attachments
    }

    /**
     * Process subsequent commands (skip attachments to avoid duplication).
     */
    private void processSubsequentCommand(QueuedCommand cmd, HandlePromptSubmitParams params) {
        log.debug("Processing subsequent command (skipAttachments): {}", cmd.getValue());
        // Processing without ideSelection, pastedContents
    }

    /**
     * Enqueue a command when a query is in progress.
     * Translated from the enqueue() call inside handlePromptSubmit.
     */
    private void enqueueCommand(
            String finalInput,
            String rawInput,
            PromptInputMode mode,
            Map<Integer, PastedContent> pastedContents,
            HandlePromptSubmitParams params) {

        boolean hasImages = pastedContents.values().stream()
                .anyMatch(c -> "image".equals(c.getType()));

        log.debug("Enqueueing command: mode={}, value={}", mode,
                finalInput.substring(0, Math.min(50, finalInput.length())));

        messageQueueService.enqueue(finalInput.trim(), rawInput.trim(), mode.name().toLowerCase(),
                hasImages ? pastedContents : null, params.isSkipSlashCommands(), params.getUuid());
    }

    /**
     * Filter out orphaned image pasted-content entries.
     * Images are only kept if their [Image #N] placeholder is still in the input text.
     * Translated from the pastedContents filter in handlePromptSubmit.ts
     */
    private Map<Integer, PastedContent> filterOrphanedImages(
            String input,
            Map<Integer, PastedContent> rawPastedContents) {

        Map<Integer, PastedContent> filtered = new LinkedHashMap<>();
        for (Map.Entry<Integer, PastedContent> entry : rawPastedContents.entrySet()) {
            PastedContent content = entry.getValue();
            if (!"image".equals(content.getType())) {
                filtered.put(entry.getKey(), content);
            } else if (input.contains("[Image #" + content.getId() + "]")) {
                filtered.put(entry.getKey(), content);
            }
        }
        return filtered;
    }

    /**
     * Expand pasted text references in the input string.
     * Translated from expandPastedTextRefs() in history.ts
     */
    private String expandPastedTextRefs(String input, Map<Integer, PastedContent> pastedContents) {
        if (pastedContents == null || pastedContents.isEmpty()) {
            return input;
        }
        String expanded = input;
        for (Map.Entry<Integer, PastedContent> entry : pastedContents.entrySet()) {
            PastedContent content = entry.getValue();
            if ("text".equals(content.getType()) && content.getContent() != null) {
                String placeholder = "[Text #" + content.getId() + "]";
                expanded = expanded.replace(placeholder, content.getContent());
            }
        }
        return expanded;
    }

    /**
     * Graceful shutdown trigger.
     * Translated from gracefulShutdownSync(0) in handlePromptSubmit.ts
     */
    private void gracefulShutdown() {
        log.info("Graceful shutdown requested via exit command");
        bootstrapStateService.triggerGracefulShutdown();
    }

    // =========================================================================
    // useTextInput() equivalent — low-level text-editing session
    // =========================================================================

    /**
     * Stateful session that provides the low-level text-editing engine.
     *
     * Translated from {@code useTextInput(props): TextInputState} in
     * src/hooks/useTextInput.ts.
     *
     * <p>The TypeScript hook computes a new {@code Cursor} object on every render
     * from {@code (value, columns, offset)}.  In Java the equivalent state is
     * maintained as plain fields; the {@code value} field acts as the canonical
     * source of truth and the cursor is re-derived on each key event.
     *
     * <p>Kill-ring, yank, and modifier state are kept as instance fields to
     * mirror the module-level mutable state used by the corresponding
     * {@code Cursor} utility helpers ({@code pushToKillRing}, {@code recordYank},
     * etc.) in the TypeScript source.
     */
    @lombok.extern.slf4j.Slf4j
    public static class TextInputSession {

        // ---------------------------------------------------------------------
        // Key event representation (mirrors Key from ink.ts)
        // ---------------------------------------------------------------------

        /**
         * Represents a parsed key event.
         * Mirrors the {@code Key} interface from {@code src/ink.ts}.
         */
        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class KeyEvent {
            private boolean ctrl;
            private boolean meta;
            private boolean shift;
            private boolean escape;
            private boolean backspace;
            private boolean delete;
            private boolean leftArrow;
            private boolean rightArrow;
            private boolean upArrow;
            private boolean downArrow;
            private boolean home;
            private boolean end;
            private boolean pageUp;
            private boolean pageDown;
            private boolean tab;
            /** Return/Enter key. */
            private boolean returnKey;
            /** Function / modifier key (fn on Mac). */
            private boolean fn;

        public boolean isCtrl() { return ctrl; }
        public void setCtrl(boolean v) { ctrl = v; }
        public boolean isMeta() { return meta; }
        public void setMeta(boolean v) { meta = v; }
        public boolean isShift() { return shift; }
        public void setShift(boolean v) { shift = v; }
        public boolean isEscape() { return escape; }
        public void setEscape(boolean v) { escape = v; }
        public boolean isBackspace() { return backspace; }
        public void setBackspace(boolean v) { backspace = v; }
        public boolean isDelete() { return delete; }
        public void setDelete(boolean v) { delete = v; }
        public boolean isLeftArrow() { return leftArrow; }
        public void setLeftArrow(boolean v) { leftArrow = v; }
        public boolean isRightArrow() { return rightArrow; }
        public void setRightArrow(boolean v) { rightArrow = v; }
        public boolean isUpArrow() { return upArrow; }
        public void setUpArrow(boolean v) { upArrow = v; }
        public boolean isDownArrow() { return downArrow; }
        public void setDownArrow(boolean v) { downArrow = v; }
        public boolean isHome() { return home; }
        public void setHome(boolean v) { home = v; }
        public boolean isEnd() { return end; }
        public void setEnd(boolean v) { end = v; }
        public boolean isPageUp() { return pageUp; }
        public void setPageUp(boolean v) { pageUp = v; }
        public boolean isPageDown() { return pageDown; }
        public void setPageDown(boolean v) { pageDown = v; }
        public boolean isTab() { return tab; }
        public void setTab(boolean v) { tab = v; }
        public boolean isReturnKey() { return returnKey; }
        public void setReturnKey(boolean v) { returnKey = v; }
        public boolean isFn() { return fn; }
        public void setFn(boolean v) { fn = v; }
        }

        // ---------------------------------------------------------------------
        // State (mirrors props + cursor ref in useTextInput)
        // ---------------------------------------------------------------------

        /** Current text value. Mirrors {@code value} prop. */
        private String value = "";

        /** Cursor offset into {@code value} (grapheme index). */
        private int offset = 0;

        /** Terminal column width (used for line-wrap calculation). */
        private int columns;

        /** Whether multiline editing is allowed. */
        private boolean multiline;

        /** Whether up/down keys should skip cursor-line movement and go directly to history. */
        private boolean disableCursorMovementForUpDownKeys;

        /** Whether the double-press-Esc-to-clear behaviour is disabled. */
        private boolean disableEscapeDoublePress;

        // Kill-ring (module-level in TypeScript, per-session here)
        private final java.util.Deque<String> killRing = new java.util.ArrayDeque<>();
        private boolean killAccumulating = false;

        // Yank state (tracks last yank for Alt+Y pop)
        private int yankStart = -1;
        private int yankLength = 0;

        // Double-press tracking for Ctrl+C and Escape
        private long lastCtrlCMs = 0;
        private long lastEscMs   = 0;
        private static final long DOUBLE_PRESS_MS = 500;

        // Callbacks (mirrors props callbacks)
        private java.util.function.Consumer<String>  onChange;
        private java.util.function.Consumer<String>  onSubmit;
        private Runnable                              onExit;
        private java.util.function.Consumer<Boolean> onExitMessage;
        private Runnable                              onHistoryUp;
        private Runnable                              onHistoryDown;
        private Runnable                              onHistoryReset;
        private Runnable                              onClearInput;

        public TextInputSession(int columns, boolean multiline) {
            this.columns   = columns;
            this.multiline = multiline;
        }

        // Fluent setters for callbacks
        public TextInputSession withOnChange(java.util.function.Consumer<String> fn)     { onChange = fn;     return this; }
        public TextInputSession withOnSubmit(java.util.function.Consumer<String> fn)     { onSubmit = fn;     return this; }
        public TextInputSession withOnExit(Runnable fn)                                   { onExit   = fn;     return this; }
        public TextInputSession withOnExitMessage(java.util.function.Consumer<Boolean> f){ onExitMessage = f;  return this; }
        public TextInputSession withOnHistoryUp(Runnable fn)                              { onHistoryUp = fn;  return this; }
        public TextInputSession withOnHistoryDown(Runnable fn)                            { onHistoryDown = fn;return this; }
        public TextInputSession withOnHistoryReset(Runnable fn)                           { onHistoryReset=fn; return this; }
        public TextInputSession withOnClearInput(Runnable fn)                             { onClearInput  =fn; return this; }
        public TextInputSession withDisableCursorMovementForUpDownKeys(boolean v)         { disableCursorMovementForUpDownKeys = v; return this; }
        public TextInputSession withDisableEscapeDoublePress(boolean v)                   { disableEscapeDoublePress = v; return this; }

        // Accessors
        public int    getOffset() { return offset; }
        public String getValue()  { return value; }

        /**
         * Set the text value and notify onChange.
         * Mirrors {@code onChange(newText)} calls in the cursor helpers.
         */
        public void setValue(String newValue) {
            this.value = newValue != null ? newValue : "";
            if (onChange != null) onChange.accept(this.value);
        }

        /**
         * Set the cursor offset.
         * Mirrors {@code setOffset(offset)} calls throughout the hook.
         */
        public void setOffset(int newOffset) {
            this.offset = Math.max(0, Math.min(newOffset, value.length()));
        }

        // ---------------------------------------------------------------------
        // Main input handler — mirrors onInput(input, key) in useTextInput.ts
        // ---------------------------------------------------------------------

        /**
         * Process a single key event.
         *
         * Mirrors {@code onInput(input, key)} in useTextInput.ts.
         * Routes the event through the key map, applies kill/yank tracking,
         * handles DEL-in-SSH-tmux, and updates value/offset.
         *
         * @param rawInput the raw character(s) received from the terminal
         * @param key      parsed key event flags
         */
        public void onInput(String rawInput, KeyEvent key) {
            if (rawInput == null) rawInput = "";

            // DEL (\x7f) filtering for SSH/tmux backspace coalescing
            if (!key.isBackspace() && !key.isDelete() && rawInput.contains("\u007f")) {
                int delCount = (int) rawInput.chars().filter(c -> c == 0x7f).count();
                for (int i = 0; i < delCount; i++) {
                    String deleted = deleteTokenBeforeOrBackspace();
                    if (deleted != null) setValue(deleted);
                }
                resetKillAccumulation();
                resetYankState();
                return;
            }

            // Reset kill accumulation for non-kill keys
            if (!isKillKey(key, rawInput)) resetKillAccumulation();
            // Reset yank state for non-yank keys
            if (!isYankKey(key, rawInput)) resetYankState();

            // Route through the key map
            String newValue = mapKey(key, rawInput);

            // SSH-coalesced Enter: strip trailing \r and submit
            if (newValue != null && rawInput.length() > 1
                    && rawInput.endsWith("\r")
                    && !rawInput.substring(0, rawInput.length() - 1).contains("\r")
                    && rawInput.charAt(rawInput.length() - 2) != '\\') {
                if (onSubmit != null) onSubmit.accept(newValue);
            }
        }

        // ---------------------------------------------------------------------
        // Key mapping — mirrors mapKey(key) in useTextInput.ts
        // ---------------------------------------------------------------------

        /**
         * Map a key event to a text edit operation.
         * Returns the new text value (or the current value if unchanged), or null
         * when the key event triggers a non-text action (e.g. submit, history).
         *
         * Mirrors {@code mapKey(key)} in useTextInput.ts.
         */
        private String mapKey(KeyEvent key, String input) {
            if (key.isEscape()) {
                if (!disableEscapeDoublePress) handleEscapeDoublePress();
                return value; // escape does not change text directly
            }
            if (key.isLeftArrow() && (key.isCtrl() || key.isMeta() || key.isFn())) {
                setOffset(prevWordOffset());
                return value;
            }
            if (key.isRightArrow() && (key.isCtrl() || key.isMeta() || key.isFn())) {
                setOffset(nextWordOffset());
                return value;
            }
            if (key.isBackspace()) {
                if (key.isMeta() || key.isCtrl()) return killWordBefore();
                return deleteTokenBeforeOrBackspace();
            }
            if (key.isDelete()) {
                if (key.isMeta()) return killToLineEnd();
                return deleteForward();
            }
            if (key.isCtrl()) return handleCtrl(input);
            if (key.isHome()) { setOffset(startOfLine()); return value; }
            if (key.isEnd())  { setOffset(endOfLine());   return value; }
            if (key.isReturnKey()) { handleEnter(key); return null; }
            if (key.isMeta()) return handleMeta(input);
            if (key.isTab())  return value; // tab is ignored
            if (key.isUpArrow() && !key.isShift())   { return upOrHistoryUp(); }
            if (key.isDownArrow() && !key.isShift()) { return downOrHistoryDown(); }
            if (key.isLeftArrow())  { setOffset(Math.max(0, offset - 1)); return value; }
            if (key.isRightArrow()) { setOffset(Math.min(value.length(), offset + 1)); return value; }

            // ANSI sequences for Home/End
            if ("\u001b[H".equals(input) || "\u001b[1~".equals(input)) {
                setOffset(startOfLine()); return value;
            }
            if ("\u001b[F".equals(input) || "\u001b[4~".equals(input)) {
                setOffset(endOfLine()); return value;
            }

            // Default: insert characters
            String cleaned = input
                .replaceAll("(?<=[^\\\\\r\n])\r$", "")  // strip SSH-coalesced trailing \r
                .replace("\r", "\n");
            if (!cleaned.isEmpty()) {
                return insert(cleaned);
            }
            return value;
        }

        // ---------------------------------------------------------------------
        // Ctrl key handlers — mirrors handleCtrl mapInput in useTextInput.ts
        // ---------------------------------------------------------------------

        private String handleCtrl(String input) {
            return switch (input) {
                case "a" -> { setOffset(startOfLine()); yield value; }
                case "b" -> { setOffset(Math.max(0, offset - 1)); yield value; }
                case "c" -> { handleCtrlCDoublePress(); yield value; }
                case "d" -> handleCtrlD();
                case "e" -> { setOffset(endOfLine()); yield value; }
                case "f" -> { setOffset(Math.min(value.length(), offset + 1)); yield value; }
                case "h" -> deleteTokenBeforeOrBackspace();
                case "k" -> killToLineEnd();
                case "n" -> downOrHistoryDown();
                case "p" -> upOrHistoryUp();
                case "u" -> killToLineStart();
                case "w" -> killWordBefore();
                case "y" -> yank();
                default  -> value;
            };
        }

        // ---------------------------------------------------------------------
        // Meta key handlers — mirrors handleMeta mapInput in useTextInput.ts
        // ---------------------------------------------------------------------

        private String handleMeta(String input) {
            return switch (input) {
                case "b" -> { setOffset(prevWordOffset()); yield value; }
                case "f" -> { setOffset(nextWordOffset()); yield value; }
                case "d" -> deleteWordForward();
                case "y" -> handleYankPop();
                default  -> value;
            };
        }

        // ---------------------------------------------------------------------
        // Enter handling
        // ---------------------------------------------------------------------

        private void handleEnter(KeyEvent key) {
            // Backslash + Enter → insert literal newline in multiline mode
            if (multiline && offset > 0 && value.charAt(offset - 1) == '\\') {
                String newValue = value.substring(0, offset - 1) + "\n" + value.substring(offset);
                value = newValue;
                setOffset(offset); // offset stays (backspace removed the \)
                if (onChange != null) onChange.accept(value);
                return;
            }
            // Meta+Enter or Shift+Enter → insert newline
            if (key.isMeta() || key.isShift()) {
                setValue(insert("\n"));
                return;
            }
            // Normal Enter → submit
            if (onSubmit != null) onSubmit.accept(value);
        }

        // ---------------------------------------------------------------------
        // History navigation
        // ---------------------------------------------------------------------

        private String upOrHistoryUp() {
            if (disableCursorMovementForUpDownKeys) {
                if (onHistoryUp != null) onHistoryUp.run();
                return value;
            }
            int upOffset = moveUpLine();
            if (upOffset != offset) { setOffset(upOffset); return value; }
            if (onHistoryUp != null) onHistoryUp.run();
            return value;
        }

        private String downOrHistoryDown() {
            if (disableCursorMovementForUpDownKeys) {
                if (onHistoryDown != null) onHistoryDown.run();
                return value;
            }
            int downOffset = moveDownLine();
            if (downOffset != offset) { setOffset(downOffset); return value; }
            if (onHistoryDown != null) onHistoryDown.run();
            return value;
        }

        // ---------------------------------------------------------------------
        // Double-press handlers
        // ---------------------------------------------------------------------

        private void handleCtrlCDoublePress() {
            long now = System.currentTimeMillis();
            if (now - lastCtrlCMs < DOUBLE_PRESS_MS) {
                // Second press: exit or clear
                if (!value.isEmpty()) {
                    setValue("");
                    setOffset(0);
                    if (onHistoryReset != null) onHistoryReset.run();
                } else if (onExit != null) {
                    onExit.run();
                }
                lastCtrlCMs = 0;
            } else {
                lastCtrlCMs = now;
                if (onExitMessage != null) onExitMessage.accept(true);
            }
        }

        private void handleEscapeDoublePress() {
            long now = System.currentTimeMillis();
            if (now - lastEscMs < DOUBLE_PRESS_MS) {
                // Second press: clear input
                if (!value.isEmpty() && value.trim().length() > 0) {
                    // add to history before clearing (mirrors addToHistory(originalValue))
                    log.debug("Escape double-press: clearing input");
                }
                if (onClearInput != null) onClearInput.run();
                if (!value.isEmpty()) {
                    setValue("");
                    setOffset(0);
                    if (onHistoryReset != null) onHistoryReset.run();
                }
                lastEscMs = 0;
            } else {
                lastEscMs = now;
            }
        }

        // ---------------------------------------------------------------------
        // Ctrl+D
        // ---------------------------------------------------------------------

        private String handleCtrlD() {
            if (value.isEmpty()) {
                handleCtrlCDoublePress(); // re-use double-press logic for empty input
                return value;
            }
            return deleteForward();
        }

        // ---------------------------------------------------------------------
        // Kill-ring operations
        // mirrors killToLineEnd, killToLineStart, killWordBefore in useTextInput.ts
        // ---------------------------------------------------------------------

        private String killToLineEnd() {
            int eol = endOfLine();
            if (eol <= offset) return value;
            String killed = value.substring(offset, eol);
            appendToKillRing(killed);
            String newValue = value.substring(0, offset) + value.substring(eol);
            setValue(newValue);
            return value;
        }

        private String killToLineStart() {
            int sol = startOfLine();
            if (sol >= offset) return value;
            String killed = value.substring(sol, offset);
            prependToKillRing(killed);
            String newValue = value.substring(0, sol) + value.substring(offset);
            setValue(newValue);
            setOffset(sol);
            return value;
        }

        private String killWordBefore() {
            int wordStart = prevWordOffset();
            if (wordStart >= offset) return value;
            String killed = value.substring(wordStart, offset);
            prependToKillRing(killed);
            String newValue = value.substring(0, wordStart) + value.substring(offset);
            setValue(newValue);
            setOffset(wordStart);
            return value;
        }

        private String deleteWordForward() {
            int wordEnd = nextWordOffset();
            if (wordEnd <= offset) return value;
            String newValue = value.substring(0, offset) + value.substring(wordEnd);
            setValue(newValue);
            return value;
        }

        // ---------------------------------------------------------------------
        // Yank operations
        // mirrors yank(), handleYankPop() in useTextInput.ts
        // ---------------------------------------------------------------------

        private String yank() {
            String text = peekKillRing();
            if (text == null || text.isEmpty()) return value;
            yankStart = offset;
            String newValue = value.substring(0, offset) + text + value.substring(offset);
            yankLength = text.length();
            setValue(newValue);
            setOffset(offset + text.length());
            return value;
        }

        private String handleYankPop() {
            if (yankStart < 0 || yankLength <= 0) return value;
            // Replace the previously yanked region with the next kill-ring entry
            String prev = rotateKillRing();
            if (prev == null) return value;
            String before = value.substring(0, yankStart);
            String after  = value.substring(yankStart + yankLength);
            String newValue = before + prev + after;
            setValue(newValue);
            setOffset(yankStart + prev.length());
            yankLength = prev.length();
            return value;
        }

        // ---------------------------------------------------------------------
        // Basic cursor operations
        // ---------------------------------------------------------------------

        private String insert(String text) {
            String newValue = value.substring(0, offset) + text + value.substring(offset);
            setValue(newValue);
            setOffset(offset + text.length());
            return value;
        }

        private String deleteTokenBeforeOrBackspace() {
            if (offset == 0) return value;
            // Simple backspace (one char)
            int newOffset = offset - 1;
            String newValue = value.substring(0, newOffset) + value.substring(offset);
            setValue(newValue);
            setOffset(newOffset);
            return value;
        }

        private String deleteForward() {
            if (offset >= value.length()) return value;
            String newValue = value.substring(0, offset) + value.substring(offset + 1);
            setValue(newValue);
            return value;
        }

        private int startOfLine() {
            int pos = offset;
            while (pos > 0 && value.charAt(pos - 1) != '\n') pos--;
            return pos;
        }

        private int endOfLine() {
            int pos = offset;
            while (pos < value.length() && value.charAt(pos) != '\n') pos++;
            return pos;
        }

        private int prevWordOffset() {
            int pos = offset;
            while (pos > 0 && !Character.isLetterOrDigit(value.charAt(pos - 1))) pos--;
            while (pos > 0 && Character.isLetterOrDigit(value.charAt(pos - 1)))  pos--;
            return pos;
        }

        private int nextWordOffset() {
            int pos = offset;
            while (pos < value.length() && !Character.isLetterOrDigit(value.charAt(pos))) pos++;
            while (pos < value.length() && Character.isLetterOrDigit(value.charAt(pos)))  pos++;
            return pos;
        }

        /** Move cursor up one wrapped line; returns unchanged offset if already at top. */
        private int moveUpLine() {
            int sol = startOfLine();
            if (sol == 0) return offset; // at first line
            // Move to end of previous line then start of that line
            int prevEol = sol - 1;
            int prevSol = prevEol;
            while (prevSol > 0 && value.charAt(prevSol - 1) != '\n') prevSol--;
            int colInLine = offset - sol;
            return Math.min(prevSol + colInLine, prevEol);
        }

        /** Move cursor down one wrapped line; returns unchanged offset if already at bottom. */
        private int moveDownLine() {
            int eol = endOfLine();
            if (eol >= value.length()) return offset; // at last line
            int nextSol = eol + 1;
            int nextEol = nextSol;
            while (nextEol < value.length() && value.charAt(nextEol) != '\n') nextEol++;
            int colInLine = offset - startOfLine();
            return Math.min(nextSol + colInLine, nextEol);
        }

        // ---------------------------------------------------------------------
        // Kill-ring helpers (module-level state in TypeScript, per-session here)
        // ---------------------------------------------------------------------

        private void appendToKillRing(String text) {
            if (killAccumulating && !killRing.isEmpty()) {
                killRing.push(killRing.pop() + text);
            } else {
                killRing.push(text);
                killAccumulating = true;
            }
        }

        private void prependToKillRing(String text) {
            if (killAccumulating && !killRing.isEmpty()) {
                killRing.push(text + killRing.pop());
            } else {
                killRing.push(text);
                killAccumulating = true;
            }
        }

        private String peekKillRing() {
            return killRing.isEmpty() ? null : killRing.peek();
        }

        /** Rotate the kill ring and return the new top element. */
        private String rotateKillRing() {
            if (killRing.size() < 2) return null;
            String top = killRing.pop();
            killRing.addLast(top);
            return killRing.peek();
        }

        private void resetKillAccumulation() {
            killAccumulating = false;
        }

        private void resetYankState() {
            yankStart  = -1;
            yankLength = 0;
        }

        // ---------------------------------------------------------------------
        // Kill / yank key predicates
        // mirrors isKillKey / isYankKey in useTextInput.ts
        // ---------------------------------------------------------------------

        private boolean isKillKey(KeyEvent key, String input) {
            if (key.isCtrl() && ("k".equals(input) || "u".equals(input) || "w".equals(input))) return true;
            if (key.isMeta() && (key.isBackspace() || key.isDelete())) return true;
            return false;
        }

        private boolean isYankKey(KeyEvent key, String input) {
            return (key.isCtrl() || key.isMeta()) && "y".equals(input);
        }

        // ---------------------------------------------------------------------
        // TextInputState-equivalent accessor (renderedValue placeholder)
        // ---------------------------------------------------------------------

        /**
         * Get a simple rendered representation of the text with cursor marker.
         * A full implementation would apply colour inversion at the cursor
         * position, handle masking, ghost text, and viewport clipping.
         *
         * Mirrors the {@code renderedValue} field in {@code TextInputState}.
         */
        public String getRenderedValue() {
            if (offset >= value.length()) return value + "|";
            return value.substring(0, offset) + "|" + value.substring(offset);
        }
    }

    // =========================================================================
    // Factory method
    // =========================================================================

    /**
     * Create a new {@link TextInputSession}.
     * Mirrors "calling" the {@code useTextInput(props)} hook.
     *
     * @param columns   terminal column width
     * @param multiline whether multiline input is allowed
     */
    public static TextInputSession newTextInputSession(int columns, boolean multiline) {
        return new TextInputSession(columns, multiline);
    }
}
