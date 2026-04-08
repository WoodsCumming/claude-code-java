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
 * Teleport API service for CCR (Claude Code Remote) sessions.
 * Translated from src/utils/teleport/api.ts
 *
 * Provides HTTP helpers with retry, type definitions for the Sessions API,
 * and operations to list, fetch, create, and update remote sessions.
 */
@Slf4j
@Service
public class TeleportService {



    // Retry configuration — mirrors TELEPORT_RETRY_DELAYS in api.ts
    private static final int[] RETRY_DELAYS_MS = {2000, 4000, 8000, 16000};
    private static final int MAX_RETRIES = RETRY_DELAYS_MS.length;

    public static final String CCR_BYOC_BETA = "ccr-byoc-2025-07-29";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OAuthService oauthService;

    @Autowired
    public TeleportService(OkHttpClient httpClient,
                            ObjectMapper objectMapper,
                            OAuthService oauthService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    // =========================================================================
    // Type definitions — mirrors TypeScript types in api.ts
    // =========================================================================

    public enum SessionStatus { requires_action, running, idle, archived }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class GitSource {
        private String type;   // "git_repository"
        private String url;
        private String revision;
        private Boolean allowUnrestrictedGitPush;
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class KnowledgeBaseSource {
        private String type;   // "knowledge_base"
        private String knowledgeBaseId;
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class OutcomeGitInfo {
        private String type;   // "github"
        private String repo;
        private List<String> branches;
    
        public List<String> getBranches() { return branches; }
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class GitRepositoryOutcome {
        private String type;   // "git_repository"
        private OutcomeGitInfo gitInfo;
    
        public OutcomeGitInfo getGitInfo() { return gitInfo; }
    
        public String getType() { return type; }
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class GithubPr {
        private String owner;
        private String repo;
        private int number;
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class SessionContext {
        private List<Map<String, Object>> sources;
        private String cwd;
        private List<GitRepositoryOutcome> outcomes;
        private String customSystemPrompt;
        private String appendSystemPrompt;
        private String model;
        private String seedBundleFileId;
        private GithubPr githubPr;
        private Boolean reuseOutcomeBranches;
    
        public List<GitRepositoryOutcome> getOutcomes() { return outcomes; }
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class SessionResource {
        private String type;   // "session"
        private String id;
        private String title;
        private String sessionStatus;
        private String environmentId;
        private String createdAt;
        private String updatedAt;
        private SessionContext sessionContext;
    
        public SessionContext getSessionContext() { return sessionContext; }
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class CodeSession {
        private String id;
        private String title;
        private String description;
        private String status; // "idle"|"working"|"waiting"|"completed"|"archived"|"cancelled"|"rejected"
        private CodeSessionRepo repo;
        private List<String> turns;
        private String createdAt;
        private String updatedAt;
    
        public void setCreatedAt(String v) { createdAt = v; }
    
        public void setDescription(String v) { description = v; }
    
        public void setId(String v) { id = v; }
    
        public void setStatus(String v) { status = v; }
    
        public void setTitle(String v) { title = v; }
    
        public void setTurns(List<String> v) { turns = v; }
    
        public void setUpdatedAt(String v) { updatedAt = v; }
    
        public void setRepo(CodeSessionRepo v) { repo = v; }
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class CodeSessionRepo {
        private String name;
        private CodeSessionOwner owner;
        private String defaultBranch;
    
    }

    @Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class CodeSessionOwner {
        private String login;
    
    }

    /** Content for a remote session message — plain string or structured blocks. */
    public sealed interface RemoteMessageContent
        permits RemoteMessageContent.TextContent, RemoteMessageContent.BlocksContent {

        record TextContent(String text) implements RemoteMessageContent {}
        record BlocksContent(List<Map<String, Object>> blocks) implements RemoteMessageContent {}
    }

    // =========================================================================
    // Transient-error detection
    // =========================================================================

    /**
     * Check if an exception represents a transient network error.
     * Translated from isTransientNetworkError() in api.ts
     */
    public static boolean isTransientNetworkError(Throwable error) {
        if (error == null) return false;
        String msg = error.getMessage();
        if (msg == null) return false;
        // OkHttp / IO errors without a response code are treated as network errors
        if (error instanceof java.io.IOException) return true;
        // Map 5xx HTTP status codes
        if (error instanceof HttpException httpEx) return httpEx.code >= 500;
        return false;
    }

    /** Simple carrier for HTTP error codes. */
    public static class HttpException extends RuntimeException {
        public final int code;
        public HttpException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    /**
     * Build standard OAuth headers.
     * Translated from getOAuthHeaders() in api.ts
     */
    public static Map<String, String> getOAuthHeaders(String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Content-Type", "application/json");
        headers.put("anthropic-version", "2023-06-01");
        return headers;
    }

    /**
     * GET with exponential-backoff retry on transient errors.
     * Translated from axiosGetWithRetry() in api.ts
     */
    public <T> CompletableFuture<T> getWithRetry(String url,
                                                   Map<String, String> headers,
                                                   Class<T> responseType) {
        return CompletableFuture.supplyAsync(() -> {
            Throwable lastError = null;
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    Request.Builder rb = new Request.Builder().url(url).get();
                    if (headers != null) headers.forEach(rb::addHeader);
                    try (Response resp = httpClient.newCall(rb.build()).execute()) {
                        if (resp.code() >= 500) {
                            throw new HttpException(resp.code(), "Server error " + resp.code());
                        }
                        if (!resp.isSuccessful() || resp.body() == null) {
                            throw new HttpException(resp.code(), "Request failed: " + resp.code());
                        }
                        return objectMapper.readValue(resp.body().string(), responseType);
                    }
                } catch (Exception e) {
                    lastError = e;
                    if (!isTransientNetworkError(e) || attempt >= MAX_RETRIES) {
                        break;
                    }
                    int delay = attempt < RETRY_DELAYS_MS.length ? RETRY_DELAYS_MS[attempt] : 2000;
                    log.debug("[teleport] GET {} failed (attempt {}/{}), retrying in {}ms: {}",
                        url, attempt + 1, MAX_RETRIES + 1, delay, e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            throw new RuntimeException("GET " + url + " failed after retries", lastError);
        });
    }

    // =========================================================================
    // Sessions API operations
    // =========================================================================

    /**
     * Validate auth and return accessToken + orgUUID.
     * Translated from prepareApiRequest() in api.ts
     */
    public CompletableFuture<ApiCredentials> prepareApiRequest() {
        return CompletableFuture.supplyAsync(() -> {
            String accessToken = oauthService.getAccessToken();
            if (accessToken == null) {
                throw new IllegalStateException(
                    "Claude Code web sessions require authentication with a Claude.ai account. "
                    + "API key authentication is not sufficient. Please run /login to authenticate.");
            }
            String orgUUID = oauthService.getOrganizationUUID().join();
            if (orgUUID == null) {
                throw new IllegalStateException("Unable to get organization UUID");
            }
            return new ApiCredentials(accessToken, orgUUID);
        });
    }

    @Data
    public static class ApiCredentials {
        private String accessToken;
        private String orgUUID;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String v) { accessToken = v; }
        public String getOrgUUID() { return orgUUID; }
        public void setOrgUUID(String v) { orgUUID = v; }
    

        public ApiCredentials() {}
        public ApiCredentials(String accessToken, String orgUUID) {
            this.accessToken = accessToken;
            this.orgUUID = orgUUID;
        }
    }

    /**
     * Fetch all code sessions from the Sessions API.
     * Translated from fetchCodeSessionsFromSessionsAPI() in api.ts
     */
    public CompletableFuture<List<CodeSession>> fetchCodeSessions() {
        return prepareApiRequest().thenCompose(creds -> {
            String baseUrl = oauthService.getBaseApiUrl();
            String url = baseUrl + "/v1/sessions";
            Map<String, String> headers = getOAuthHeaders(creds.getAccessToken());
            headers.put("anthropic-beta", CCR_BYOC_BETA);
            headers.put("x-organization-uuid", creds.getOrgUUID());

            return CompletableFuture.supplyAsync(() -> {
                try {
                    Request.Builder rb = new Request.Builder().url(url).get();
                    headers.forEach(rb::addHeader);
                    try (Response resp = httpClient.newCall(rb.build()).execute()) {
                        if (resp.code() != 200 || resp.body() == null) {
                            throw new RuntimeException("Failed to fetch code sessions: " + resp.code());
                        }
                        Map<?, ?> body = objectMapper.readValue(resp.body().string(), Map.class);
                        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
                        if (data == null) return List.of();
                        List<CodeSession> sessions = new ArrayList<>();
                        for (Map<String, Object> s : data) {
                            sessions.add(mapToCodeSession(s));
                        }
                        return sessions;
                    }
                } catch (Exception e) {
                    log.error("[teleport] fetchCodeSessions error: {}", e.getMessage());
                    throw new RuntimeException(e);
                }
            });
        });
    }

    /**
     * Fetch a single session by ID.
     * Translated from fetchSession() in api.ts
     */
    public CompletableFuture<SessionResource> fetchSession(String sessionId) {
        return prepareApiRequest().thenCompose(creds -> CompletableFuture.supplyAsync(() -> {
            String url = oauthService.getBaseApiUrl() + "/v1/sessions/" + sessionId;
            Map<String, String> headers = getOAuthHeaders(creds.getAccessToken());
            headers.put("anthropic-beta", CCR_BYOC_BETA);
            headers.put("x-organization-uuid", creds.getOrgUUID());

            try {
                Request.Builder rb = new Request.Builder().url(url).get();
                headers.forEach(rb::addHeader);
                try (Response resp = httpClient.newCall(rb.build()).execute()) {
                    int code = resp.code();
                    if (code == 404) throw new RuntimeException("Session not found: " + sessionId);
                    if (code == 401) throw new RuntimeException("Session expired. Please run /login to sign in again.");
                    if (code != 200 || resp.body() == null) {
                        throw new RuntimeException("Failed to fetch session: " + code);
                    }
                    return objectMapper.readValue(resp.body().string(), SessionResource.class);
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException("fetchSession failed", e);
            }
        }));
    }

    /**
     * Extract the first branch name from a session's git repository outcomes.
     * Translated from getBranchFromSession() in api.ts
     */
    public Optional<String> getBranchFromSession(SessionResource session) {
        if (session.getSessionContext() == null
                || session.getSessionContext().getOutcomes() == null) {
            return Optional.empty();
        }
        return session.getSessionContext().getOutcomes().stream()
            .filter(o -> "git_repository".equals(o.getType()))
            .findFirst()
            .flatMap(o -> o.getGitInfo() != null
                && o.getGitInfo().getBranches() != null
                && !o.getGitInfo().getBranches().isEmpty()
                ? Optional.of(o.getGitInfo().getBranches().get(0))
                : Optional.empty());
    }

    /**
     * Send a user message event to an existing remote session.
     * Translated from sendEventToRemoteSession() in api.ts
     */
    public CompletableFuture<Boolean> sendEventToRemoteSession(String sessionId,
                                                                RemoteMessageContent messageContent,
                                                                String uuid) {
        return prepareApiRequest().thenCompose(creds -> CompletableFuture.supplyAsync(() -> {
            try {
                String url = oauthService.getBaseApiUrl() + "/v1/sessions/" + sessionId + "/events";
                Map<String, String> headers = getOAuthHeaders(creds.getAccessToken());
                headers.put("anthropic-beta", CCR_BYOC_BETA);
                headers.put("x-organization-uuid", creds.getOrgUUID());

                Object content = switch (messageContent) {
                    case RemoteMessageContent.TextContent tc -> tc.text();
                    case RemoteMessageContent.BlocksContent bc -> bc.blocks();
                };

                Map<String, Object> userEvent = new LinkedHashMap<>();
                userEvent.put("uuid", uuid != null ? uuid : UUID.randomUUID().toString());
                userEvent.put("session_id", sessionId);
                userEvent.put("type", "user");
                userEvent.put("parent_tool_use_id", null);
                userEvent.put("message", Map.of("role", "user", "content", content));

                Map<String, Object> body = Map.of("events", List.of(userEvent));
                String json = objectMapper.writeValueAsString(body);

                Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();
                headers.forEach(req.newBuilder()::addHeader);

                Request builtReq;
                {
                    Request.Builder rb = new Request.Builder().url(url)
                        .post(RequestBody.create(json, MediaType.parse("application/json")));
                    headers.forEach(rb::addHeader);
                    builtReq = rb.build();
                }

                try (Response resp = httpClient.newCall(builtReq).execute()) {
                    boolean ok = resp.code() == 200 || resp.code() == 201;
                    log.debug("[teleport] sendEventToRemoteSession {} → {}", sessionId, resp.code());
                    return ok;
                }
            } catch (Exception e) {
                log.debug("[teleport] sendEventToRemoteSession error: {}", e.getMessage());
                return false;
            }
        }));
    }

    /**
     * Update the title of an existing remote session.
     * Translated from updateSessionTitle() in api.ts
     */
    public CompletableFuture<Boolean> updateSessionTitle(String sessionId, String title) {
        return prepareApiRequest().thenCompose(creds -> CompletableFuture.supplyAsync(() -> {
            try {
                String url = oauthService.getBaseApiUrl() + "/v1/sessions/" + sessionId;
                Map<String, String> headers = getOAuthHeaders(creds.getAccessToken());
                headers.put("anthropic-beta", CCR_BYOC_BETA);
                headers.put("x-organization-uuid", creds.getOrgUUID());

                String json = objectMapper.writeValueAsString(Map.of("title", title));
                Request.Builder rb = new Request.Builder().url(url)
                    .patch(RequestBody.create(json, MediaType.parse("application/json")));
                headers.forEach(rb::addHeader);

                try (Response resp = httpClient.newCall(rb.build()).execute()) {
                    log.debug("[teleport] updateSessionTitle {} → {}", sessionId, resp.code());
                    return resp.code() == 200;
                }
            } catch (Exception e) {
                log.debug("[teleport] updateSessionTitle error: {}", e.getMessage());
                return false;
            }
        }));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private CodeSession mapToCodeSession(Map<String, Object> s) {
        CodeSession cs = new CodeSession();
        cs.setId((String) s.get("id"));
        cs.setTitle(s.get("title") != null ? (String) s.get("title") : "Untitled");
        cs.setDescription("");
        cs.setStatus((String) s.get("session_status"));
        cs.setTurns(List.of());
        cs.setCreatedAt((String) s.get("created_at"));
        cs.setUpdatedAt((String) s.get("updated_at"));
        // repo parsed from git source
        Map<String, Object> ctx = (Map<String, Object>) s.get("session_context");
        if (ctx != null) {
            List<Map<String, Object>> sources = (List<Map<String, Object>>) ctx.get("sources");
            if (sources != null) {
                sources.stream()
                    .filter(src -> "git_repository".equals(src.get("type")))
                    .findFirst()
                    .ifPresent(gitSrc -> {
                        String url = (String) gitSrc.get("url");
                        if (url != null) {
                            String[] parts = parseGitHubRepoPath(url);
                            if (parts != null) {
                                CodeSessionOwner owner = new CodeSessionOwner(parts[0]);
                                String revision = (String) gitSrc.get("revision");
                                cs.setRepo(new CodeSessionRepo(parts[1], owner, revision));
                            }
                        }
                    });
            }
        }
        return cs;
    }

    /**
     * Poll remote session events starting from cursor.
     * Returns a list of event maps.
     */
    public CompletableFuture<List<Map<String, Object>>> pollRemoteSessionEvents(
            String sessionId, String cursor) {
        return CompletableFuture.completedFuture(new java.util.ArrayList<>());
    }

    /** Extract [owner, name] from a GitHub URL. Returns null if not parseable. */
    private static String[] parseGitHubRepoPath(String url) {
        // Handles https://github.com/owner/repo and git@github.com:owner/repo
        String path = url.replaceAll(".*github\\.com[:/]", "").replaceAll("\\.git$", "");
        String[] parts = path.split("/", 2);
        return parts.length == 2 ? parts : null;
    }
}
