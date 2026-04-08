package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Message queue service for managing user input and command queuing.
 * Translated from src/utils/messageQueueManager.ts
 *
 * Provides a priority-based queue of queued commands. Priority order:
 * NOW (0) > NEXT (1) > LATER (2). Within the same priority commands are
 * processed FIFO. Subscribers are notified on every mutation.
 */
@Slf4j
@Service
public class MessageQueueService {



    // =========================================================================
    // Priority
    // =========================================================================

    /**
     * Command dispatch priority.
     * Translated from QueuePriority in textInputTypes.ts
     */
    public enum QueuePriority {
        NOW(0), NEXT(1), LATER(2);

        private final int order;

        QueuePriority(int order) { this.order = order; }

        public int getOrder() { return order; }
    }

    // =========================================================================
    // QueuedCommand
    // =========================================================================

    /**
     * A command waiting in the queue.
     * Translated from QueuedCommand in textInputTypes.ts
     */
    @Data
    public static class QueuedCommand {
        private String id;
        /** Text value or a reference to content blocks. */
        private String value;
        private String mode;
        private QueuePriority priority;
        /** Agent ID — null means main thread. */
        private String agentId;
        /** True for system-generated (non-editable) commands. */
        private boolean isMeta;
        /** True when slash-command processing should be skipped. */
        private boolean skipSlashCommands;
        private Map<String, Object> pastedContents;

        public QueuedCommand(String id, String value, String mode, QueuePriority priority) {
            this(id, value, mode, priority, null, false, false, null);
        }

        public QueuedCommand(String id, String value, String mode, QueuePriority priority,
                             String agentId, boolean isMeta, boolean skipSlashCommands,
                             Map<String, Object> pastedContents) {
            this.id = id;
            this.value = value;
            this.mode = mode;
            this.priority = priority != null ? priority : QueuePriority.NEXT;
            this.agentId = agentId;
            this.isMeta = isMeta;
            this.skipSlashCommands = skipSlashCommands;
            this.pastedContents = pastedContents;
        }

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getValue() { return value; }
        public void setValue(String v) { value = v; }
        public String getMode() { return mode; }
        public void setMode(String v) { mode = v; }
        public QueuePriority getPriority() { return priority; }
        public void setPriority(QueuePriority v) { priority = v; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public boolean isIsMeta() { return isMeta; }
        public boolean isMeta() { return isMeta; }
        public void setIsMeta(boolean v) { isMeta = v; }
        public boolean isSkipSlashCommands() { return skipSlashCommands; }
        public void setSkipSlashCommands(boolean v) { skipSlashCommands = v; }
        public Map<String, Object> getPastedContents() { return pastedContents; }
        public void setPastedContents(Map<String, Object> v) { pastedContents = v; }
    }

    // =========================================================================
    // State
    // =========================================================================

    private final List<QueuedCommand> commandQueue = new ArrayList<>();
    private volatile List<QueuedCommand> snapshot = Collections.unmodifiableList(new ArrayList<>());
    private final List<Runnable> subscribers = new CopyOnWriteArrayList<>();

    // =========================================================================
    // useSyncExternalStore interface
    // =========================================================================

    /**
     * Subscribe to command queue changes.
     * Returns a Runnable that unsubscribes when called.
     * Translated from subscribeToCommandQueue in messageQueueManager.ts
     */
    public Runnable subscribeToCommandQueue(Runnable listener) {
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }

    /**
     * Get current snapshot of the command queue (immutable view).
     * Translated from getCommandQueueSnapshot() in messageQueueManager.ts
     */
    public List<QueuedCommand> getCommandQueueSnapshot() {
        return snapshot;
    }

    // =========================================================================
    // Read operations
    // =========================================================================

    /** Get a mutable copy of the current queue. */
    public synchronized List<QueuedCommand> getCommandQueue() {
        return new ArrayList<>(commandQueue);
    }

    /** Get the current queue length without copying. */
    public synchronized int getCommandQueueLength() {
        return commandQueue.size();
    }

    /** Check if there are commands in the queue. */
    public synchronized boolean hasCommandsInQueue() {
        return !commandQueue.isEmpty();
    }

    /**
     * Trigger a re-check by notifying subscribers.
     * Translated from recheckCommandQueue() in messageQueueManager.ts
     */
    public synchronized void recheckCommandQueue() {
        if (!commandQueue.isEmpty()) {
            notifySubscribers();
        }
    }

    // =========================================================================
    // Write operations
    // =========================================================================

    /**
     * Add a command to the queue (default priority: NEXT).
     * Translated from enqueue() in messageQueueManager.ts
     */
    public synchronized void enqueue(QueuedCommand command) {
        QueuedCommand cmd = command.getPriority() != null
                ? command
                : new QueuedCommand(command.getId(), command.getValue(), command.getMode(),
                                    QueuePriority.NEXT, command.getAgentId(), command.isMeta(),
                                    command.isSkipSlashCommands(), command.getPastedContents());
        commandQueue.add(cmd);
        notifySubscribers();
        logOperation("enqueue", cmd.getValue());
    }

