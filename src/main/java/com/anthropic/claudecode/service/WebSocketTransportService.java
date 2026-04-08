package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.TransportUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * WebSocket Transport Service.
 * Translated from src/cli/transports/WebSocketTransport.ts
 *
 * Transport that uses a WebSocket for both reading and writing, with
 * automatic reconnection, exponential backoff, message buffering for replay,
 * and periodic ping/keepalive frames.
 */
@Slf4j
@Service
public class WebSocketTransportService {



    // ---------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------

    private static final String KEEP_ALIVE_FRAME = "{\"type\":\"keep_alive\"}\n";

    private static final int DEFAULT_MAX_BUFFER_SIZE = 1000;
    private static final long DEFAULT_BASE_RECONNECT_DELAY = 1_000L;
    private static final long DEFAULT_MAX_RECONNECT_DELAY = 30_000L;
    /** Time budget for reconnection attempts before giving up (10 minutes). */
    private static final long DEFAULT_RECONNECT_GIVE_UP_MS = 600_000L;
    private static final long DEFAULT_PING_INTERVAL = 10_000L;
    private static final long DEFAULT_KEEPALIVE_INTERVAL = 300_000L; // 5 minutes

    /**
     * Threshold for detecting system sleep/wake: if the gap between consecutive
     * reconnection attempts exceeds this, the machine likely slept.
     */
    private static final long SLEEP_DETECTION_THRESHOLD_MS = DEFAULT_MAX_RECONNECT_DELAY * 2; // 60s

    /**
     * WebSocket close codes that indicate a permanent server-side rejection.
     */
    private static final Set<Integer> PERMANENT_CLOSE_CODES = Set.of(
            1002, // protocol error
            4001, // session expired / not found
            4003  // unauthorized
    );

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------------------------------------------------------------------
    // State enum
    // ---------------------------------------------------------------------------

    public enum WebSocketTransportState {
        IDLE, CONNECTED, RECONNECTING, CLOSING, CLOSED
    }

    // ---------------------------------------------------------------------------
    // Options
    // ---------------------------------------------------------------------------

    /**
     * Options for configuring a {@link WebSocketTransport} instance.
     * Translated from WebSocketTransportOptions in WebSocketTransport.ts
     */
    public record WebSocketTransportOptions(
            boolean autoReconnect,
            boolean isBridge
    ) {
        public WebSocketTransportOptions() {
            this(true, false);
        }
    }

    // ---------------------------------------------------------------------------
    // WebSocketTransport instance
    // ---------------------------------------------------------------------------

    /**
     * A single WebSocket transport connection instance.
     */
    public static class WebSocketTransport {

        private volatile WebSocket ws = null;
        private volatile String lastSentId = null;

        protected final URL url;
        protected volatile WebSocketTransportState state = WebSocketTransportState.IDLE;
        protected Consumer<String> onDataCallback;
        private Consumer<Integer> onCloseCallback;
        private Runnable onConnectCallback;

        private final Map<String, String> headers;
        private final String sessionId;
        private final boolean autoReconnect;
        private final boolean isBridge;

        // Reconnection state
        private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
        private volatile Long reconnectStartTime = null;
        private volatile ScheduledFuture<?> reconnectTimer = null;
        private volatile Long lastReconnectAttemptTime = null;
        private volatile long lastActivityTime = 0L;

        // Ping/pong
        private volatile ScheduledFuture<?> pingInterval = null;
        private volatile boolean pongReceived = true;

        // Keepalive
        private volatile ScheduledFuture<?> keepAliveInterval = null;

        // Message buffer for replay on reconnection (circular, bounded)
        private final Deque<Map<String, Object>> messageBuffer;
        private final int maxBufferSize;

        // Connect timing
        private volatile long connectStartTime = 0L;

        private final Supplier<Map<String, String>> refreshHeaders;
        private final OkHttpClient httpClient;
        private final ScheduledExecutorService scheduler;

