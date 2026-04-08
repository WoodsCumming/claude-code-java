package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Session history service for fetching remote session events from the CCR API.
 * Translated from src/assistant/sessionHistory.ts
 *
 * Provides paginated access to session events (SDKMessages) stored server-side,
 * used by the assistant's session resume and history display.
 */
@Slf4j
@Service
public class SessionHistoryService {



    public static final int HISTORY_PAGE_SIZE = 100;
    private static final String CCR_BYOC_BETA = "ccr-byoc-2025-07-29";
    private static final String BASE_API_URL = "https://api.anthropic.com";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OAuthService oauthService;

    @Autowired
    public SessionHistoryService(OkHttpClient httpClient,
                                  ObjectMapper objectMapper,
                                  OAuthService oauthService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    // =========================================================================
    // Auth context
    // =========================================================================

    /**
     * Build auth headers and base URL for history requests.
     * Reuse the returned context across pages for efficiency.
     * Translated from createHistoryAuthCtx() in sessionHistory.ts
     */
    public CompletableFuture<HistoryAuthCtx> createHistoryAuthCtx(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
            if (tokens == null) {
                throw new IllegalStateException("No OAuth tokens available");
            }
            // Derive org UUID from the token account if available
            String orgUUID = tokens.getTokenAccount() != null
                ? tokens.getTokenAccount().organizationUuid() : "";
            String baseUrl = BASE_API_URL
                + "/v1/sessions/" + sessionId + "/events";
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + tokens.getAccessToken());
            headers.put("anthropic-beta", CCR_BYOC_BETA);
            if (orgUUID != null && !orgUUID.isBlank()) {
                headers.put("x-organization-uuid", orgUUID);
            }
            return new HistoryAuthCtx(baseUrl, headers);
        });
    }

    // =========================================================================
    // Page fetching
    // =========================================================================

    /**
     * Fetch the newest page: last `limit` events, chronological order.
     * has_more=true means older events exist.
     * Translated from fetchLatestEvents() in sessionHistory.ts
     */
    public CompletableFuture<HistoryPage> fetchLatestEvents(HistoryAuthCtx ctx) {
        return fetchLatestEvents(ctx, HISTORY_PAGE_SIZE);
    }

    public CompletableFuture<HistoryPage> fetchLatestEvents(HistoryAuthCtx ctx, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", limit);
        params.put("anchor_to_latest", true);
        return fetchPage(ctx, params, "fetchLatestEvents");
    }

    /**
     * Fetch an older page: events immediately before the `beforeId` cursor.
     * Translated from fetchOlderEvents() in sessionHistory.ts
     */
    public CompletableFuture<HistoryPage> fetchOlderEvents(HistoryAuthCtx ctx, String beforeId) {
        return fetchOlderEvents(ctx, beforeId, HISTORY_PAGE_SIZE);
    }

    public CompletableFuture<HistoryPage> fetchOlderEvents(HistoryAuthCtx ctx,
                                                            String beforeId,
                                                            int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", limit);
        params.put("before_id", beforeId);
        return fetchPage(ctx, params, "fetchOlderEvents");
    }

    /**
     * Internal page fetch with configurable query params.
     * Translated from fetchPage() in sessionHistory.ts
     */
    private CompletableFuture<HistoryPage> fetchPage(HistoryAuthCtx ctx,
                                                       Map<String, Object> params,
                                                       String label) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpUrl.Builder urlBuilder = Objects.requireNonNull(
                    HttpUrl.parse(ctx.baseUrl())).newBuilder();
                for (Map.Entry<String, Object> p : params.entrySet()) {
                    urlBuilder.addQueryParameter(p.getKey(), String.valueOf(p.getValue()));
                }

                Request.Builder reqBuilder = new Request.Builder().url(urlBuilder.build()).get();
                for (Map.Entry<String, String> h : ctx.headers().entrySet()) {
                    reqBuilder.header(h.getKey(), h.getValue());
                }

                try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                    if (response.code() != 200) {
                        log.debug("[{}] HTTP {}", label, response.code());
                        return null;
                    }

                    ResponseBody body = response.body();
                    if (body == null) return null;

                    SessionEventsResponse data = objectMapper.readValue(
                        body.string(), SessionEventsResponse.class);

                    List<Map<String, Object>> events =
                        data.getData() != null ? data.getData() : List.of();

                    return new HistoryPage(events, data.getFirstId(), data.isHasMore());
                }
            } catch (Exception e) {
                log.debug("[{}] Error: {}", label, e.getMessage());
                return null;
            }
        });
    }

    // =========================================================================
    // Convenience: fetch all history pages
    // =========================================================================

    /**
     * Fetch all pages of session history in chronological order.
     * Starts from the latest page and walks backwards via firstId cursors.
     */
    public CompletableFuture<List<Map<String, Object>>> fetchAllHistory(String sessionId) {
        return createHistoryAuthCtx(sessionId).thenCompose(ctx ->
            CompletableFuture.supplyAsync(() -> {
                List<Map<String, Object>> allEvents = new ArrayList<>();

                try {
                    // Fetch newest page first
                    HistoryPage page = fetchLatestEvents(ctx).get();
                    if (page == null || page.getEvents().isEmpty()) return allEvents;

                    allEvents.addAll(page.getEvents());

                    // Walk backwards through older pages
                    while (page.isHasMore() && page.getFirstId() != null) {
                        String cursor = page.getFirstId();
                        page = fetchOlderEvents(ctx, cursor).get();
                        if (page == null || page.getEvents().isEmpty()) break;
                        allEvents.addAll(0, page.getEvents()); // prepend for chronological order
                    }
                } catch (Exception e) {
                    log.debug("[fetchAllHistory] Failed: {}", e.getMessage());
                }

                return allEvents;
            })
        );
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Auth context shared across history page requests.
     * Translated from HistoryAuthCtx in sessionHistory.ts
     */
    public record HistoryAuthCtx(String baseUrl, Map<String, String> headers) {}

    /**
     * One page of session events.
     * Translated from HistoryPage in sessionHistory.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HistoryPage {
        /** Events in chronological order within this page. */
        private List<Map<String, Object>> events;
        /** Oldest event ID in this page → before_id cursor for the next older page. */
        private String firstId;
        /** true = older events exist beyond this page. */
        private boolean hasMore;

        public List<Map<String, Object>> getEvents() { return events; }
        public void setEvents(List<Map<String, Object>> v) { events = v; }
        public String getFirstId() { return firstId; }
        public void setFirstId(String v) { firstId = v; }
        public boolean isHasMore() { return hasMore; }
        public void setHasMore(boolean v) { hasMore = v; }
    
    }

    /**
     * Wire type for the /sessions/{id}/events API response.
     * Translated from SessionEventsResponse in sessionHistory.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionEventsResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("data")
        private List<Map<String, Object>> data;
        @com.fasterxml.jackson.annotation.JsonProperty("has_more")
        private boolean hasMore;
        @com.fasterxml.jackson.annotation.JsonProperty("first_id")
        private String firstId;
        @com.fasterxml.jackson.annotation.JsonProperty("last_id")
        private String lastId;

        public List<Map<String, Object>> getData() { return data; }
        public void setData(List<Map<String, Object>> v) { data = v; }
        public String getLastId() { return lastId; }
        public void setLastId(String v) { lastId = v; }
    
        public String getFirstId() { return firstId; }
    
        public boolean isHasMore() { return hasMore; }
    }

    /**
     * Get the Nth latest assistant response text (1-indexed).
     * Returns Optional.empty() if not found.
     */
    public CompletableFuture<java.util.Optional<String>> getNthLatestAssistantResponse(int n) {
        // In a full implementation, this would query the message history.
        // Here we return empty as a safe default.
        return CompletableFuture.completedFuture(java.util.Optional.empty());
    }
}