    /**
     * Convenience overload used by UserInputProcessorService.
     * Builds a QueuedCommand from the raw fields and delegates to enqueue(QueuedCommand).
     */
    @SuppressWarnings("unchecked")
    public synchronized void enqueue(String value, String rawValue, String mode,
                                     Object pastedContents, boolean skipSlashCommands, String id) {
        String cmdId = id != null ? id : java.util.UUID.randomUUID().toString();
        java.util.Map<String, Object> pc = null;
        if (pastedContents instanceof java.util.Map<?, ?>) {
            pc = (java.util.Map<String, Object>) pastedContents;
        }
        QueuedCommand cmd = new QueuedCommand(cmdId, value, mode, QueuePriority.NEXT,
                null, false, skipSlashCommands, pc);
        enqueue(cmd);
    }

    /**
     * Add a task notification to the queue (default priority: LATER).
     * Translated from enqueuePendingNotification() in messageQueueManager.ts
     */
    public synchronized void enqueuePendingNotification(QueuedCommand command) {
        QueuedCommand cmd = new QueuedCommand(
                command.getId(), command.getValue(), command.getMode(),
                command.getPriority() != null ? command.getPriority() : QueuePriority.LATER,
                command.getAgentId(), command.isMeta(),
                command.isSkipSlashCommands(), command.getPastedContents());
        commandQueue.add(cmd);
        notifySubscribers();
        logOperation("enqueue", cmd.getValue());
    }

    /**
     * Remove and return the highest-priority command, or empty if the queue is empty.
     * An optional filter narrows the candidates.
     * Translated from dequeue() in messageQueueManager.ts
     */
    public synchronized Optional<QueuedCommand> dequeue(Predicate<QueuedCommand> filter) {
        if (commandQueue.isEmpty()) return Optional.empty();

        int bestIdx = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int i = 0; i < commandQueue.size(); i++) {
            QueuedCommand cmd = commandQueue.get(i);
            if (filter != null && !filter.test(cmd)) continue;
            int priority = cmd.getPriority().getOrder();
            if (priority < bestPriority) {
                bestIdx = i;
                bestPriority = priority;
            }
        }

        if (bestIdx == -1) return Optional.empty();

        QueuedCommand dequeued = commandQueue.remove(bestIdx);
        notifySubscribers();
        logOperation("dequeue", null);
        return Optional.of(dequeued);
    }

    /** Dequeue without a filter. */
    public Optional<QueuedCommand> dequeue() {
        return dequeue(null);
    }

    /**
     * Remove and return all commands from the queue.
     * Translated from dequeueAll() in messageQueueManager.ts
     */
    public synchronized List<QueuedCommand> dequeueAll() {
        if (commandQueue.isEmpty()) return List.of();
        List<QueuedCommand> commands = new ArrayList<>(commandQueue);
        commandQueue.clear();
        notifySubscribers();
        commands.forEach(c -> logOperation("dequeue", null));
        return commands;
    }

