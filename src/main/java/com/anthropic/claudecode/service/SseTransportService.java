package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.TransportUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * SSE Transport Service.
 * Translated from src/cli/transports/SSETransport.ts
 *
 * Transport that uses SSE for reading and HTTP POST for writing.
 * Reads events via Server-Sent Events from the CCR v2 event stream endpoint.
 * Writes events via HTTP POST with retry logic.
 *
 * Supports automatic reconnection with exponential backoff and Last-Event-ID
 * for resumption after disconnection.
 */
@Slf4j
@Service
public class SseTransportService {



    // ---------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------

    private static final long RECONNECT_BASE_DELAY_MS = 1_000L;
    private static final long RECONNECT_MAX_DELAY_MS = 30_000L;
    /** Time budget for reconnection attempts before giving up (10 minutes). */
    private static final long RECONNECT_GIVE_UP_MS = 600_000L;
    /** Server sends keepalives every 15s; treat connection as dead after 45s of silence. */
    private static final long LIVENESS_TIMEOUT_MS = 45_000L;

    private static final Set<Integer> PERMANENT_HTTP_CODES = Set.of(401, 403, 404);

    private static final int POST_MAX_RETRIES = 10;
    private static final long POST_BASE_DELAY_MS = 500L;
    private static final long POST_MAX_DELAY_MS = 8_000L;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------------------------------------------------------------------
    // SSE Frame
    // ---------------------------------------------------------------------------

    /**
     * Represents a single parsed SSE frame.
     */
    public record SseFrame(String event, String id, String data) {}

    /**
     * Result of parsing SSE frames from a buffer.
     */
    public record ParseResult(List<SseFrame> frames, String remaining) {}

    /**
     * Payload for {@code event: client_event} frames, matching the StreamClientEvent
     * proto message in session_stream.proto.
     */
    public record StreamClientEvent(
            String eventId,
            long sequenceNum,
            String eventType,
            String source,
            Map<String, Object> payload,
            String createdAt
    ) {}

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    public enum SseTransportState {
        IDLE, CONNECTED, RECONNECTING, CLOSING, CLOSED
    }

    // ---------------------------------------------------------------------------
    // SSETransport instance
    // ---------------------------------------------------------------------------

    /**
     * A single SSE transport connection instance.
     * Create via {@link #createTransport(URL, Map, String, java.util.function.Supplier)}.
     */
    public static class SseTransport {

        private volatile SseTransportState state = SseTransportState.IDLE;
        private Consumer<String> onDataCallback;
        private Consumer<Integer> onCloseCallback;
        private Consumer<StreamClientEvent> onEventCallback;

        private final Map<String, String> headers;
        private final String sessionId;
        private final java.util.function.Supplier<Map<String, String>> refreshHeaders;
        private final java.util.function.Supplier<Map<String, String>> getAuthHeaders;

        // SSE connection state
        private volatile okhttp3.Call currentCall;
        private final AtomicLong lastSequenceNum = new AtomicLong(0);
        private final Set<Long> seenSequenceNums = ConcurrentHashMap.newKeySet();

        // Reconnection state
        private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
        private volatile Long reconnectStartTime = null;
        private volatile ScheduledFuture<?> reconnectTimer = null;

        // Liveness detection
        private volatile ScheduledFuture<?> livenessTimer = null;

        private final URL sseUrl;
        private final String postUrl;
        private final OkHttpClient httpClient;
        private final ScheduledExecutorService scheduler;

        SseTransport(
                URL sseUrl,
                Map<String, String> headers,
                String sessionId,
                java.util.function.Supplier<Map<String, String>> refreshHeaders,
                long initialSequenceNum,
                java.util.function.Supplier<Map<String, String>> getAuthHeaders,
                OkHttpClient httpClient,
                ScheduledExecutorService scheduler) {
            this.sseUrl = sseUrl;
            this.headers = new LinkedHashMap<>(headers);
            this.sessionId = sessionId;
            this.refreshHeaders = refreshHeaders;
            this.getAuthHeaders = getAuthHeaders != null ? getAuthHeaders
                    : () -> TransportUtils.getSessionIngressAuthHeaders();
            this.postUrl = convertSseUrlToPostUrl(sseUrl);
            this.httpClient = httpClient;
            this.scheduler = scheduler;

            if (initialSequenceNum > 0) {
                this.lastSequenceNum.set(initialSequenceNum);
            }
            log.debug("SseTransport: SSE URL = {}", sseUrl);
            log.debug("SseTransport: POST URL = {}", this.postUrl);
            log.info("cli_sse_transport_initialized");
        }

