package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.BridgeDebugUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Bridge session CRUD operations — create, get, archive, update title.
 * Translated from src/bridge/createSession.ts
 *
 * Used by both `claude remote-control` (standalone bridge) and `/remote-control`
 * (session pre-populated with conversation history).
 */
@Slf4j
@Service
public class BridgeCreateSessionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeCreateSessionService.class);

    private static final String ANTHROPIC_BETA_HEADER = "ccr-byoc-2025-07-29";
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public BridgeCreateSessionService(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    // --- Request / response types ---

    public static class GitSource {
        private String type = "git_repository";
        private String url;
        private String revision;
        public GitSource() {}
        public String getType() { return type; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getRevision() { return revision; }
        public void setRevision(String v) { revision = v; }
    }

    public static class GitOutcomeInfo {
        private String type = "github";
        private String repo;
        private List<String> branches;
        public GitOutcomeInfo() {}
        public String getRepo() { return repo; }
        public void setRepo(String v) { repo = v; }
        public List<String> getBranches() { return branches; }
        public void setBranches(List<String> v) { branches = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GitOutcome {
        private String type = "git_repository";
        private GitOutcomeInfo git_info;
        public GitOutcomeInfo getGitInfo() { return git_info; }
        public void setGitInfo(GitOutcomeInfo v) { this.git_info = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionEvent {
        private String type = "event";
        private Object data; // SDKMessage equivalent

        public void setType(String v) { type = v; }
        public Object getData() { return data; }
        public void setData(Object v) { data = v; }
    }

    public static class CreateSessionRequest {
        private String environmentId;
        private String title;
        private List<SessionEvent> events;
        private String gitRepoUrl;
        private String branch;
        private String permissionMode;
        private String baseUrl;
        private String accessToken;
        private String orgUUID;
        public CreateSessionRequest() {}
        public String getEnvironmentId() { return environmentId; }
        public void setEnvironmentId(String v) { environmentId = v; }
        public String getTitle() { return title; }
        public void setTitle(String v) { title = v; }
        public List<SessionEvent> getEvents() { return events; }
        public void setEvents(List<SessionEvent> v) { events = v; }
        public String getGitRepoUrl() { return gitRepoUrl; }
        public void setGitRepoUrl(String v) { gitRepoUrl = v; }
        public String getBranch() { return branch; }
        public void setBranch(String v) { branch = v; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String v) { permissionMode = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { baseUrl = v; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String v) { accessToken = v; }
        public String getOrgUUID() { return orgUUID; }
        public void setOrgUUID(String v) { orgUUID = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SessionInfo {
        private String environment_id;
        private String title;

        public String getEnvironment_id() { return environment_id; }
        public void setEnvironment_id(String v) { environment_id = v; }
    }

    // --- Public API ---

    /**
     * Create a session on a bridge environment via POST /v1/sessions.
     *
     * @return the session ID on success, or null if creation fails
     */
    public CompletableFuture<String> createBridgeSession(CreateSessionRequest req) {
        return CompletableFuture.supplyAsync(() -> {
            String accessToken = req.getAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                log.debug("[bridge] No access token for session creation");
                return null;
            }

            String orgUUID = req.getOrgUUID();
            if (orgUUID == null || orgUUID.isEmpty()) {
                log.debug("[bridge] No org UUID for session creation");
                return null;
            }

            try {
                // Build git source and outcome context
                GitSource gitSource = null;
                GitOutcome gitOutcome = null;
                if (req.getGitRepoUrl() != null && !req.getGitRepoUrl().isEmpty()) {
                    ParsedGitRemote parsed = parseGitRemote(req.getGitRepoUrl());
                    if (parsed != null) {
                        String revision = req.getBranch() != null && !req.getBranch().isEmpty()
                                ? req.getBranch() : null;
                        gitSource = new GitSource();
                        gitSource.setUrl("https://" + parsed.host + "/" + parsed.owner + "/" + parsed.name);
                        gitSource.setRevision(revision);

                        GitOutcomeInfo info = new GitOutcomeInfo();
                        info.setRepo(parsed.owner + "/" + parsed.name);
                        info.setBranches(List.of("claude/" + (req.getBranch() != null
                                && !req.getBranch().isEmpty() ? req.getBranch() : "task")));

                        gitOutcome = new GitOutcome();
                        gitOutcome.setGitInfo(info);
                    }
                }

                Map<String, Object> sessionContext = new LinkedHashMap<>();
                sessionContext.put("sources", gitSource != null ? List.of(gitSource) : List.of());
                sessionContext.put("outcomes", gitOutcome != null ? List.of(gitOutcome) : List.of());

                Map<String, Object> body = new LinkedHashMap<>();
                if (req.getTitle() != null) body.put("title", req.getTitle());
                if (req.getEvents() != null) body.put("events", req.getEvents());
                body.put("session_context", sessionContext);
                body.put("environment_id", req.getEnvironmentId());
                body.put("source", "remote-control");
                if (req.getPermissionMode() != null) {
                    body.put("permission_mode", req.getPermissionMode());
                }

                String jsonBody = objectMapper.writeValueAsString(body);
                String url = (req.getBaseUrl() != null ? req.getBaseUrl()
                        : "https://api.anthropic.com") + "/v1/sessions";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .header("anthropic-beta", ANTHROPIC_BETA_HEADER)
                        .header("x-organization-uuid", orgUUID)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                boolean isSuccess = response.statusCode() == 200 || response.statusCode() == 201;
                if (!isSuccess) {
                    String detail = parseErrorDetail(response.body());
                    log.debug("[bridge] Session creation failed with status {}{}",
                            response.statusCode(), detail != null ? ": " + detail : "");
                    return null;
                }

                Map<?, ?> data = objectMapper.readValue(response.body(), Map.class);
                Object id = data.get("id");
                if (!(id instanceof String)) {
                    log.debug("[bridge] No session ID in response");
                    return null;
                }
                return (String) id;

            } catch (Exception err) {
                log.debug("[bridge] Session creation request failed: {}", err.getMessage());
                return null;
            }
        });
    }

    /**
     * Fetch a bridge session via GET /v1/sessions/{id}.
     *
     * @return SessionInfo with environment_id and title, or null on failure
     */
    public CompletableFuture<SessionInfo> getBridgeSession(
            String sessionId, String baseUrl, String accessToken, String orgUUID) {
        return CompletableFuture.supplyAsync(() -> {
            if (accessToken == null) {
                log.debug("[bridge] No access token for session fetch");
                return null;
            }
            if (orgUUID == null) {
                log.debug("[bridge] No org UUID for session fetch");
                return null;
            }

            String url = (baseUrl != null ? baseUrl : "https://api.anthropic.com")
                    + "/v1/sessions/" + sessionId;
            log.debug("[bridge] Fetching session {}", sessionId);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("anthropic-beta", ANTHROPIC_BETA_HEADER)
                        .header("x-organization-uuid", orgUUID)
                        .timeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    String detail = parseErrorDetail(response.body());
                    log.debug("[bridge] Session fetch failed with status {}{}",
                            response.statusCode(), detail != null ? ": " + detail : "");
                    return null;
                }

                return objectMapper.readValue(response.body(), SessionInfo.class);

            } catch (Exception err) {
                log.debug("[bridge] Session fetch request failed: {}", err.getMessage());
                return null;
            }
        });
    }

    /**
     * Archive a bridge session via POST /v1/sessions/{id}/archive.
     *
     * The archive endpoint accepts sessions in any status and returns 409 if
     * already archived, making it safe to call even if already archived.
     * Errors are thrown — callers must handle with .exceptionally().
     */
    public CompletableFuture<Void> archiveBridgeSession(
            String sessionId, String baseUrl, String accessToken, String orgUUID,
            Long timeoutMs) {
        return CompletableFuture.runAsync(() -> {
            if (accessToken == null) {
                log.debug("[bridge] No access token for session archive");
                return;
            }
            if (orgUUID == null) {
                log.debug("[bridge] No org UUID for session archive");
                return;
            }

            long timeout = timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS;
            String url = (baseUrl != null ? baseUrl : "https://api.anthropic.com")
                    + "/v1/sessions/" + sessionId + "/archive";
            log.debug("[bridge] Archiving session {}", sessionId);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .header("anthropic-beta", ANTHROPIC_BETA_HEADER)
                        .header("x-organization-uuid", orgUUID)
                        .timeout(Duration.ofMillis(timeout))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    log.debug("[bridge] Session {} archived successfully", sessionId);
                } else {
                    String detail = parseErrorDetail(response.body());
                    log.debug("[bridge] Session archive failed with status {}{}",
                            response.statusCode(), detail != null ? ": " + detail : "");
                }
            } catch (Exception err) {
                // Rethrow so callers can handle it
                throw new RuntimeException("[bridge] Session archive request failed: "
                        + err.getMessage(), err);
            }
        });
    }

    /**
     * Update the title of a bridge session via PATCH /v1/sessions/{id}.
     * Errors are swallowed — title sync is best-effort.
     */
    public CompletableFuture<Void> updateBridgeSessionTitle(
            String sessionId, String title, String baseUrl, String accessToken, String orgUUID) {
        return CompletableFuture.runAsync(() -> {
            if (accessToken == null) {
                log.debug("[bridge] No access token for session title update");
                return;
            }
            if (orgUUID == null) {
                log.debug("[bridge] No org UUID for session title update");
                return;
            }

            // Compat gateway only accepts session_* — re-tag cse_* here
            String compatId = toCompatSessionId(sessionId);
            String url = (baseUrl != null ? baseUrl : "https://api.anthropic.com")
                    + "/v1/sessions/" + compatId;
            log.debug("[bridge] Updating session title: {} → {}", compatId, title);

            try {
                String jsonBody = objectMapper.writeValueAsString(Map.of("title", title));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .header("anthropic-beta", ANTHROPIC_BETA_HEADER)
                        .header("x-organization-uuid", orgUUID)
                        .timeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    log.debug("[bridge] Session title updated successfully");
                } else {
                    String detail = parseErrorDetail(response.body());
                    log.debug("[bridge] Session title update failed with status {}{}",
                            response.statusCode(), detail != null ? ": " + detail : "");
                }
            } catch (Exception err) {
                log.debug("[bridge] Session title update request failed: {}", err.getMessage());
            }
        });
    }

    // --- Private helpers ---

    private String toCompatSessionId(String sessionId) {
        if (sessionId.startsWith("cse_")) {
            return "session_" + sessionId.substring(4);
        }
        return sessionId;
    }

    private String parseErrorDetail(String body) {
        try {
            Map<?, ?> data = objectMapper.readValue(body, Map.class);
            return BridgeDebugUtils.extractErrorDetail(data);
        } catch (Exception e) {
            return null;
        }
    }

    private static class ParsedGitRemote {
        String host;
        String owner;
        String name;
        ParsedGitRemote() {}
        ParsedGitRemote(String host, String owner, String name) { this.host = host; this.owner = owner; this.name = name; }
        public String getHost() { return host; }
        public void setHost(String v) { host = v; }
        public String getOwner() { return owner; }
        public void setOwner(String v) { owner = v; }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
    }

    private ParsedGitRemote parseGitRemote(String url) {
        if (url == null || url.isEmpty()) return null;
        // Match patterns like https://github.com/owner/repo or git@github.com:owner/repo
        String normalized = url.trim();
        // Remove trailing .git
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        // HTTPS pattern
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://([^/]+)/([^/]+)/([^/]+)/?")
                .matcher(normalized);
        if (m.matches()) {
            ParsedGitRemote result = new ParsedGitRemote();
            result.setHost(m.group(1));
            result.setOwner(m.group(2));
            result.setName(m.group(3));
            return result;
        }
        // SSH pattern: git@github.com:owner/repo
        m = java.util.regex.Pattern
                .compile("git@([^:]+):([^/]+)/([^/]+)")
                .matcher(normalized);
        if (m.matches()) {
            ParsedGitRemote result = new ParsedGitRemote();
            result.setHost(m.group(1));
            result.setOwner(m.group(2));
            result.setName(m.group(3));
            return result;
        }
        return null;
    }
}