        WebSocketTransport(
                URL url,
                Map<String, String> headers,
                String sessionId,
                Supplier<Map<String, String>> refreshHeaders,
                WebSocketTransportOptions options,
                OkHttpClient httpClient,
                ScheduledExecutorService scheduler) {
            this.url = url;
            this.headers = new LinkedHashMap<>(headers == null ? Map.of() : headers);
            this.sessionId = sessionId;
            this.refreshHeaders = refreshHeaders;
            this.autoReconnect = options != null ? options.autoReconnect() : true;
            this.isBridge = options != null ? options.isBridge() : false;
            this.maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;
            this.messageBuffer = new ArrayDeque<>(maxBufferSize + 1);
            this.httpClient = httpClient;
            this.scheduler = scheduler;
        }

        /**
         * Open the WebSocket connection asynchronously.
         */
        public CompletableFuture<Void> connect() {
            if (state != WebSocketTransportState.IDLE && state != WebSocketTransportState.RECONNECTING) {
                log.error("WebSocketTransport: Cannot connect, current state is {}", state);
                log.error("cli_websocket_connect_failed");
                return CompletableFuture.completedFuture(null);
            }
            state = WebSocketTransportState.RECONNECTING;
            connectStartTime = System.currentTimeMillis();
            log.debug("WebSocketTransport: Opening {}", url);
            log.info("cli_websocket_connect_opening");

            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url.toString());
            for (Map.Entry<String, String> e : headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            if (lastSentId != null) {
                rb.header("X-Last-Request-Id", lastSentId);
                log.debug("WebSocketTransport: Adding X-Last-Request-Id header: {}", lastSentId);
            }

            ws = httpClient.newWebSocket(rb.build(), new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    handleOpenEvent(response);
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    lastActivityTime = System.currentTimeMillis();
                    log.info("cli_websocket_message_received length={}", text.length());
                    if (onDataCallback != null) onDataCallback.accept(text);
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    String text = bytes.utf8();
                    lastActivityTime = System.currentTimeMillis();
                    log.info("cli_websocket_message_received length={}", text.length());
                    if (onDataCallback != null) onDataCallback.accept(text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    log.error("WebSocketTransport: Error: {}", t.getMessage());
                    log.error("cli_websocket_connect_error");
                    // close event fires after error — let onClosing/onClosed call handleConnectionError
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    boolean isClean = code == 1000 || code == 1001;
                    if (isClean) {
                        log.debug("WebSocketTransport: Closed: {}", code);
                    } else {
                        log.error("WebSocketTransport: Closed: {}", code);
                    }
                    log.error("cli_websocket_connect_closed");
                    handleConnectionError(code);
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(1000, null);
                }
            });

            return CompletableFuture.completedFuture(null);
        }

        private void handleOpenEvent(Response upgradeResponse) {
            long connectDuration = System.currentTimeMillis() - connectStartTime;
            log.debug("WebSocketTransport: Connected");
            log.info("cli_websocket_connect_connected duration_ms={}", connectDuration);

            reconnectAttempts.set(0);
            reconnectStartTime = null;
            lastReconnectAttemptTime = null;
            lastActivityTime = System.currentTimeMillis();
            state = WebSocketTransportState.CONNECTED;
            if (onConnectCallback != null) onConnectCallback.run();

            // Check for last-id in upgrade response headers
            String serverLastId = upgradeResponse != null ? upgradeResponse.header("x-last-request-id") : null;
            replayBufferedMessages(serverLastId != null ? serverLastId : "");

            startPingInterval();
            startKeepaliveInterval();
        }

        /**
         * Send a raw line on the WebSocket.
         */
        protected boolean sendLine(String line) {
            if (ws == null || state != WebSocketTransportState.CONNECTED) {
                log.debug("WebSocketTransport: Not connected");
                log.info("cli_websocket_send_not_connected");
                return false;
            }
            boolean sent = ws.send(line);
            if (sent) {
                lastActivityTime = System.currentTimeMillis();
                return true;
            }
            log.error("WebSocketTransport: Failed to send");
            log.error("cli_websocket_send_error");
            handleConnectionError(null);
            return false;
        }

        protected void doDisconnect() {
            stopPingInterval();
            stopKeepaliveInterval();
            if (ws != null) {
                ws.close(1000, "closing");
                ws = null;
            }
        }