        /**
         * High-water mark of sequence numbers seen on this stream.
         */
        public long getLastSequenceNum() {
            return lastSequenceNum.get();
        }

        /**
         * Open the SSE connection asynchronously.
         */
        public CompletableFuture<Void> connect() {
            if (state != SseTransportState.IDLE && state != SseTransportState.RECONNECTING) {
                log.error("SseTransport: Cannot connect, current state is {}", state);
                log.error("cli_sse_connect_failed");
                return CompletableFuture.completedFuture(null);
            }
            state = SseTransportState.RECONNECTING;
            long connectStartTime = System.currentTimeMillis();

            // Build SSE URL with sequence number for resumption
            URL resolvedUrl = sseUrl;
            try {
                long seq = lastSequenceNum.get();
                String query = sseUrl.getQuery();
                String extra = seq > 0 ? "from_sequence_num=" + seq : "";
                String newQuery = (query == null || query.isEmpty()) ? extra
                        : (extra.isEmpty() ? query : query + "&" + extra);
                resolvedUrl = new URL(sseUrl.getProtocol(), sseUrl.getHost(), sseUrl.getPort(),
                        sseUrl.getPath() + (newQuery.isEmpty() ? "" : "?" + newQuery));
            } catch (MalformedURLException e) {
                log.error("SseTransport: Failed to build SSE URL with sequence num", e);
            }

            Map<String, String> authHeaders = getAuthHeaders.get();
            okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                    .url(resolvedUrl.toString())
                    .header("Accept", "text/event-stream")
                    .header("anthropic-version", "2023-06-01")
                    .header("User-Agent", TransportUtils.getClaudeCodeUserAgent());

            // Apply base headers
            for (Map.Entry<String, String> e : headers.entrySet()) {
                requestBuilder.header(e.getKey(), e.getValue());
            }
            // Apply auth headers (possibly override Authorization if Cookie is present)
            for (Map.Entry<String, String> e : authHeaders.entrySet()) {
                requestBuilder.header(e.getKey(), e.getValue());
            }
            if (authHeaders.containsKey("Cookie")) {
                requestBuilder.removeHeader("Authorization");
            }
            long seq = lastSequenceNum.get();
            if (seq > 0) {
                requestBuilder.header("Last-Event-ID", String.valueOf(seq));
            }

            log.debug("SseTransport: Opening {}", resolvedUrl);
            log.info("cli_sse_connect_opening");

            Request request = requestBuilder.build();
            currentCall = httpClient.newCall(request);

            CompletableFuture<Void> future = new CompletableFuture<>();
            currentCall.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (call.isCanceled()) {
                        future.complete(null);
                        return;
                    }
                    log.error("SseTransport: Connection error: {}", e.getMessage());
                    log.error("cli_sse_connect_error");
                    handleConnectionError();
                    future.complete(null);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (response) {
                        int status = response.code();
                        if (!response.isSuccessful()) {
                            boolean isPermanent = PERMANENT_HTTP_CODES.contains(status);
                            log.error("SseTransport: HTTP {}{}", status, isPermanent ? " (permanent)" : "");
                            log.error("cli_sse_connect_http_error status={}", status);
                            if (isPermanent) {
                                state = SseTransportState.CLOSED;
                                if (onCloseCallback != null) onCloseCallback.accept(status);
                                future.complete(null);
                                return;
                            }
                            handleConnectionError();
                            future.complete(null);
                            return;
                        }

                        ResponseBody body = response.body();
                        if (body == null) {
                            log.debug("SseTransport: No response body");
                            handleConnectionError();
                            future.complete(null);
                            return;
                        }

                        long connectDuration = System.currentTimeMillis() - connectStartTime;
                        log.debug("SseTransport: Connected");
                        log.info("cli_sse_connect_connected duration_ms={}", connectDuration);

                        state = SseTransportState.CONNECTED;
                        reconnectAttempts.set(0);
                        reconnectStartTime = null;
                        resetLivenessTimer();

                        // Read stream synchronously in this callback thread
                        readStream(body);
                        future.complete(null);
                    }
                }
            });
            return future;
        }

        /**
         * Read and process the SSE stream body.
         */
        private void readStream(ResponseBody body) {
            StringBuilder buffer = new StringBuilder();
            try (okio.BufferedSource source = body.source()) {
                while (!source.exhausted()) {
                    String chunk = source.readUtf8();
                    if (chunk.isEmpty()) continue;
                    buffer.append(chunk);
                    ParseResult result = parseSSEFrames(buffer.toString());
                    buffer.setLength(0);
                    buffer.append(result.remaining());

                    for (SseFrame frame : result.frames()) {
                        // Any frame proves the connection is alive
                        resetLivenessTimer();
                        processFrame(frame);
                    }
                }
            } catch (IOException e) {
                if (currentCall != null && currentCall.isCanceled()) return;
                log.error("SseTransport: Stream read error: {}", e.getMessage());
                log.error("cli_sse_stream_read_error");
            } finally {
                clearLivenessTimer();
            }

            if (state != SseTransportState.CLOSING && state != SseTransportState.CLOSED) {
                log.debug("SseTransport: Stream ended, reconnecting");
                handleConnectionError();
            }
        }

        private void processFrame(SseFrame frame) {
            if (frame.id() != null) {
                try {
                    long seqNum = Long.parseLong(frame.id());
                    if (seenSequenceNums.contains(seqNum)) {
                        log.warn("SseTransport: DUPLICATE frame seq={} (lastSequenceNum={}, seenCount={})",
                                seqNum, lastSequenceNum.get(), seenSequenceNums.size());
                        log.warn("cli_sse_duplicate_sequence");
                    } else {
                        seenSequenceNums.add(seqNum);
                        // Prune old sequence numbers to prevent unbounded growth
                        if (seenSequenceNums.size() > 1000) {
                            long threshold = lastSequenceNum.get() - 200;
                            seenSequenceNums.removeIf(s -> s < threshold);
                        }
                    }
                    lastSequenceNum.updateAndGet(cur -> Math.max(cur, seqNum));
                } catch (NumberFormatException ignored) {
                    // non-numeric id, skip
                }
            }

            if (frame.event() != null && frame.data() != null) {
                handleSSEFrame(frame.event(), frame.data());
            } else if (frame.data() != null) {
                log.warn("SseTransport: Frame has data: but no event: field — dropped");
                log.warn("cli_sse_frame_missing_event_field");
            }
        }

        /**
         * Handle a single SSE frame.
         */
        private void handleSSEFrame(String eventType, String data) {
            if (!"client_event".equals(eventType)) {
                log.warn("SseTransport: Unexpected SSE event type '{}' on worker stream", eventType);
                log.warn("cli_sse_unexpected_event_type event_type={}", eventType);
                return;
            }

            StreamClientEvent ev;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = objectMapper.readValue(data, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) raw.get("payload");
                ev = new StreamClientEvent(
                        (String) raw.get("event_id"),
                        ((Number) raw.getOrDefault("sequence_num", 0L)).longValue(),
                        (String) raw.get("event_type"),
                        (String) raw.get("source"),
                        payload,
                        (String) raw.get("created_at")
                );
            } catch (Exception e) {
                log.error("SseTransport: Failed to parse client_event data: {}", e.getMessage());
                return;
            }

            Map<String, Object> payload = ev.payload();
            if (payload != null && payload.containsKey("type")) {
                String sessionLabel = sessionId != null ? " session=" + sessionId : "";
                log.debug("SseTransport: Event seq={} event_id={} event_type={} payload_type={}{}",
                        ev.sequenceNum(), ev.eventId(), ev.eventType(), payload.get("type"), sessionLabel);
                log.info("cli_sse_message_received");
                if (onDataCallback != null) {
                    try {
                        onDataCallback.accept(objectMapper.writeValueAsString(payload) + "\n");
                    } catch (Exception e) {
                        log.error("SseTransport: Failed to serialize payload", e);
                    }
                }
            } else {
                log.debug("SseTransport: Ignoring client_event with no type in payload: event_id={}", ev.eventId());
            }

            if (onEventCallback != null) {
                onEventCallback.accept(ev);
            }
        }

        /**
         * Handle connection errors with exponential backoff and time budget.
         */
        private void handleConnectionError() {
            clearLivenessTimer();

            if (state == SseTransportState.CLOSING || state == SseTransportState.CLOSED) return;

            // Abort any in-flight SSE fetch
            if (currentCall != null) {
                currentCall.cancel();
                currentCall = null;
            }

            long now = System.currentTimeMillis();
            if (reconnectStartTime == null) {
                reconnectStartTime = now;
            }

            long elapsed = now - reconnectStartTime;
            if (elapsed < RECONNECT_GIVE_UP_MS) {
                if (reconnectTimer != null) {
                    reconnectTimer.cancel(false);
                    reconnectTimer = null;
                }

                // Refresh headers before reconnecting
                if (refreshHeaders != null) {
                    Map<String, String> fresh = refreshHeaders.get();
                    headers.putAll(fresh);
                    log.debug("SseTransport: Refreshed headers for reconnect");
                }

                state = SseTransportState.RECONNECTING;
                int attempt = reconnectAttempts.incrementAndGet();
                long baseDelay = Math.min(
                        RECONNECT_BASE_DELAY_MS * (1L << (attempt - 1)),
                        RECONNECT_MAX_DELAY_MS);
                // Add ±25% jitter
                long delay = Math.max(0L,
                        baseDelay + (long) (baseDelay * 0.25 * (2 * Math.random() - 1)));

                log.debug("SseTransport: Reconnecting in {}ms (attempt {}, {}s elapsed)",
                        delay, attempt, elapsed / 1000);
                log.error("cli_sse_reconnect_attempt reconnectAttempts={}", attempt);

                reconnectTimer = scheduler.schedule(() -> {
                    reconnectTimer = null;
                    connect();
                }, delay, TimeUnit.MILLISECONDS);
            } else {
                log.error("SseTransport: Reconnection time budget exhausted after {}s", elapsed / 1000);
                log.error("cli_sse_reconnect_exhausted reconnectAttempts={} elapsedMs={}", reconnectAttempts.get(), elapsed);
                state = SseTransportState.CLOSED;
                if (onCloseCallback != null) onCloseCallback.accept(null);
            }
        }

        /**
         * Bound liveness timeout handler.
         */
        private void onLivenessTimeout() {
            livenessTimer = null;
            log.error("SseTransport: Liveness timeout, reconnecting");
            log.error("cli_sse_liveness_timeout");
            if (currentCall != null) currentCall.cancel();
            handleConnectionError();
        }

        private void resetLivenessTimer() {
            clearLivenessTimer();
            livenessTimer = scheduler.schedule(this::onLivenessTimeout, LIVENESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private void clearLivenessTimer() {
            if (livenessTimer != null) {
                livenessTimer.cancel(false);
                livenessTimer = null;
            }
        }

        // -----------------------------------------------------------------------
        // Write (HTTP POST)
        // -----------------------------------------------------------------------

        /**
         * Write a message via HTTP POST with retry logic.
         */
        public CompletableFuture<Void> write(Map<String, Object> message) {
            Map<String, String> authHeaders = getAuthHeaders.get();
            if (authHeaders.isEmpty()) {
                log.debug("SseTransport: No session token available for POST");
                log.warn("cli_sse_post_no_token");
                return CompletableFuture.completedFuture(null);
            }

            return CompletableFuture.runAsync(() -> {
                String body;
                try {
                    body = objectMapper.writeValueAsString(message);
                } catch (Exception e) {
                    log.error("SseTransport: Failed to serialize POST body", e);
                    return;
                }

                MediaType json = MediaType.get("application/json; charset=utf-8");
                okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                        .url(postUrl)
                        .post(RequestBody.create(body, json))
                        .header("Content-Type", "application/json")
                        .header("anthropic-version", "2023-06-01")
                        .header("User-Agent", TransportUtils.getClaudeCodeUserAgent());
                for (Map.Entry<String, String> e : authHeaders.entrySet()) {
                    rb.header(e.getKey(), e.getValue());
                }

                for (int attempt = 1; attempt <= POST_MAX_RETRIES; attempt++) {
                    try (Response response = httpClient.newCall(rb.build()).execute()) {
                        int status = response.code();
                        if (status == 200 || status == 201) {
                            log.debug("SseTransport: POST success type={}", message.get("type"));
                            return;
                        }
                        log.debug("SseTransport: POST {} attempt {}/{}", status, attempt, POST_MAX_RETRIES);
                        // 4xx (except 429) are permanent — don't retry
                        if (status >= 400 && status < 500 && status != 429) {
                            log.warn("cli_sse_post_client_error status={}", status);
                            return;
                        }
                        log.warn("cli_sse_post_retryable_error status={} attempt={}", status, attempt);
                    } catch (IOException e) {
                        log.warn("SseTransport: POST error: {}, attempt {}/{}", e.getMessage(), attempt, POST_MAX_RETRIES);
                        log.warn("cli_sse_post_network_error attempt={}", attempt);
                    }

                    if (attempt == POST_MAX_RETRIES) {
                        log.warn("cli_sse_post_retries_exhausted");
                        return;
                    }

                    long delayMs = Math.min(POST_BASE_DELAY_MS * (1L << (attempt - 1)), POST_MAX_DELAY_MS);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        // -----------------------------------------------------------------------
        // Transport interface
        // -----------------------------------------------------------------------

        public boolean isConnected() {
            return state == SseTransportState.CONNECTED;
        }

        public boolean isClosed() {
            return state == SseTransportState.CLOSED;
        }

        public void setOnData(Consumer<String> callback) {
            this.onDataCallback = callback;
        }

        public void setOnClose(Consumer<Integer> callback) {
            this.onCloseCallback = callback;
        }

        public void setOnEvent(Consumer<StreamClientEvent> callback) {
            this.onEventCallback = callback;
        }

        public void close() {
            if (reconnectTimer != null) {
                reconnectTimer.cancel(false);
                reconnectTimer = null;
            }
            clearLivenessTimer();
            state = SseTransportState.CLOSING;
            if (currentCall != null) {
                currentCall.cancel();
                currentCall = null;
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Factory method
    // ---------------------------------------------------------------------------

    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    public SseTransportService(OkHttpClient httpClient, ScheduledExecutorService scheduler) {
        this.httpClient = httpClient;
        this.scheduler = scheduler;
    }

    /**
     * Create a new {@link SseTransport} instance.
     */
    public SseTransport createTransport(
            URL sseUrl,
            Map<String, String> headers,
            String sessionId,
            java.util.function.Supplier<Map<String, String>> refreshHeaders) {
        return createTransport(sseUrl, headers, sessionId, refreshHeaders, 0L, null);
    }

    /**
     * Create a new {@link SseTransport} with a caller-provided auth-header supplier
     * (required for concurrent multi-session callers).
     */
    public SseTransport createTransport(
            URL sseUrl,
            Map<String, String> headers,
            String sessionId,
            java.util.function.Supplier<Map<String, String>> refreshHeaders,
            long initialSequenceNum,
            java.util.function.Supplier<Map<String, String>> getAuthHeaders) {
        return new SseTransport(sseUrl, headers == null ? Map.of() : headers,
                sessionId, refreshHeaders, initialSequenceNum, getAuthHeaders,
                httpClient, scheduler);
    }

    // ---------------------------------------------------------------------------
    // SSE Frame Parser — exported for testing
    // ---------------------------------------------------------------------------

    /**
     * Incrementally parse SSE frames from a text buffer.
     * Returns parsed frames and the remaining (incomplete) buffer.
     * Translated from parseSSEFrames() in SSETransport.ts
     */
    public static ParseResult parseSSEFrames(String buffer) {
        List<SseFrame> frames = new ArrayList<>();
        int pos = 0;
        int idx;

        while ((idx = buffer.indexOf("\n\n", pos)) != -1) {
            String rawFrame = buffer.substring(pos, idx);
            pos = idx + 2;

            if (rawFrame.isBlank()) continue;

            String event = null;
            String id = null;
            String data = null;
            boolean isComment = false;

            for (String line : rawFrame.split("\n", -1)) {
                if (line.startsWith(":")) {
                    isComment = true;
                    continue;
                }
                int colonIdx = line.indexOf(':');
                if (colonIdx == -1) continue;

                String field = line.substring(0, colonIdx);
                // Per SSE spec, strip one leading space after colon if present
                String value = (colonIdx + 1 < line.length() && line.charAt(colonIdx + 1) == ' ')
                        ? line.substring(colonIdx + 2)
                        : line.substring(colonIdx + 1);

                switch (field) {
                    case "event" -> event = value;
                    case "id" -> id = value;
                    case "data" -> data = data == null ? value : data + "\n" + value;
                    // ignore retry: and other fields
                }
            }

            if (data != null || isComment) {
                frames.add(new SseFrame(event, id, data));
            }
        }

        return new ParseResult(frames, buffer.substring(pos));
    }

    // ---------------------------------------------------------------------------
    // URL Conversion
    // ---------------------------------------------------------------------------

    /**
     * Convert an SSE URL to the HTTP POST endpoint URL.
     * From: .../events/stream
     * To:   .../events
     * Translated from convertSSEUrlToPostUrl() in SSETransport.ts
     */
    public static String convertSseUrlToPostUrl(URL sseUrl) {
        String path = sseUrl.getPath();
        if (path.endsWith("/stream")) {
            path = path.substring(0, path.length() - "/stream".length());
        }
        int port = sseUrl.getPort();
        String portStr = port == -1 ? "" : ":" + port;
        return sseUrl.getProtocol() + "://" + sseUrl.getHost() + portStr + path;
    }
}
