package com.anthropic.claudecode.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Work-secret utilities: decode, build SDK URLs, compare session IDs,
 * and register a CCR v2 worker.
 *
 * Translated from src/bridge/workSecret.ts
 */
@Slf4j
@Component
public class BridgeWorkSecret {



    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // =========================================================================
    // WorkSecret record
    // =========================================================================

    /**
     * Decoded work secret payload.
     * Mirrors the {@code WorkSecret} type in bridge/types.ts
     */
    public record WorkSecret(
            int version,
            @JsonProperty("session_ingress_token") String sessionIngressToken,
            @JsonProperty("api_base_url") String apiBaseUrl
    ) {}

    // =========================================================================
    // decodeWorkSecret
    // =========================================================================

    /**
     * Decode a base64url-encoded work secret and validate its version.
     *
     * @param secret base64url-encoded JSON work secret
     * @return decoded {@link WorkSecret}
     * @throws IllegalArgumentException if the secret is invalid or the version
     *                                  is not supported
     * Translated from {@code decodeWorkSecret()} in workSecret.ts
     */
    public static WorkSecret decodeWorkSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Work secret must not be null or empty");
        }

        String json;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(secret);
            json = new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Work secret is not valid base64url: " + e.getMessage(), e);
        }

        Map<?, ?> parsed;
        try {
            parsed = OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Work secret JSON is malformed: " + e.getMessage(), e);
        }

        Object versionObj = parsed.get("version");
        if (!(versionObj instanceof Number) || ((Number) versionObj).intValue() != 1) {
            throw new IllegalArgumentException(
                    "Unsupported work secret version: " + (versionObj != null ? versionObj : "unknown"));
        }

        Object ingressToken = parsed.get("session_ingress_token");
        if (!(ingressToken instanceof String) || ((String) ingressToken).isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid work secret: missing or empty session_ingress_token");
        }

        Object apiBaseUrl = parsed.get("api_base_url");
        if (!(apiBaseUrl instanceof String)) {
            throw new IllegalArgumentException("Invalid work secret: missing api_base_url");
        }

        return new WorkSecret(1, (String) ingressToken, (String) apiBaseUrl);
    }

    // =========================================================================
    // buildSdkUrl
    // =========================================================================

    /**
     * Build a WebSocket SDK URL from the API base URL and session ID.
     *
     * <p>Uses {@code /v2/} for localhost (direct to session-ingress, no Envoy
     * rewrite) and {@code /v1/} for production (Envoy rewrites /v1/ → /v2/).</p>
     *
     * @param apiBaseUrl HTTP(S) base URL
     * @param sessionId  session UUID or tagged ID
     * @return {@code wss://host/v1/session_ingress/ws/{sessionId}} (or {@code ws://} for localhost)
     * Translated from {@code buildSdkUrl()} in workSecret.ts
     */
    public static String buildSdkUrl(String apiBaseUrl, String sessionId) {
        boolean isLocalhost = apiBaseUrl.contains("localhost") || apiBaseUrl.contains("127.0.0.1");
        String protocol = isLocalhost ? "ws" : "wss";
        String version = isLocalhost ? "v2" : "v1";
        String host = apiBaseUrl
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");
        return protocol + "://" + host + "/" + version + "/session_ingress/ws/" + sessionId;
    }

    // =========================================================================
    // sameSessionId
    // =========================================================================

    /**
     * Compare two session IDs regardless of their tagged-ID prefix.
     *
     * <p>Tagged IDs have the form {@code {tag}_{body}} or
     * {@code {tag}_staging_{body}}, where the body encodes a UUID. CCR v2's
     * compat layer returns {@code session_*} to v1 API clients but the
     * infrastructure layer uses {@code cse_*}. Both have the same underlying UUID.</p>
     *
     * @param a first session ID
     * @param b second session ID
     * @return true if both IDs refer to the same underlying session
     * Translated from {@code sameSessionId()} in workSecret.ts
     */
    public static boolean sameSessionId(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;

        // The body is everything after the last underscore — handles both
        // {tag}_{body} and {tag}_staging_{body}.
        String aBody = a.substring(a.lastIndexOf('_') + 1);
        String bBody = b.substring(b.lastIndexOf('_') + 1);

        // Guard against IDs with no underscore (bare UUIDs): lastIndexOf returns -1,
        // substring(0) returns the whole string, and we already checked a.equals(b).
        // Require a minimum length to avoid accidental matches on short suffixes.
        return aBody.length() >= 4 && aBody.equals(bBody);
    }

    // =========================================================================
    // buildCCRv2SdkUrl
    // =========================================================================

    /**
     * Build a CCR v2 session URL from the API base URL and session ID.
     *
     * <p>Unlike {@link #buildSdkUrl}, this returns an HTTP(S) URL (not ws://)
     * and points at {@code /v1/code/sessions/{id}} — the child CC will derive
     * the SSE stream path and worker endpoints from this base.</p>
     *
     * @param apiBaseUrl HTTP(S) base URL
     * @param sessionId  session UUID or tagged ID
     * @return {@code https://host/v1/code/sessions/{sessionId}}
     * Translated from {@code buildCCRv2SdkUrl()} in workSecret.ts
     */
    public static String buildCCRv2SdkUrl(String apiBaseUrl, String sessionId) {
        String base = apiBaseUrl.replaceAll("/+$", "");
        return base + "/v1/code/sessions/" + sessionId;
    }

    // =========================================================================
    // registerWorker
    // =========================================================================

    /**
     * Register this bridge as the worker for a CCR v2 session.
     *
     * <p>Returns the {@code worker_epoch}, which must be passed to the child CC
     * process so its CCRClient can include it in every
     * heartbeat/state/event request.</p>
     *
     * <p>Mirrors what environment-manager does in the container path
     * ({@code api-go/environment-manager/cmd/cmd_task_run.go RegisterWorker}).</p>
     *
     * @param sessionUrl  HTTP(S) URL of the CCR v2 session
     * @param accessToken bearer token with worker role
     * @return worker epoch as a {@link CompletableFuture}
     * Translated from {@code registerWorker()} in workSecret.ts
     */
    public static CompletableFuture<Long> registerWorker(String sessionUrl, String accessToken) {
        WebClient client = WebClient.builder().build();

        return client.post()
                .uri(sessionUrl + "/worker/register")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .bodyValue(Map.of())
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(10))
                .map(data -> {
                    // protojson serializes int64 as a string to avoid JS number
                    // precision loss; the Go side may also return a number.
                    Object raw = data.get("worker_epoch");
                    long epoch;
                    if (raw instanceof String s) {
                        epoch = Long.parseLong(s);
                    } else if (raw instanceof Number n) {
                        epoch = n.longValue();
                    } else {
                        throw new IllegalStateException(
                                "registerWorker: invalid worker_epoch in response: " + data);
                    }
                    return epoch;
                })
                .toFuture();
    }

    private BridgeWorkSecret() {}
}