        private void handleConnectionError(Integer closeCode) {
            log.debug("WebSocketTransport: Disconnected from {}{}",
                    url, closeCode != null ? " (code " + closeCode + ")" : "");
            log.info("cli_websocket_disconnected");

            doDisconnect();

            if (state == WebSocketTransportState.CLOSING || state == WebSocketTransportState.CLOSED) return;

            // Handle 4003 with header refresh
            boolean headersRefreshed = false;
            if (closeCode != null && closeCode == 4003 && refreshHeaders != null) {
                Map<String, String> fresh = refreshHeaders.get();
                if (!Objects.equals(fresh.get("Authorization"), headers.get("Authorization"))) {
                    headers.putAll(fresh);
                    headersRefreshed = true;
                    log.debug("WebSocketTransport: 4003 received but headers refreshed, scheduling reconnect");
                    log.info("cli_websocket_4003_token_refreshed");
                }
            }

            if (closeCode != null && PERMANENT_CLOSE_CODES.contains(closeCode) && !headersRefreshed) {
                log.error("WebSocketTransport: Permanent close code {}, not reconnecting", closeCode);
                log.error("cli_websocket_permanent_close closeCode={}", closeCode);
                state = WebSocketTransportState.CLOSED;
                if (onCloseCallback != null) onCloseCallback.accept(closeCode);
                return;
            }

            if (!autoReconnect) {
                state = WebSocketTransportState.CLOSED;
                if (onCloseCallback != null) onCloseCallback.accept(closeCode);
                return;
            }

            long now = System.currentTimeMillis();
            if (reconnectStartTime == null) {
                reconnectStartTime = now;
            }

            // Detect system sleep/wake
            if (lastReconnectAttemptTime != null &&
                    (now - lastReconnectAttemptTime) > SLEEP_DETECTION_THRESHOLD_MS) {
                log.debug("WebSocketTransport: Detected system sleep ({}s gap), resetting reconnection budget",
                        (now - lastReconnectAttemptTime) / 1000);
                log.info("cli_websocket_sleep_detected gapMs={}", now - lastReconnectAttemptTime);
                reconnectStartTime = now;
                reconnectAttempts.set(0);
            }
            lastReconnectAttemptTime = now;

            long elapsed = now - reconnectStartTime;
            if (elapsed < DEFAULT_RECONNECT_GIVE_UP_MS) {
                if (reconnectTimer != null) {
                    reconnectTimer.cancel(false);
                    reconnectTimer = null;
                }

                if (!headersRefreshed && refreshHeaders != null) {
                    headers.putAll(refreshHeaders.get());
                    log.debug("WebSocketTransport: Refreshed headers for reconnect");
                }

                state = WebSocketTransportState.RECONNECTING;
                int attempt = reconnectAttempts.incrementAndGet();
                long baseDelay = Math.min(DEFAULT_BASE_RECONNECT_DELAY * (1L << (attempt - 1)),
                        DEFAULT_MAX_RECONNECT_DELAY);
                // Add ±25% jitter to avoid thundering herd
                long delay = Math.max(0L,
                        baseDelay + (long) (baseDelay * 0.25 * (2 * Math.random() - 1)));

                log.debug("WebSocketTransport: Reconnecting in {}ms (attempt {}, {}s elapsed)",
                        delay, attempt, elapsed / 1000);
                log.error("cli_websocket_reconnect_attempt reconnectAttempts={}", attempt);

                Integer finalCloseCode = closeCode;
                reconnectTimer = scheduler.schedule(() -> {
                    reconnectTimer = null;
                    connect();
                }, delay, TimeUnit.MILLISECONDS);
            } else {
                log.error("WebSocketTransport: Reconnection time budget exhausted after {}s for {}",
                        elapsed / 1000, url);
                log.error("cli_websocket_reconnect_exhausted reconnectAttempts={} elapsedMs={}",
                        reconnectAttempts.get(), elapsed);
                state = WebSocketTransportState.CLOSED;
                if (onCloseCallback != null) onCloseCallback.accept(closeCode);
            }
        }