    /**
     * Peek at the highest-priority command without removing it.
     * Translated from peek() in messageQueueManager.ts
     */
    public synchronized Optional<QueuedCommand> peek(Predicate<QueuedCommand> filter) {
        if (commandQueue.isEmpty()) return Optional.empty();
        int bestIdx = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int i = 0; i < commandQueue.size(); i++) {
            QueuedCommand cmd = commandQueue.get(i);
            if (filter != null && !filter.test(cmd)) continue;
            int priority = cmd.getPriority().getOrder();
            if (priority < bestPriority) {
                bestIdx = i;
                bestPriority = priority;
            }
        }
        if (bestIdx == -1) return Optional.empty();
        return Optional.of(commandQueue.get(bestIdx));
    }

    /** Peek without a filter. */
    public Optional<QueuedCommand> peek() {
        return peek(null);
    }

    /**
     * Remove and return all commands matching a predicate, preserving priority order.
     * Translated from dequeueAllMatching() in messageQueueManager.ts
     */
    public synchronized List<QueuedCommand> dequeueAllMatching(Predicate<QueuedCommand> predicate) {
        List<QueuedCommand> matched = new ArrayList<>();
        List<QueuedCommand> remaining = new ArrayList<>();
        for (QueuedCommand cmd : commandQueue) {
            if (predicate.test(cmd)) matched.add(cmd);
            else remaining.add(cmd);
        }
        if (matched.isEmpty()) return List.of();
        commandQueue.clear();
        commandQueue.addAll(remaining);
        notifySubscribers();
        matched.forEach(c -> logOperation("dequeue", null));
        return matched;
    }

    /**
     * Remove specific commands from the queue by reference identity.
     * Translated from remove() in messageQueueManager.ts
     */
    public synchronized void remove(List<QueuedCommand> commandsToRemove) {
        if (commandsToRemove.isEmpty()) return;
        int before = commandQueue.size();
        commandQueue.removeIf(commandsToRemove::contains);
        if (commandQueue.size() != before) notifySubscribers();
        commandsToRemove.forEach(c -> logOperation("remove", null));
    }

    /**
     * Remove commands matching a predicate and return them.
     * Translated from removeByFilter() in messageQueueManager.ts
     */
    public synchronized List<QueuedCommand> removeByFilter(Predicate<QueuedCommand> predicate) {
        List<QueuedCommand> removed = new ArrayList<>();
        for (int i = commandQueue.size() - 1; i >= 0; i--) {
            if (predicate.test(commandQueue.get(i))) {
                removed.add(0, commandQueue.remove(i));
            }
        }
        if (!removed.isEmpty()) {
            notifySubscribers();
            removed.forEach(c -> logOperation("remove", null));
        }
        return removed;
    }

    /**
     * Clear all commands from the queue.
     * Translated from clearCommandQueue() in messageQueueManager.ts
     */
    public synchronized void clearCommandQueue() {
        if (commandQueue.isEmpty()) return;
        commandQueue.clear();
        notifySubscribers();
    }

    /**
     * Clear all commands and reset snapshot (for test cleanup).
     * Translated from resetCommandQueue() in messageQueueManager.ts
     */
    public synchronized void resetCommandQueue() {
        commandQueue.clear();
        snapshot = Collections.unmodifiableList(new ArrayList<>());
    }

    // =========================================================================
    // Priority helpers
    // =========================================================================

    /**
     * Return all commands at or above a given priority without removing them.
     * Translated from getCommandsByMaxPriority() in messageQueueManager.ts
     */
    public synchronized List<QueuedCommand> getCommandsByMaxPriority(QueuePriority maxPriority) {
        int threshold = maxPriority.getOrder();
        return commandQueue.stream()
                .filter(cmd -> cmd.getPriority().getOrder() <= threshold)
                .toList();
    }

    // =========================================================================
    // Editable-mode helpers
    // =========================================================================

    private static final Set<String> NON_EDITABLE_MODES = Set.of("task-notification");

    /**
     * Whether a prompt input mode is editable.
     * Translated from isPromptInputModeEditable() in messageQueueManager.ts
     */
    public static boolean isPromptInputModeEditable(String mode) {
        return !NON_EDITABLE_MODES.contains(mode);
    }

    /**
     * Whether a queued command can be pulled into the input buffer via UP/ESC.
     * Translated from isQueuedCommandEditable() in messageQueueManager.ts
     */
    public static boolean isQueuedCommandEditable(QueuedCommand cmd) {
        return isPromptInputModeEditable(cmd.getMode()) && !cmd.isMeta();
    }

    /**
     * Whether a queued command should render in the queue preview under the prompt.
     * Translated from isQueuedCommandVisible() in messageQueueManager.ts
     */
    public static boolean isQueuedCommandVisible(QueuedCommand cmd) {
        return isQueuedCommandEditable(cmd);
    }

    /**
     * Returns true if the command is a slash command.
     * Translated from isSlashCommand() in messageQueueManager.ts
     */
    public static boolean isSlashCommand(QueuedCommand cmd) {
        return cmd.getValue() != null
                && cmd.getValue().trim().startsWith("/")
                && !cmd.isSkipSlashCommands();
    }

    // =========================================================================
    // Deprecated aliases
    // =========================================================================

    /** @deprecated Use subscribeToCommandQueue */
    @Deprecated
    public Runnable subscribeToPendingNotifications(Runnable listener) {
        return subscribeToCommandQueue(listener);
    }

    /** @deprecated Use getCommandQueueSnapshot */
    @Deprecated
    public List<QueuedCommand> getPendingNotificationsSnapshot() {
        return getCommandQueueSnapshot();
    }

    /** @deprecated Use hasCommandsInQueue */
    @Deprecated
    public boolean hasPendingNotifications() {
        return hasCommandsInQueue();
    }

    /** @deprecated Use getCommandQueueLength */
    @Deprecated
    public int getPendingNotificationsCount() {
        return getCommandQueueLength();
    }

    /** @deprecated Use recheckCommandQueue */
    @Deprecated
    public void recheckPendingNotifications() {
        recheckCommandQueue();
    }

    /** @deprecated Use dequeue */
    @Deprecated
    public Optional<QueuedCommand> dequeuePendingNotification() {
        return dequeue();
    }

    /** @deprecated Use resetCommandQueue */
    @Deprecated
    public void resetPendingNotifications() {
        resetCommandQueue();
    }

    /** @deprecated Use clearCommandQueue */
    @Deprecated
    public void clearPendingNotifications() {
        clearCommandQueue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void notifySubscribers() {
        snapshot = Collections.unmodifiableList(new ArrayList<>(commandQueue));
        subscribers.forEach(Runnable::run);
    }

    private void logOperation(String operation, String content) {
        if (log.isDebugEnabled()) {
            log.debug("queue-operation: {} {}", operation, content != null ? content : "");
        }
    }
}
