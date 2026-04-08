package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * SDK event queue — buffers system events for headless / streaming SDK consumers.
 * Translated from src/utils/sdkEventQueue.ts
 *
 * <p>Events are only queued when the session is non-interactive (headless/streaming).
 * In TUI mode they are silently dropped. The queue is bounded by
 * {@value #MAX_QUEUE_SIZE}; oldest events are evicted when the cap is reached.</p>
 */
@Slf4j
@Service
public class SdkEventQueueService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SdkEventQueueService.class);


    private static final int MAX_QUEUE_SIZE = 1000;

    // -------------------------------------------------------------------------
    // Typed event records (mirror the TS union SdkEvent)
    // -------------------------------------------------------------------------

    /**
     * Sealed base interface for all SDK events.
     * Translated from {@code SdkEvent} in sdkEventQueue.ts.
     */
    public sealed interface SdkEvent permits
            SdkEventQueueService.TaskStartedEvent,
            SdkEventQueueService.TaskProgressEvent,
            SdkEventQueueService.TaskNotificationEvent,
            SdkEventQueueService.SessionStateChangedEvent,
            SdkEventQueueService.RawMapEvent {}

    /**
     * Usage statistics snapshot attached to task events.
     */
    public record UsageStats(int totalTokens, int toolUses, long durationMs) {}

    /** Raw map event that allows passing arbitrary event data. */
    public record RawMapEvent(java.util.Map<String, Object> data) implements SdkEvent {}

    /**
     * Emitted when a new task/agent is started.
     * Translated from {@code TaskStartedEvent} in sdkEventQueue.ts.
     */
    public record TaskStartedEvent(
            String taskId,
            String toolUseId,
            String description,
            String taskType,
            String workflowName,
            String prompt) implements SdkEvent {}

    /**
     * Emitted periodically while a task is running.
     * Translated from {@code TaskProgressEvent} in sdkEventQueue.ts.
     */
    public record TaskProgressEvent(
            String taskId,
            String toolUseId,
            String description,
            UsageStats usage,
            String lastToolName,
            String summary) implements SdkEvent {}

    /**
     * Emitted when a foreground agent reaches a terminal state.
     * Translated from {@code TaskNotificationSdkEvent} in sdkEventQueue.ts.
     */
    public record TaskNotificationEvent(
            String taskId,
            String toolUseId,
            String status,          // "completed" | "failed" | "stopped"
            String outputFile,
            String summary,
            UsageStats usage) implements SdkEvent {}

    /**
     * Emitted when the main session transitions between idle / running / requires_action.
     * Translated from {@code SessionStateChangedEvent} in sdkEventQueue.ts.
     */
    public record SessionStateChangedEvent(
            String state) implements SdkEvent {}  // "idle" | "running" | "requires_action"

    /**
     * An SDK event enriched with a UUID and session ID, returned by {@link #drainSdkEvents()}.
     */
    public record DrainedEvent(SdkEvent event, String uuid, String sessionId) {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Bounded FIFO queue of pending SDK events. */
    private final Deque<SdkEvent> queue = new ArrayDeque<>(MAX_QUEUE_SIZE);

    /** Optional listeners for real-time event delivery (publish-subscribe). */
    private final List<Consumer<SdkEvent>> listeners = new CopyOnWriteArrayList<>();

    private final SessionStateService sessionStateService;

    @Autowired
    public SdkEventQueueService(SessionStateService sessionStateService) {
        this.sessionStateService = sessionStateService;
    }

    // -------------------------------------------------------------------------
    // enqueueSdkEvent
    // -------------------------------------------------------------------------

    /** Overload that accepts a raw map event. */
    public synchronized void enqueueSdkEvent(java.util.Map<String, Object> eventMap) {
        enqueueSdkEvent(new RawMapEvent(eventMap));
    }

    /**
     * Adds an event to the queue if the current session is non-interactive.
     * Translated from {@code enqueueSdkEvent()} in sdkEventQueue.ts.
     */
    public synchronized void enqueueSdkEvent(SdkEvent event) {
        // Only queue events in headless / non-interactive mode
        if (!sessionStateService.isNonInteractiveSession()) return;

        if (queue.size() >= MAX_QUEUE_SIZE) {
            queue.pollFirst(); // evict oldest (mirrors queue.shift() in TS)
        }
        queue.addLast(event);
        notifyListeners(event);
    }

    // -------------------------------------------------------------------------
    // drainSdkEvents
    // -------------------------------------------------------------------------

    /**
     * Atomically removes all queued events and returns them enriched with UUIDs
     * and the current session ID.
     * Translated from {@code drainSdkEvents()} in sdkEventQueue.ts.
     */
    public synchronized List<DrainedEvent> drainSdkEvents() {
        if (queue.isEmpty()) return List.of();

        List<SdkEvent> snapshot = new ArrayList<>(queue);
        queue.clear();

        String sessionId = sessionStateService.getSessionId();
        return snapshot.stream()
                .map(e -> new DrainedEvent(e, UUID.randomUUID().toString(), sessionId))
                .toList();
    }

    // -------------------------------------------------------------------------
    // emitTaskTerminatedSdk
    // -------------------------------------------------------------------------

    /**
     * Convenience helper — enqueues a {@link TaskNotificationEvent} for a task
     * that has reached a terminal state.
     * Translated from {@code emitTaskTerminatedSdk()} in sdkEventQueue.ts.
     *
     * @param taskId    the task that terminated
     * @param status    {@code "completed"}, {@code "failed"}, or {@code "stopped"}
     * @param toolUseId optional tool-use ID associated with this task
     * @param summary   optional human-readable summary
     * @param outputFile optional path to the task's output file
     * @param usage     optional resource-usage snapshot
     */
    public void emitTaskTerminatedSdk(
            String taskId,
            String status,
            String toolUseId,
            String summary,
            String outputFile,
            UsageStats usage) {
        enqueueSdkEvent(new TaskNotificationEvent(
                taskId,
                toolUseId,
                status,
                outputFile != null ? outputFile : "",
                summary    != null ? summary    : "",
                usage));
    }

    // -------------------------------------------------------------------------
    // Convenience event enqueue methods
    // -------------------------------------------------------------------------

    /**
     * Enqueues a {@link TaskStartedEvent}.
     */
    public void enqueueTaskStarted(String taskId, String description,
                                   String toolUseId, String taskType,
                                   String workflowName, String prompt) {
        enqueueSdkEvent(new TaskStartedEvent(
                taskId, toolUseId, description, taskType, workflowName, prompt));
    }

    /**
     * Enqueues a {@link SessionStateChangedEvent}.
     */
    public void enqueueSessionStateChanged(String state) {
        enqueueSdkEvent(new SessionStateChangedEvent(state));
    }

    /**
     * Enqueues a task notification event (convenience overload for LocalMainSessionTaskService).
     */
    public void enqueueTaskNotification(String taskId, String status, String summary) {
        enqueueSdkEvent(new TaskNotificationEvent(taskId, null, status, null, summary, null));
    }

    // -------------------------------------------------------------------------
    // Listener registration
    // -------------------------------------------------------------------------

    /**
     * Registers a listener for real-time event delivery.
     *
     * @return a {@link Runnable} that unregisters the listener when called
     */
    public Runnable subscribe(Consumer<SdkEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void notifyListeners(SdkEvent event) {
        for (Consumer<SdkEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.debug("SDK event listener error: {}", e.getMessage());
            }
        }
    }
}
