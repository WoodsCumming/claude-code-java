package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// SerialBatchEventUploader translated from src/cli/transports/SerialBatchEventUploader.ts

/**
 * Analytics event logging service — public API for event logging.
 * Translated from src/services/analytics/index.ts
 * Event types added from src/utils/telemetry/events.ts
 *
 * DESIGN: Events are queued until attachAnalyticsSink() is called during app
 * initialization. The sink handles routing to Datadog and 1P event logging.
 *
 * Metadata values intentionally restricted to Boolean/Number to avoid
 * accidentally logging code snippets or file paths.
 */
@Slf4j
@Service
public class AnalyticsService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalyticsService.class);

    // =========================================================================
    // Telemetry event constants
    // Translated from src/utils/telemetry/events.ts — event names used in the
    // codebase are collected here so callers can reference them as constants.
    // =========================================================================

    /** Fired at session start when OTEL event logging is initializing. */
    public static final String EVENT_ANALYTICS_SINK_ATTACHED = "analytics_sink_attached";

    // OTel event helpers (src/utils/telemetry/events.ts)
    public static final String OTEL_EVENT_PREFIX = "claude_code.";

    // Attribute keys used by logOTelEvent
    public static final String ATTR_EVENT_NAME      = "event.name";
    public static final String ATTR_EVENT_TIMESTAMP = "event.timestamp";
    public static final String ATTR_EVENT_SEQUENCE  = "event.sequence";
    public static final String ATTR_PROMPT_ID       = "prompt.id";
    public static final String ATTR_WORKSPACE_HOST_PATHS = "workspace.host_paths";

    // =========================================================================
    // Metadata helpers
    // =========================================================================

    /**
     * Metadata type for log events: only boolean or numeric values allowed.
     * Mirrors TypeScript: { [key: string]: boolean | number | undefined }
     */
    public static final class LogEventMetadata extends LinkedHashMap<String, Object> {
        /** Only put Boolean or Number values. */
        public LogEventMetadata with(String key, Number value) {
            put(key, value);
            return this;
        }
        public LogEventMetadata with(String key, Boolean value) {
            put(key, value);
            return this;
        }
    }

    // =========================================================================
    // Sink interface
    // =========================================================================

    /**
     * Sink interface for the analytics backend.
     * Translated from AnalyticsSink type in index.ts
     */
    public interface AnalyticsSink {
        void logEvent(String eventName, Map<String, Object> metadata);
        CompletableFuture<Void> logEventAsync(String eventName, Map<String, Object> metadata);
    }

    // =========================================================================
    // Internal state
    // =========================================================================

    private record QueuedEvent(String eventName, Map<String, Object> metadata, boolean async) {}

    private final List<QueuedEvent> eventQueue = new CopyOnWriteArrayList<>();
    private volatile AnalyticsSink sink = null;

    // Monotonically increasing counter for OTel event ordering within a session.
    // Mirrors eventSequence in src/utils/telemetry/events.ts
    private int eventSequence = 0;

    // Track whether we've already warned about a null event logger.
    // Mirrors hasWarnedNoEventLogger in src/utils/telemetry/events.ts
    private boolean hasWarnedNoEventLogger = false;

    // =========================================================================
    // Sink attachment
    // =========================================================================

    /**
     * Attach the analytics sink that will receive all events.
     * Queued events are drained asynchronously to avoid adding latency to the
     * startup path.
     *
     * Idempotent: if a sink is already attached, this is a no-op.
     * Translated from attachAnalyticsSink() in index.ts
     */
    public synchronized void attachAnalyticsSink(AnalyticsSink newSink) {
        if (this.sink != null) {
            return;
        }
        this.sink = newSink;

        if (!eventQueue.isEmpty()) {
            List<QueuedEvent> queuedEvents = new ArrayList<>(eventQueue);
            eventQueue.clear();

            // Log queue size for debug purposes (ant users only)
            if ("ant".equals(System.getenv("USER_TYPE"))) {
                LogEventMetadata debugMeta = new LogEventMetadata();
                debugMeta.with("queued_event_count", queuedEvents.size());
                newSink.logEvent(EVENT_ANALYTICS_SINK_ATTACHED, debugMeta);
            }

            // Drain the queue asynchronously to avoid blocking startup
            CompletableFuture.runAsync(() -> {
                for (QueuedEvent event : queuedEvents) {
                    if (event.async()) {
                        newSink.logEventAsync(event.eventName(), event.metadata());
                    } else {
                        newSink.logEvent(event.eventName(), event.metadata());
                    }
                }
            });
        }
    }

    // =========================================================================
    // Event logging
    // =========================================================================

    /**
     * Log an event to analytics backends (synchronous).
     *
     * Events may be sampled based on dynamic config.
     * If no sink is attached, events are queued and drained when the sink attaches.
     * Translated from logEvent() in index.ts
     */
    public void logEvent(String eventName, Map<String, Object> metadata) {
        if (sink == null) {
            eventQueue.add(new QueuedEvent(eventName, metadata != null ? metadata : Map.of(), false));
            return;
        }
        sink.logEvent(eventName, metadata != null ? metadata : Map.of());
    }

    /**
     * Log an event to analytics backends (asynchronous).
     *
     * If no sink is attached, events are queued and drained when the sink attaches.
     * Translated from logEventAsync() in index.ts
     */
    public CompletableFuture<Void> logEventAsync(String eventName, Map<String, Object> metadata) {
        if (sink == null) {
            eventQueue.add(new QueuedEvent(eventName, metadata != null ? metadata : Map.of(), true));
            return CompletableFuture.completedFuture(null);
        }
        return sink.logEventAsync(eventName, metadata != null ? metadata : Map.of());
    }

    // =========================================================================
    // OTel event logging
    // Translated from logOTelEvent() in src/utils/telemetry/events.ts
    // =========================================================================

    /**
     * Log an OTel event with standard metadata attributes.
     *
     * Adds event.name, event.timestamp, event.sequence, and optionally
     * prompt.id and workspace.host_paths attributes. Prefixes body with
     * "claude_code." matching the TypeScript implementation.
     *
     * Translated from logOTelEvent() in src/utils/telemetry/events.ts
     */
    public synchronized void logOTelEvent(String eventName,
                                          Map<String, String> additionalMetadata) {
        if (sink == null) {
            if (!hasWarnedNoEventLogger) {
                hasWarnedNoEventLogger = true;
                log.warn("[3P telemetry] Event dropped (no event logger initialized): {}", eventName);
            }
            return;
        }

        // Skip logging in test environment
        if ("test".equals(System.getenv("NODE_ENV"))) {
            return;
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_EVENT_NAME, eventName);
        attributes.put(ATTR_EVENT_TIMESTAMP, java.time.Instant.now().toString());
        attributes.put(ATTR_EVENT_SEQUENCE, eventSequence++);

        String promptId = System.getenv("CLAUDE_PROMPT_ID");
        if (promptId != null) {
            attributes.put(ATTR_PROMPT_ID, promptId);
        }

        String workspaceDir = System.getenv("CLAUDE_CODE_WORKSPACE_HOST_PATHS");
        if (workspaceDir != null) {
            attributes.put(ATTR_WORKSPACE_HOST_PATHS, workspaceDir.split("\\|"));
        }

        if (additionalMetadata != null) {
            for (Map.Entry<String, String> entry : additionalMetadata.entrySet()) {
                if (entry.getValue() != null) {
                    attributes.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Emit prefixed event
        sink.logEvent(OTEL_EVENT_PREFIX + eventName, attributes);
    }

    /**
     * Redact content if user-prompt logging is not enabled.
     * Translated from redactIfDisabled() in src/utils/telemetry/events.ts
     */
    public static String redactIfDisabled(String content) {
        String flag = System.getenv("OTEL_LOG_USER_PROMPTS");
        boolean enabled = flag != null
                && (flag.equalsIgnoreCase("true") || flag.equals("1") || flag.equalsIgnoreCase("yes"));
        return enabled ? content : "<REDACTED>";
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Strip {@code _PROTO_*} keys from a payload destined for general-access
     * storage (e.g. Datadog). PII-tagged values sent to privileged 1P columns
     * use these keys; non-1P sinks must never see them.
     *
     * Returns the input unchanged when no {@code _PROTO_} keys are present.
     * Translated from stripProtoFields() in index.ts
     */
    public static Map<String, Object> stripProtoFields(Map<String, Object> metadata) {
        if (metadata == null) return Map.of();
        boolean hasProtoFields = metadata.keySet().stream().anyMatch(k -> k.startsWith("_PROTO_"));
        if (!hasProtoFields) return metadata;

        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.keySet().removeIf(k -> k.startsWith("_PROTO_"));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Reset analytics state for testing purposes only.
     * Translated from _resetForTesting() in index.ts
     */
    public synchronized void resetForTesting() {
        sink = null;
        eventQueue.clear();
        eventSequence = 0;
        hasWarnedNoEventLogger = false;
    }

    /** Expose current sink for subclasses / tests. */
    public AnalyticsSink getSink() {
        return sink;
    }

    // =========================================================================
    // Serial Batch Event Uploader  (src/cli/transports/SerialBatchEventUploader.ts)
    // =========================================================================

    /**
     * Throw from {@link SerialBatchEventUploader.Config#send} to make the
     * uploader wait a server-supplied duration before retrying (e.g. 429 with
     * Retry-After).  When {@code retryAfterMs} is set it overrides exponential
     * backoff for that attempt — clamped to [baseDelayMs, maxDelayMs] and
     * jittered.  Without {@code retryAfterMs}, behaves like any other error
     * (exponential backoff).
     *
     * Translated from {@code RetryableError} in SerialBatchEventUploader.ts.
     */
    public static class RetryableError extends RuntimeException {
        private final Long retryAfterMs;

        public RetryableError(String message) {
            super(message);
            this.retryAfterMs = null;
        }

        public RetryableError(String message, Long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }

        public Optional<Long> getRetryAfterMs() {
            return Optional.ofNullable(retryAfterMs);
        }
    }

    /**
     * Serial ordered event uploader with batching, retry, and backpressure.
     *
     * <ul>
     *   <li>{@link #enqueue} adds events to the pending buffer</li>
     *   <li>At most one POST in-flight at a time</li>
     *   <li>Drains up to {@code maxBatchSize} items per POST</li>
     *   <li>New events accumulate while in-flight</li>
     *   <li>On failure: exponential backoff (clamped), retries indefinitely
     *       unless {@code maxConsecutiveFailures} is set</li>
     *   <li>{@link #flush} blocks until pending queue is empty</li>
     *   <li>Backpressure: {@link #enqueue} waits when {@code maxQueueSize} is reached</li>
     * </ul>
     *
     * Translated from {@code SerialBatchEventUploader<T>} in SerialBatchEventUploader.ts.
     *
     * @param <T> event item type
     */
    public static class SerialBatchEventUploader<T> {

        /**
         * Configuration for a {@link SerialBatchEventUploader}.
         * Mirrors {@code SerialBatchEventUploaderConfig<T>} in SerialBatchEventUploader.ts.
         */
        public static class Config<T> {
            /** Max items per POST (1 = no batching). */
            public int maxBatchSize = 100;
            /**
             * Max serialised bytes per POST.  First item always goes in regardless of size;
             * subsequent items only if cumulative JSON bytes stay under this.
             * {@code null} = no byte limit (count-only batching).
             */
            public Integer maxBatchBytes = null;
            /** Max pending items before {@link #enqueue} blocks. */
            public int maxQueueSize = 10_000;
            /** The actual HTTP call — caller controls payload format. */
            public Consumer<List<T>> send;
            /** Base delay for exponential backoff (ms). */
            public long baseDelayMs = 500;
            /** Max delay cap (ms). */
            public long maxDelayMs = 30_000;
            /** Random jitter range added to retry delay (ms). */
            public long jitterMs = 500;
            /**
             * After this many consecutive failures, drop the batch and move on.
             * {@code null} = retry indefinitely (default).
             */
            public Integer maxConsecutiveFailures = null;
            /** Called when a batch is dropped for hitting {@code maxConsecutiveFailures}. */
            public BiConsumer<Integer, Integer> onBatchDropped = null;
        }

        private final Config<T> config;
        private final List<T> pending = new ArrayList<>();
        private int pendingAtClose = 0;
        private volatile boolean draining = false;
        private volatile boolean closed = false;
        private final List<CompletableFuture<Void>> backpressureWaiters = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<Void>> flushWaiters = new CopyOnWriteArrayList<>();
        private int droppedBatches = 0;

        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "serial-batch-uploader");
            t.setDaemon(true);
            return t;
        });

        public SerialBatchEventUploader(Config<T> config) {
            this.config = config;
        }

        /**
         * Monotonic count of batches dropped via {@code maxConsecutiveFailures}.
         * Translated from {@code droppedBatchCount} getter in SerialBatchEventUploader.ts.
         */
        public int getDroppedBatchCount() { return droppedBatches; }

        /**
         * Pending queue depth.  After {@link #close}, returns the count at close time.
         * Translated from {@code pendingCount} getter in SerialBatchEventUploader.ts.
         */
        public synchronized int getPendingCount() {
            return closed ? pendingAtClose : pending.size();
        }

        /**
         * Add a single event.  Blocks when the buffer is full.
         * Translated from {@code enqueue(events)} in SerialBatchEventUploader.ts.
         */
        public CompletableFuture<Void> enqueue(T event) {
            return enqueue(List.of(event));
        }

        /**
         * Add multiple events.  Blocks when the buffer is full.
         * Translated from {@code enqueue(events)} in SerialBatchEventUploader.ts.
         */
        public synchronized CompletableFuture<Void> enqueue(List<T> items) {
            if (closed || items == null || items.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            if (pending.size() + items.size() > config.maxQueueSize) {
                CompletableFuture<Void> waiter = new CompletableFuture<>();
                backpressureWaiters.add(waiter);
                return waiter.thenCompose(ignored -> enqueue(items));
            }
            pending.addAll(items);
            scheduleDrain();
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Block until all pending events have been sent.
         * Translated from {@code flush()} in SerialBatchEventUploader.ts.
         */
        public synchronized CompletableFuture<Void> flush() {
            if (pending.isEmpty() && !draining) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> waiter = new CompletableFuture<>();
            flushWaiters.add(waiter);
            scheduleDrain();
            return waiter;
        }

        /**
         * Drop pending events and stop processing.
         * Resolves any blocked callers.
         * Translated from {@code close()} in SerialBatchEventUploader.ts.
         */
        public synchronized void close() {
            if (closed) return;
            closed = true;
            pendingAtClose = pending.size();
            pending.clear();
            releaseBackpressure();
            notifyFlushWaiters();
            executor.shutdownNow();
        }

        private synchronized void scheduleDrain() {
            if (draining || closed) return;
            draining = true;
            executor.submit(this::drainLoop);
        }

        /**
         * Drain loop — sends batches serially, retrying on failure with backoff.
         * Translated from the private {@code drain()} method in SerialBatchEventUploader.ts.
         */
        private void drainLoop() {
            int failures = 0;
            try {
                while (true) {
                    List<T> batch;
                    synchronized (this) {
                        if (pending.isEmpty() || closed) break;
                        batch = takeBatch();
                    }
                    if (batch.isEmpty()) continue;

                    try {
                        config.send.accept(batch);
                        failures = 0;
                    } catch (Exception err) {
                        failures++;
                        Integer maxFails = config.maxConsecutiveFailures;
                        if (maxFails != null && failures >= maxFails) {
                            droppedBatches++;
                            if (config.onBatchDropped != null) {
                                config.onBatchDropped.accept(batch.size(), failures);
                            }
                            failures = 0;
                            synchronized (this) { releaseBackpressure(); }
                            continue;
                        }
                        // Re-queue failed batch at front
                        synchronized (this) {
                            pending.addAll(0, batch);
                        }
                        Long retryAfterMs = (err instanceof RetryableError re)
                            ? re.getRetryAfterMs().orElse(null)
                            : null;
                        sleepForRetry(failures, retryAfterMs);
                        continue;
                    }

                    synchronized (this) { releaseBackpressure(); }
                }
            } finally {
                synchronized (this) {
                    draining = false;
                    if (pending.isEmpty()) notifyFlushWaiters();
                }
            }
        }

        /**
         * Pull the next batch from pending, respecting maxBatchSize and maxBatchBytes.
         * Un-serialisable items are dropped in place.
         * Translated from {@code takeBatch()} in SerialBatchEventUploader.ts.
         */
        private List<T> takeBatch() {
            int maxSize = config.maxBatchSize;
            Integer maxBytes = config.maxBatchBytes;
            if (maxBytes == null) {
                int end = Math.min(maxSize, pending.size());
                List<T> batch = new ArrayList<>(pending.subList(0, end));
                pending.subList(0, end).clear();
                return batch;
            }
            long bytes = 0;
            int count = 0;
            while (count < pending.size() && count < maxSize) {
                long itemBytes;
                try {
                    itemBytes = JsonUtils.jsonStringify(pending.get(count)).length();
                } catch (Exception e) {
                    pending.remove(count);
                    continue;
                }
                if (count > 0 && bytes + itemBytes > maxBytes) break;
                bytes += itemBytes;
                count++;
            }
            List<T> batch = new ArrayList<>(pending.subList(0, count));
            pending.subList(0, count).clear();
            return batch;
        }

        /**
         * Compute retry delay with optional server-supplied hint.
         * Translated from {@code retryDelay()} in SerialBatchEventUploader.ts.
         */
        private long retryDelay(int failures, Long retryAfterMs) {
            double jitter = Math.random() * config.jitterMs;
            if (retryAfterMs != null) {
                long clamped = Math.max(config.baseDelayMs,
                                       Math.min(retryAfterMs, config.maxDelayMs));
                return (long) (clamped + jitter);
            }
            long exponential = Math.min(
                (long) (config.baseDelayMs * Math.pow(2, failures - 1)),
                config.maxDelayMs);
            return (long) (exponential + jitter);
        }

        private void sleepForRetry(int failures, Long retryAfterMs) {
            try {
                Thread.sleep(retryDelay(failures, retryAfterMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void releaseBackpressure() {
            List<CompletableFuture<Void>> waiters = new ArrayList<>(backpressureWaiters);
            backpressureWaiters.clear();
            waiters.forEach(f -> f.complete(null));
        }

        private void notifyFlushWaiters() {
            List<CompletableFuture<Void>> waiters = new ArrayList<>(flushWaiters);
            flushWaiters.clear();
            waiters.forEach(f -> f.complete(null));
        }
    }
}
