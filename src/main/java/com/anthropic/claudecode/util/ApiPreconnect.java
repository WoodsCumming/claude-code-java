package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * API preconnection utility.
 * Translated from src/utils/apiPreconnect.ts
 *
 * Preconnects to the Anthropic API to overlap TCP+TLS handshake with startup.
 */
@Slf4j
public class ApiPreconnect {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiPreconnect.class);


    private static final AtomicBoolean fired = new AtomicBoolean(false);

    /**
     * Preconnect to the Anthropic API.
     * Translated from preconnectAnthropicApi() in apiPreconnect.ts
     */
    public static void preconnectAnthropicApi() {
        if (!fired.compareAndSet(false, true)) return;

        // Skip if using cloud provider
        if (EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))
            || EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"))) {
            return;
        }

        // Skip if using proxy or unix socket
        if (System.getenv("HTTPS_PROXY") != null
            || System.getenv("ANTHROPIC_UNIX_SOCKET") != null) {
            return;
        }

        // Fire-and-forget preconnect
        Thread.ofVirtual().start(() -> {
            try {
                String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
                if (baseUrl == null) baseUrl = "https://api.anthropic.com";

                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

                // Send a HEAD request to warm the connection
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

                client.send(request, HttpResponse.BodyHandlers.discarding());
                log.debug("API preconnect successful");
            } catch (Exception e) {
                // Ignore preconnect failures - they're best-effort
                log.debug("API preconnect failed: {}", e.getMessage());
            }
        });
    }

    private ApiPreconnect() {}
}
