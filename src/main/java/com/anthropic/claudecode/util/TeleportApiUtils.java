package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Teleport API utilities.
 * Translated from src/utils/teleport/api.ts
 *
 * Utilities for making API requests with retry logic.
 */
@Slf4j
public class TeleportApiUtils {



    public static final String CCR_BYOC_BETA = "ccr-byoc-2025-07-29";

    private static final long[] RETRY_DELAYS = {2_000, 4_000, 8_000, 16_000};

    /**
     * Check if an error is a transient network error.
     * Translated from isTransientNetworkError() in api.ts
     */
    public static boolean isTransientNetworkError(Throwable error) {
        if (error == null) return false;
        String message = error.getMessage();
        if (message == null) return true; // No response = network error

        // Check for 5xx errors
        if (message.contains("HTTP 5") || message.contains("500") || message.contains("503")) {
            return true;
        }

        // Check for connection errors
        if (error instanceof java.net.ConnectException
            || error instanceof java.net.SocketTimeoutException) {
            return true;
        }

        return false;
    }

    /**
     * Make an HTTP GET request with retry.
     * Translated from axiosGetWithRetry() in api.ts
     */
    public static CompletableFuture<HttpResponse<String>> getWithRetry(
            String url,
            Map<String, String> headers) {

        return CompletableFuture.supplyAsync(() -> {
            Throwable lastError = null;

            for (int attempt = 0; attempt <= RETRY_DELAYS.length; attempt++) {
                try {
                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET();

                    if (headers != null) {
                        headers.forEach(builder::header);
                    }

                    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

                } catch (Exception e) {
                    lastError = e;

                    if (!isTransientNetworkError(e) || attempt >= RETRY_DELAYS.length) {
                        break;
                    }

                    try {
                        Thread.sleep(RETRY_DELAYS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            throw new RuntimeException("Request failed after retries: " + url, lastError);
        });
    }

    /**
     * Get OAuth headers.
     * Translated from getOAuthHeaders() in api.ts
     */
    public static Map<String, String> getOAuthHeaders(String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    /**
     * Prepare an API request.
     * Translated from prepareApiRequest() in api.ts
     */
    public static ApiRequestContext prepareApiRequest(String accessToken, String orgUUID) {
        return new ApiRequestContext(accessToken, orgUUID);
    }

    public record ApiRequestContext(String accessToken, String orgUUID) {}

    private TeleportApiUtils() {}
}
