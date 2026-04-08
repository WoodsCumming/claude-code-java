package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * API preconnection service.
 * Translated from src/utils/apiPreconnect.ts
 *
 * Preconnects to the Anthropic API to overlap TCP+TLS handshake with startup.
 *
 * The TCP+TLS handshake (~100-200ms) normally blocks the first API call.
 * Firing a HEAD request during init allows the handshake to happen in parallel
 * with action-handler work before the first real API request.
 *
 * Skipped when:
 * - proxy/mTLS/unix socket configured (preconnect would use wrong transport)
 * - Bedrock/Vertex/Foundry (different endpoints, different auth)
 */
@Slf4j
@Service
public class ApiPreconnectService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiPreconnectService.class);


    private static final AtomicBoolean fired = new AtomicBoolean(false);

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    /**
     * Preconnect to the Anthropic API.
     * Translated from preconnectAnthropicApi() in apiPreconnect.ts
     *
     * Fire-and-forget: launches a virtual thread to warm the connection pool.
     * Should be called AFTER CA certificates and proxy settings are applied
     * so that settings.json env vars are visible.
     */
    public void preconnectAnthropicApi() {
        if (!fired.compareAndSet(false, true)) {
            return;
        }

        // Skip if using a cloud provider — different endpoint + auth
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))
                || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))
                || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"))) {
            log.debug("Skipping API preconnect: cloud provider configured");
            return;
        }

        // Skip if proxy/mTLS/unix socket — SDK's custom dispatcher won't reuse this pool
        if (System.getenv("HTTPS_PROXY") != null
                || System.getenv("https_proxy") != null
                || System.getenv("HTTP_PROXY") != null
                || System.getenv("http_proxy") != null
                || System.getenv("ANTHROPIC_UNIX_SOCKET") != null
                || System.getenv("CLAUDE_CODE_CLIENT_CERT") != null
                || System.getenv("CLAUDE_CODE_CLIENT_KEY") != null) {
            log.debug("Skipping API preconnect: proxy/mTLS/unix socket configured");
            return;
        }

        // Use configured base URL (staging, local, or custom gateway)
        String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        final String resolvedBaseUrl = baseUrl;

        // Fire and forget via virtual thread. HEAD means no response body —
        // the connection is eligible for keep-alive pool reuse immediately after
        // headers arrive. 10s timeout so a slow network doesn't hang the process;
        // abort is fine since the real request will handshake fresh if needed.
        Thread.ofVirtual().name("api-preconnect").start(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(resolvedBaseUrl))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(10))
                        .build();

                client.send(request, HttpResponse.BodyHandlers.discarding());
                log.debug("API preconnect successful to {}", resolvedBaseUrl);
            } catch (Exception e) {
                // Ignore preconnect failures — best-effort warm-up
                log.debug("API preconnect failed (this is normal): {}", e.getMessage());
            }
        });
    }

    /**
     * Reset the fired flag (for testing purposes only).
     */
    static void resetForTesting() {
        fired.set(false);
    }
}