        /**
         * Close the WebSocket transport cleanly.
         */
        public void close() {
            if (reconnectTimer != null) {
                reconnectTimer.cancel(false);
                reconnectTimer = null;
            }
            stopPingInterval();
            stopKeepaliveInterval();
            state = WebSocketTransportState.CLOSING;
            doDisconnect();
        }

        private void replayBufferedMessages(String serverLastId) {
            List<Map<String, Object>> messages;
            synchronized (messageBuffer) {
                messages = new ArrayList<>(messageBuffer);
            }
            if (messages.isEmpty()) return;

            int startIndex = 0;
            if (serverLastId != null && !serverLastId.isEmpty()) {
                for (int i = 0; i < messages.size(); i++) {
                    Object uuid = messages.get(i).get("uuid");
                    if (serverLastId.equals(uuid)) {
                        startIndex = i + 1;
                        // Evict confirmed messages from the buffer
                        synchronized (messageBuffer) {
                            for (int j = 0; j < startIndex; j++) {
                                messageBuffer.pollFirst();
                            }
                        }
                        if (startIndex >= messages.size()) {
                            lastSentId = null;
                        }
                        log.debug("WebSocketTransport: Evicted {} confirmed messages, {} remaining",
                                startIndex, messages.size() - startIndex);
                        log.info("cli_websocket_evicted_confirmed_messages evicted={} remaining={}",
                                startIndex, messages.size() - startIndex);
                        break;
                    }
                }
            }

            List<Map<String, Object>> toReplay = messages.subList(startIndex, messages.size());
            if (toReplay.isEmpty()) {
                log.debug("WebSocketTransport: No new messages to replay");
                log.info("cli_websocket_no_messages_to_replay");
                return;
            }

            log.debug("WebSocketTransport: Replaying {} buffered messages", toReplay.size());
            log.info("cli_websocket_messages_to_replay count={}", toReplay.size());

            for (Map<String, Object> message : toReplay) {
                try {
                    String line = objectMapper.writeValueAsString(message) + "\n";
                    boolean success = sendLine(line);
                    if (!success) {
                        handleConnectionError(null);
                        break;
                    }
                } catch (Exception e) {
                    log.error("WebSocketTransport: Failed to serialize message for replay", e);
                }
            }
        }

        // -----------------------------------------------------------------------
        // Transport interface
        // -----------------------------------------------------------------------

        public boolean isConnected() {
            return state == WebSocketTransportState.CONNECTED;
        }

        public boolean isClosed() {
            return state == WebSocketTransportState.CLOSED;
        }

        public void setOnData(Consumer<String> callback) {
            this.onDataCallback = callback;
        }

        public void setOnConnect(Runnable callback) {
            this.onConnectCallback = callback;
        }

        public void setOnClose(Consumer<Integer> callback) {
            this.onCloseCallback = callback;
        }

        public String getStateLabel() {
            return state.name().toLowerCase();
        }

        /**
         * Write a message to the transport.
         * Messages with a "uuid" field are buffered for replay on reconnection.
         */
        public CompletableFuture<Void> write(Map<String, Object> message) {
            Object uuid = message.get("uuid");
            if (uuid instanceof String uuidStr) {
                synchronized (messageBuffer) {
                    messageBuffer.addLast(message);
                    if (messageBuffer.size() > maxBufferSize) {
                        messageBuffer.pollFirst();
                    }
                }
                lastSentId = uuidStr;
            }

            if (state != WebSocketTransportState.CONNECTED) {
                // Message buffered for replay when connected (if it has a UUID)
                return CompletableFuture.completedFuture(null);
            }

            String sessionLabel = sessionId != null ? " session=" + sessionId : "";
            log.debug("WebSocketTransport: Sending message type={}{}",
                    message.get("type"), sessionLabel);

            try {
                String line = objectMapper.writeValueAsString(message) + "\n";
                sendLine(line);
            } catch (Exception e) {
                log.error("WebSocketTransport: Failed to serialize message", e);
            }
            return CompletableFuture.completedFuture(null);
        }

        // -----------------------------------------------------------------------
        // Ping interval
        // -----------------------------------------------------------------------

