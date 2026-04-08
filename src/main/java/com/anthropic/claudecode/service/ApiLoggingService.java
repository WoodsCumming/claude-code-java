package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * API logging and analytics service.
 * Translated from src/services/api/logging.ts
 *
 * Logs API requests, responses, errors, and gateway fingerprinting for
 * analytics/debugging. Also detects known AI gateway reverse-proxies from
 * response headers or base-URL hostname suffixes.
 */
@Slf4j
@Service
public class ApiLoggingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiLoggingService.class);


    // -----------------------------------------------------------------------
    // Known AI gateway type
    // -----------------------------------------------------------------------

    public enum KnownGateway {
        LITELLM, HELICONE, PORTKEY, CLOUDFLARE_AI_GATEWAY, KONG, BRAINTRUST, DATABRICKS;

        /** Return lowercase kebab-case name for analytics (matches TS enum values). */
        public String analyticsName() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    // Gateway header-prefix fingerprints
    private static final Map<KnownGateway, List<String>> GATEWAY_FINGERPRINTS = Map.of(
        KnownGateway.LITELLM,               List.of("x-litellm-"),
        KnownGateway.HELICONE,              List.of("helicone-"),
        KnownGateway.PORTKEY,               List.of("x-portkey-"),
        KnownGateway.CLOUDFLARE_AI_GATEWAY, List.of("cf-aig-"),
        KnownGateway.KONG,                  List.of("x-kong-"),
        KnownGateway.BRAINTRUST,            List.of("x-bt-")
    );

    // Gateways identified by hosted domain suffix
    private static final Map<KnownGateway, List<String>> GATEWAY_HOST_SUFFIXES = Map.of(
        KnownGateway.DATABRICKS, List.of(
            ".cloud.databricks.com",
            ".azuredatabricks.net",
            ".gcp.databricks.com"
        )
    );

    private final ApiErrorUtilsService errorUtils;

    @Autowired
    public ApiLoggingService(ApiErrorUtilsService errorUtils) {
        this.errorUtils = errorUtils;
    }

    // -----------------------------------------------------------------------
    // logAPIQuery
    // -----------------------------------------------------------------------

    /**
     * Log the start of an API query.
     * Translated from logAPIQuery() in logging.ts
     */
    public void logApiQuery(ApiQueryParams params) {
        log.debug("[API:query] model={} messagesLen={} temp={} source={} betas={}",
            params.getModel(),
            params.getMessagesLength(),
            params.getTemperature(),
            params.getQuerySource(),
            params.getBetas());
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApiQueryParams {
        private String model;
        private int messagesLength;
        private double temperature;
        private List<String> betas;
        private String permissionMode;
        private String querySource;
        private String thinkingType;
        private String effortValue;
        private Boolean fastMode;
        private String previousRequestId;

        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public int getMessagesLength() { return messagesLength; }
        public void setMessagesLength(int v) { messagesLength = v; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double v) { temperature = v; }
        public List<String> getBetas() { return betas; }
        public void setBetas(List<String> v) { betas = v; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String v) { permissionMode = v; }
        public String getQuerySource() { return querySource; }
        public void setQuerySource(String v) { querySource = v; }
        public String getThinkingType() { return thinkingType; }
        public void setThinkingType(String v) { thinkingType = v; }
        public String getEffortValue() { return effortValue; }
        public void setEffortValue(String v) { effortValue = v; }
        public boolean isFastMode() { return fastMode; }
        public void setFastMode(Boolean v) { fastMode = v; }
        public String getPreviousRequestId() { return previousRequestId; }
        public void setPreviousRequestId(String v) { previousRequestId = v; }
    }

    // -----------------------------------------------------------------------
    // logAPIError
    // -----------------------------------------------------------------------

    /**
     * Log an API error event.
     * Translated from logAPIError() in logging.ts
     */
    public void logApiError(ApiErrorParams params) {
        // Detect gateway from response headers / base URL
        Optional<KnownGateway> gateway = detectGateway(params.getResponseHeaders(), null);

        // Extract connection error details for debug logging
        errorUtils.extractConnectionErrorDetails(params.getError()).ifPresent(cd -> {
            String sslLabel = cd.isSslError() ? " (SSL error)" : "";
            log.debug("Connection error details: code={}{}  message={}",
                cd.getCode(), sslLabel, cd.getMessage());
        });

        if (params.getClientRequestId() != null) {
            log.debug("API error x-client-request-id={} (for server-log lookup)",
                params.getClientRequestId());
        }

        String status = params.getError() instanceof AnthropicClient.ApiException apiEx
            ? String.valueOf(apiEx.getStatusCode()) : null;
        String errorMsg = params.getError() != null ? params.getError().getMessage() : "unknown";

        log.warn("[API:error] model={} status={} attempt={} dur={}ms retryDur={}ms error={} gateway={}",
            params.getModel(),
            status,
            params.getAttempt(),
            params.getDurationMs(),
            params.getDurationMsIncludingRetries(),
            errorMsg,
            gateway.map(KnownGateway::analyticsName).orElse(null));
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApiErrorParams {
        private Throwable error;
        private String model;
        private int messageCount;
        private Integer messageTokens;
        private long durationMs;
        private long durationMsIncludingRetries;
        private int attempt;
        private String requestId;
        private String clientRequestId;
        private Boolean didFallBackToNonStreaming;
        private String promptCategory;
        /** Raw response headers from the failed request, if available. */
        private Map<String, String> responseHeaders;
        private String querySource;
        private Boolean fastMode;
        private String previousRequestId;

        public Throwable getError() { return error; }
        public void setError(Throwable v) { error = v; }
        public int getMessageCount() { return messageCount; }
        public void setMessageCount(int v) { messageCount = v; }
        public Integer getMessageTokens() { return messageTokens; }
        public void setMessageTokens(Integer v) { messageTokens = v; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long v) { durationMs = v; }
        public long getDurationMsIncludingRetries() { return durationMsIncludingRetries; }
        public void setDurationMsIncludingRetries(long v) { durationMsIncludingRetries = v; }
        public int getAttempt() { return attempt; }
        public void setAttempt(int v) { attempt = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public String getClientRequestId() { return clientRequestId; }
        public void setClientRequestId(String v) { clientRequestId = v; }
        public boolean isDidFallBackToNonStreaming() { return didFallBackToNonStreaming; }
        public void setDidFallBackToNonStreaming(Boolean v) { didFallBackToNonStreaming = v; }
        public String getPromptCategory() { return promptCategory; }
        public void setPromptCategory(String v) { promptCategory = v; }
        public Map<String, String> getResponseHeaders() { return responseHeaders; }
        public void setResponseHeaders(Map<String, String> v) { responseHeaders = v; }
    }

    // -----------------------------------------------------------------------
    // logAPISuccess
    // -----------------------------------------------------------------------

    /**
     * Log a successful API response together with timing and token usage.
     * Translated from logAPISuccessAndDuration() / logAPISuccess() in logging.ts
     */
    public void logApiSuccess(ApiSuccessParams params) {
        Optional<KnownGateway> gateway = detectGateway(params.getResponseHeaders(),
            System.getenv("ANTHROPIC_BASE_URL"));

        log.debug("[API:success] model={} in={} out={} cacheRead={} cacheCreate={} "
            + "dur={}ms retryDur={}ms attempt={} stop={} cost={} source={} gateway={}",
            params.getModel(),
            params.getInputTokens(),
            params.getOutputTokens(),
            params.getCacheReadTokens(),
            params.getCacheCreationTokens(),
            params.getDurationMs(),
            params.getDurationMsIncludingRetries(),
            params.getAttempt(),
            params.getStopReason(),
            params.getCostUsd(),
            params.getQuerySource(),
            gateway.map(KnownGateway::analyticsName).orElse(null));
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApiSuccessParams {
        private String model;
        private String preNormalizedModel;
        private long start;
        private long startIncludingRetries;
        private Long ttftMs;
        private int inputTokens;
        private int outputTokens;
        private int cacheReadTokens;
        private int cacheCreationTokens;
        private long durationMs;
        private long durationMsIncludingRetries;
        private int attempt;
        private String requestId;
        private String stopReason;
        private double costUsd;
        private boolean didFallBackToNonStreaming;
        private String querySource;
        private Map<String, String> responseHeaders;
        private List<String> betas;
        private Boolean fastMode;
        private String previousRequestId;
        private String permissionMode;
        private String globalCacheStrategy;
    }

    // -----------------------------------------------------------------------
    // Gateway detection
    // -----------------------------------------------------------------------

    /**
     * Detect whether requests are flowing through a known AI gateway reverse-proxy.
     * Translated from detectGateway() in logging.ts
     */
    public Optional<KnownGateway> detectGateway(
            Map<String, String> headers,
            String baseUrl) {

        if (headers != null) {
            // Header names should be lower-case; guard with toLowerCase just in case
            Set<String> headerNames = new HashSet<>();
            headers.keySet().forEach(k -> headerNames.add(k.toLowerCase(Locale.ROOT)));

            for (Map.Entry<KnownGateway, List<String>> entry : GATEWAY_FINGERPRINTS.entrySet()) {
                for (String prefix : entry.getValue()) {
                    if (headerNames.stream().anyMatch(h -> h.startsWith(prefix))) {
                        return Optional.of(entry.getKey());
                    }
                }
            }
        }

        if (baseUrl != null && !baseUrl.isBlank()) {
            try {
                String host = new java.net.URL(baseUrl).getHost().toLowerCase(Locale.ROOT);
                for (Map.Entry<KnownGateway, List<String>> entry : GATEWAY_HOST_SUFFIXES.entrySet()) {
                    for (String suffix : entry.getValue()) {
                        if (host.endsWith(suffix)) return Optional.of(entry.getKey());
                    }
                }
            } catch (java.net.MalformedURLException ignored) { /* malformed URL — ignore */ }
        }

        return Optional.empty();
    }
}
