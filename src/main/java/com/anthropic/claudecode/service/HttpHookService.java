package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

/**
 * HTTP hook execution service.
 * Translated from src/utils/hooks/execHttpHook.ts
 *
 * Executes HTTP-based hooks that call external endpoints.
 */
@Slf4j
@Service
public class HttpHookService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HttpHookService.class);


    private static final long DEFAULT_HTTP_HOOK_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Autowired
    public HttpHookService(OkHttpClient httpClient,
                            ObjectMapper objectMapper,
                            SettingsService settingsService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    /**
     * Execute an HTTP hook.
     * Translated from execHttpHook() in execHttpHook.ts
     */
    public CompletableFuture<HttpHookResult> executeHttpHook(
            HttpHookConfig hookConfig,
            Map<String, Object> hookInput) {

        return CompletableFuture.supplyAsync(() -> {
            String url = hookConfig.getUrl();

            // Check if URL is in the allowlist
            if (!isUrlAllowed(url)) {
                log.warn("[http-hook] URL not in allowlist: {}", url);
                return new HttpHookResult(false, null, "URL not in allowlist: " + url, -1);
            }

            try {
                String jsonBody = objectMapper.writeValueAsString(hookInput);
                Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, JSON))
                    .header("Content-Type", "application/json")
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : null;
                    boolean success = response.isSuccessful();
                    return new HttpHookResult(success, body, null, response.code());
                }
            } catch (Exception e) {
                log.debug("[http-hook] Error executing hook {}: {}", url, e.getMessage());
                return new HttpHookResult(false, null, e.getMessage(), -1);
            }
        });
    }

    private boolean isUrlAllowed(String url) {
        Map<String, Object> settings = settingsService.getMergedSettings(null);
        Object allowedUrlsObj = settings.get("allowedHttpHookUrls");
        if (allowedUrlsObj == null) return true; // No restrictions

        List<String> allowedUrls = (List<String>) allowedUrlsObj;
        return allowedUrls.stream().anyMatch(allowed ->
            url.startsWith(allowed) || url.equals(allowed));
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HttpHookConfig {
        private String url;
        private Map<String, String> headers;
        private Integer timeoutMs;

        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> v) { headers = v; }
        public Integer getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(Integer v) { timeoutMs = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HttpHookResult {
        private boolean success;
        private String body;
        private String error;
        private int statusCode;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean v) { success = v; }
        public String getBody() { return body; }
        public void setBody(String v) { body = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int v) { statusCode = v; }
    }
}