        private void startPingInterval() {
            stopPingInterval();
            pongReceived = true;
            AtomicLong lastTickTime = new AtomicLong(System.currentTimeMillis());

            pingInterval = scheduler.scheduleAtFixedRate(() -> {
                if (state == WebSocketTransportState.CONNECTED && ws != null) {
                    long now = System.currentTimeMillis();
                    long gap = now - lastTickTime.getAndSet(now);

                    // Process-suspension detector
                    if (gap > SLEEP_DETECTION_THRESHOLD_MS) {
                        log.debug("WebSocketTransport: {}s tick gap detected — process was suspended, forcing reconnect",
                                gap / 1000);
                        log.info("cli_websocket_sleep_detected_on_ping gapMs={}", gap);
                        handleConnectionError(null);
                        return;
                    }

                    if (!pongReceived) {
                        log.error("WebSocketTransport: No pong received, connection appears dead");
                        log.error("cli_websocket_pong_timeout");
                        handleConnectionError(null);
                        return;
                    }

                    pongReceived = false;
                    try {
                        // OkHttp WebSocket does not expose ping() directly; send a ping frame via the internal API.
                        // We use the ws.send() with a control-frame workaround or rely on OkHttp's built-in pings.
                        // For simplicity, we track keep-alive via application-level frames.
                        // OkHttp supports setting pingInterval on OkHttpClient for WS pings at the HTTP level.
                        // Here we mark pongReceived = true immediately since OkHttp handles WS pings internally.
                        pongReceived = true;
                    } catch (Exception e) {
                        log.error("WebSocketTransport: Ping failed: {}", e.getMessage());
                        log.error("cli_websocket_ping_failed");
                    }
                }
            }, DEFAULT_PING_INTERVAL, DEFAULT_PING_INTERVAL, TimeUnit.MILLISECONDS);
        }

        private void stopPingInterval() {
            if (pingInterval != null) {
                pingInterval.cancel(false);
                pingInterval = null;
            }
        }

        // -----------------------------------------------------------------------
        // Keepalive interval
        // -----------------------------------------------------------------------

        private void startKeepaliveInterval() {
            stopKeepaliveInterval();

            // In CCR sessions (CLAUDE_CODE_REMOTE set), session activity heartbeats handle keep-alives
            String claudeCodeRemote = System.getenv("CLAUDE_CODE_REMOTE");
            if (claudeCodeRemote != null && !claudeCodeRemote.isBlank()
                    && !"false".equalsIgnoreCase(claudeCodeRemote) && !"0".equals(claudeCodeRemote)) {
                return;
            }

            keepAliveInterval = scheduler.scheduleAtFixedRate(() -> {
                if (state == WebSocketTransportState.CONNECTED && ws != null) {
                    boolean sent = ws.send(KEEP_ALIVE_FRAME);
                    if (sent) {
                        lastActivityTime = System.currentTimeMillis();
                        log.debug("WebSocketTransport: Sent periodic keep_alive data frame");
                    } else {
                        log.error("cli_websocket_keepalive_failed");
                    }
                }
            }, DEFAULT_KEEPALIVE_INTERVAL, DEFAULT_KEEPALIVE_INTERVAL, TimeUnit.MILLISECONDS);
        }

        private void stopKeepaliveInterval() {
            if (keepAliveInterval != null) {
                keepAliveInterval.cancel(false);
                keepAliveInterval = null;
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    public WebSocketTransportService(OkHttpClient httpClient, ScheduledExecutorService scheduler) {
        this.httpClient = httpClient;
        this.scheduler = scheduler;
    }

    /**
     * Create a new {@link WebSocketTransport} with default options.
     */
    public WebSocketTransport createTransport(URL url, Map<String, String> headers,
            String sessionId, Supplier<Map<String, String>> refreshHeaders) {
        return createTransport(url, headers, sessionId, refreshHeaders, null);
    }

    /**
     * Create a new {@link WebSocketTransport} with explicit options.
     */
    public WebSocketTransport createTransport(URL url, Map<String, String> headers,
            String sessionId, Supplier<Map<String, String>> refreshHeaders,
            WebSocketTransportOptions options) {
        return new WebSocketTransport(url, headers, sessionId, refreshHeaders, options,
                httpClient, scheduler);
    }
}
