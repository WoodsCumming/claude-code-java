package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Anthropic voice_stream speech-to-text client for push-to-talk.
 * Translated from src/services/voiceStreamSTT.ts
 *
 * <p>Connects to Anthropic's {@code voice_stream} WebSocket endpoint using
 * OAuth Bearer credentials. The wire protocol uses JSON control messages
 * (KeepAlive, CloseStream) and binary audio frames. The server responds with
 * {@code TranscriptText} and {@code TranscriptEndpoint} JSON messages.</p>
 *
 * <p>ANT-ONLY: Only reachable in ant builds gated by the VOICE_MODE feature.</p>
 */
@Slf4j
@Service
public class VoiceStreamSTTService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VoiceStreamSTTService.class);


    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String VOICE_STREAM_PATH = "/api/ws/speech_to_text/voice_stream";
    private static final long KEEPALIVE_INTERVAL_MS = 8_000;

    private static final String KEEPALIVE_MSG = "{\"type\":\"KeepAlive\"}";
    private static final String CLOSE_STREAM_MSG = "{\"type\":\"CloseStream\"}";

    /** Exported finalize() resolution timeouts (ms). Matches TS FINALIZE_TIMEOUTS_MS. */
    public static final long FINALIZE_SAFETY_MS = 5_000;
    public static final long FINALIZE_NO_DATA_MS = 1_500;

    // ── Types ─────────────────────────────────────────────────────────────────

    /**
     * How finalize() resolved.
     * Translated from FinalizeSource in voiceStreamSTT.ts
     */
    public enum FinalizeSource {
        POST_CLOSESTREAM_ENDPOINT,
        NO_DATA_TIMEOUT,
        SAFETY_TIMEOUT,
        WS_CLOSE,
        WS_ALREADY_CLOSED
    }

    /**
     * Callbacks supplied by the caller when connecting.
     * Translated from VoiceStreamCallbacks in voiceStreamSTT.ts
     */
    public interface VoiceStreamCallbacks {
        /** Called with each transcript fragment. {@code isFinal=true} on commit. */
        void onTranscript(String text, boolean isFinal);
        /** Called on non-fatal errors ({@code fatal=true} when retry is futile). */
        void onError(String error, boolean fatal);
        /** Called when the WebSocket connection closes. */
        void onClose();
        /** Called when the WebSocket opens and the connection is ready for audio. */
        void onReady(VoiceStreamConnection connection);
    }

    /**
     * Handle to an open voice stream connection.
     * Translated from VoiceStreamConnection in voiceStreamSTT.ts
     */
    public interface VoiceStreamConnection {
        /** Send a raw PCM audio chunk (16 kHz / 16-bit signed / mono). */
        void send(byte[] audioChunk);
        /** Flush remaining audio and wait for the final transcript. */
        CompletableFuture<FinalizeSource> finalizeStream();
        /** Close the connection immediately. */
        void close();
        /** Return {@code true} when the WebSocket is open. */
        boolean isConnected();
    }

    // ── Availability ─────────────────────────────────────────────────────────

    /**
     * Return {@code true} when OAuth tokens are present and valid.
     * Translated from isVoiceStreamAvailable() in voiceStreamSTT.ts
     */
    public boolean isVoiceStreamAvailable() {
        // In a real implementation, this would check OAuthService for a valid token.
        String token = System.getenv("ANTHROPIC_ACCESS_TOKEN");
        return token != null && !token.isBlank();
    }

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * Connect to the voice_stream WebSocket endpoint.
     * Translated from connectVoiceStream() in voiceStreamSTT.ts
     *
     * @param callbacks event callbacks (onTranscript, onError, onClose, onReady)
     * @param language  BCP-47 language code (default "en")
     * @param keyterms  optional list of keyterms for STT boosting
     * @return a {@link VoiceStreamConnection} future, or a failed future on auth error
     */
    public CompletableFuture<VoiceStreamConnection> connectVoiceStream(
            VoiceStreamCallbacks callbacks,
            String language,
            List<String> keyterms) {

        String accessToken = System.getenv("ANTHROPIC_ACCESS_TOKEN");
        if (accessToken == null || accessToken.isBlank()) {
            log.debug("[voice_stream] No OAuth token available");
            return CompletableFuture.failedFuture(new IllegalStateException("No OAuth token available"));
        }

        String wsBaseUrl = Optional.ofNullable(System.getenv("VOICE_STREAM_BASE_URL"))
                .orElse("wss://api.anthropic.com");

        // Build query parameters
        StringJoiner params = new StringJoiner("&");
        params.add("encoding=linear16");
        params.add("sample_rate=16000");
        params.add("channels=1");
        params.add("endpointing_ms=300");
        params.add("utterance_end_ms=1000");
        params.add("language=" + (language != null ? language : "en"));

        if (keyterms != null) {
            for (String term : keyterms) {
                params.add("keyterms=" + term);
            }
        }

        String url = wsBaseUrl + VOICE_STREAM_PATH + "?" + params;
        log.debug("[voice_stream] Connecting to {}", url);

        return buildConnection(url, accessToken, callbacks);
    }

    /** Convenience overload using defaults. */
    public CompletableFuture<VoiceStreamConnection> connectVoiceStream(VoiceStreamCallbacks callbacks) {
        return connectVoiceStream(callbacks, "en", null);
    }

    // ── Internal WebSocket connection builder ─────────────────────────────────

    private CompletableFuture<VoiceStreamConnection> buildConnection(
            String url,
            String accessToken,
            VoiceStreamCallbacks callbacks) {

        CompletableFuture<VoiceStreamConnection> resultFuture = new CompletableFuture<>();

        AtomicBoolean connected = new AtomicBoolean(false);
        AtomicBoolean finalized = new AtomicBoolean(false);
        AtomicBoolean finalizing = new AtomicBoolean(false);
        AtomicBoolean upgradeRejected = new AtomicBoolean(false);

        // lastTranscriptText accumulates the current segment
        final String[] lastTranscriptText = {""};

        // finalize() resolve handle
        final CompletableFuture<FinalizeSource>[] finalizeFuture = new CompletableFuture[]{null};
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voice-stream-scheduler");
            t.setDaemon(true);
            return t;
        });

        // keepalive timer handle
        final ScheduledFuture<?>[] keepaliveTimer = {null};

        HttpClient httpClient = HttpClient.newBuilder().build();

        // Build connection object first so it can be passed to onReady
        VoiceStreamConnection[] connectionHolder = {null};

        WebSocket.Listener listener = new WebSocket.Listener() {

            private final StringBuilder textBuffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                log.debug("[voice_stream] WebSocket connected");
                connected.set(true);
                webSocket.sendText(KEEPALIVE_MSG, true);
                log.debug("[voice_stream] Sending initial KeepAlive");

                keepaliveTimer[0] = scheduler.scheduleAtFixedRate(() -> {
                    if (connected.get()) {
                        log.debug("[voice_stream] Sending periodic KeepAlive");
                        webSocket.sendText(KEEPALIVE_MSG, true);
                    }
                }, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);

                callbacks.onReady(connectionHolder[0]);
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                textBuffer.append(data);
                if (last) {
                    String text = textBuffer.toString();
                    textBuffer.setLength(0);
                    handleMessage(text, webSocket);
                }
                webSocket.request(1);
                return null;
            }

            private void handleMessage(String raw, WebSocket webSocket) {
                log.debug("[voice_stream] Message received ({} chars): {}",
                        raw.length(), raw.substring(0, Math.min(raw.length(), 200)));

                // Simple JSON field extraction without a full parser dependency
                String type = extractJsonStringField(raw, "type");
                if (type == null) return;

                switch (type) {
                    case "TranscriptText" -> {
                        String transcript = extractJsonStringField(raw, "data");
                        log.debug("[voice_stream] TranscriptText: \"{}\"", transcript);
                        if (finalized.get()) {
                            // disarm no-data timer — real data arrived
                            cancelNoDataTimer();
                        }
                        if (transcript != null && !transcript.isEmpty()) {
                            // Auto-finalize previous segment when new non-cumulative text arrives
                            String prev = lastTranscriptText[0].stripLeading();
                            String next = transcript.stripLeading();
                            if (!prev.isEmpty() && !next.isEmpty()
                                    && !next.startsWith(prev) && !prev.startsWith(next)) {
                                log.debug("[voice_stream] Auto-finalizing previous segment: \"{}\"",
                                        lastTranscriptText[0]);
                                callbacks.onTranscript(lastTranscriptText[0], true);
                            }
                            lastTranscriptText[0] = transcript;
                            callbacks.onTranscript(transcript, false);
                        }
                    }
                    case "TranscriptEndpoint" -> {
                        log.debug("[voice_stream] TranscriptEndpoint, last=\"{}\"", lastTranscriptText[0]);
                        String finalText = lastTranscriptText[0];
                        lastTranscriptText[0] = "";
                        if (!finalText.isEmpty()) {
                            callbacks.onTranscript(finalText, true);
                        }
                        if (finalized.get()) {
                            resolveFinalize(FinalizeSource.POST_CLOSESTREAM_ENDPOINT);
                        }
                    }
                    case "TranscriptError" -> {
                        String desc = Optional.ofNullable(extractJsonStringField(raw, "description"))
                                .or(() -> Optional.ofNullable(extractJsonStringField(raw, "error_code")))
                                .orElse("unknown transcription error");
                        log.debug("[voice_stream] TranscriptError: {}", desc);
                        if (!finalizing.get()) callbacks.onError(desc, false);
                    }
                    case "error" -> {
                        String msg = Optional.ofNullable(extractJsonStringField(raw, "message")).orElse(raw);
                        log.debug("[voice_stream] Server error: {}", msg);
                        if (!finalizing.get()) callbacks.onError(msg, false);
                    }
                    default -> {}
                }
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.debug("[voice_stream] WebSocket closed: code={} reason=\"{}\"", statusCode, reason);
                connected.set(false);
                cancelKeepalive();

                if (lastTranscriptText[0] != null && !lastTranscriptText[0].isEmpty()) {
                    log.debug("[voice_stream] Promoting unreported interim transcript to final on close");
                    String finalText = lastTranscriptText[0];
                    lastTranscriptText[0] = "";
                    callbacks.onTranscript(finalText, true);
                }

                resolveFinalize(FinalizeSource.WS_CLOSE);

                if (!finalizing.get() && !upgradeRejected.get()
                        && statusCode != 1000 && statusCode != 1005) {
                    callbacks.onError(
                            "Connection closed: code " + statusCode
                                    + (reason != null && !reason.isEmpty() ? " — " + reason : ""),
                            false);
                }
                callbacks.onClose();
                scheduler.shutdown();
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.error("[voice_stream] WebSocket error: {}", error.getMessage(), error);
                if (!finalizing.get()) {
                    callbacks.onError("Voice stream connection error: " + error.getMessage(), false);
                }
            }

            // ── Timer helpers ─────────────────────────────────────────────────

            private ScheduledFuture<?>[] noDataTimerHolder = {null};

            private void cancelNoDataTimer() {
                if (noDataTimerHolder[0] != null) {
                    noDataTimerHolder[0].cancel(false);
                    noDataTimerHolder[0] = null;
                }
            }

            private void cancelKeepalive() {
                if (keepaliveTimer[0] != null) {
                    keepaliveTimer[0].cancel(false);
                    keepaliveTimer[0] = null;
                }
            }

            private void resolveFinalize(FinalizeSource source) {
                if (finalizeFuture[0] != null && !finalizeFuture[0].isDone()) {
                    if (lastTranscriptText[0] != null && !lastTranscriptText[0].isEmpty()) {
                        log.debug("[voice_stream] Promoting unreported interim before {} resolve", source);
                        String t = lastTranscriptText[0];
                        lastTranscriptText[0] = "";
                        callbacks.onTranscript(t, true);
                    }
                    log.debug("[voice_stream] Finalize resolved via {}", source);
                    finalizeFuture[0].complete(source);
                }
            }
        };

        WebSocket[] wsHolder = {null};

        connectionHolder[0] = new VoiceStreamConnection() {
            @Override
            public void send(byte[] audioChunk) {
                WebSocket ws = wsHolder[0];
                if (ws == null || !connected.get()) return;
                if (finalized.get()) {
                    log.debug("[voice_stream] Dropping audio chunk after CloseStream: {} bytes", audioChunk.length);
                    return;
                }
                log.debug("[voice_stream] Sending audio chunk: {} bytes", audioChunk.length);
                ws.sendBinary(ByteBuffer.wrap(audioChunk), true);
            }

            @Override
            public CompletableFuture<FinalizeSource> finalizeStream() {
                if (finalizing.get() || finalized.get()) {
                    return CompletableFuture.completedFuture(FinalizeSource.WS_ALREADY_CLOSED);
                }
                finalizing.set(true);

                CompletableFuture<FinalizeSource> future = new CompletableFuture<>();
                finalizeFuture[0] = future;

                // Safety timeout
                scheduler.schedule(() -> {
                    if (!future.isDone()) {
                        future.complete(FinalizeSource.SAFETY_TIMEOUT);
                        log.debug("[voice_stream] Finalize resolved via SAFETY_TIMEOUT");
                    }
                }, FINALIZE_SAFETY_MS, TimeUnit.MILLISECONDS);

                // No-data timeout
                scheduler.schedule(() -> {
                    if (!future.isDone()) {
                        future.complete(FinalizeSource.NO_DATA_TIMEOUT);
                        log.debug("[voice_stream] Finalize resolved via NO_DATA_TIMEOUT");
                    }
                }, FINALIZE_NO_DATA_MS, TimeUnit.MILLISECONDS);

                WebSocket ws = wsHolder[0];
                if (ws == null) {
                    future.complete(FinalizeSource.WS_ALREADY_CLOSED);
                    return future;
                }

                // Defer CloseStream to flush any queued audio callbacks
                CompletableFuture.runAsync(() -> {
                    finalized.set(true);
                    if (connected.get()) {
                        log.debug("[voice_stream] Sending CloseStream (finalize)");
                        ws.sendText(CLOSE_STREAM_MSG, true);
                    }
                });

                return future;
            }

            @Override
            public void close() {
                finalized.set(true);
                cancelKeepalive();
                connected.set(false);
                WebSocket ws = wsHolder[0];
                if (ws != null) {
                    ws.abort();
                }
            }

            @Override
            public boolean isConnected() {
                return connected.get();
            }

            private void cancelKeepalive() {
                if (keepaliveTimer[0] != null) {
                    keepaliveTimer[0].cancel(false);
                    keepaliveTimer[0] = null;
                }
            }
        };

        httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + accessToken)
                .header("x-app", "cli")
                .buildAsync(URI.create(url), listener)
                .thenAccept(ws -> {
                    wsHolder[0] = ws;
                    resultFuture.complete(connectionHolder[0]);
                })
                .exceptionally(e -> {
                    resultFuture.completeExceptionally(e);
                    return null;
                });

        return resultFuture;
    }

    // ── Minimal JSON field extractor ──────────────────────────────────────────

    /**
     * Extract a top-level string field from a JSON object without a full parser.
     */
    private static String extractJsonStringField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Whether voice mode is enabled (feature gate + auth check). */
    public boolean isVoiceModeEnabled() {
        return isVoiceStreamAvailable();
    }

    /** Whether Anthropic auth is enabled (user has a valid auth token). */
    public boolean isAnthropicAuthEnabled() {
        return isVoiceStreamAvailable();
    }

    /** Normalize a language code for STT. Returns "en" for unsupported languages. */
    public String normalizeLanguageForSTT(String language) {
        if (language == null || language.isBlank()) return "en";
        // Map BCP-47 codes to supported STT codes
        return language.split("-")[0]; // simplification
    }

    /** Get the language that was "fell back from" (if any). Returns null if no fallback. */
    public String getFellBackFrom(String language) {
        return null; // In a full implementation, track unsupported language fallback
    }
}
