package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Service for submitting transcript shares and feedback surveys.
 * Translated from src/components/FeedbackSurvey/submitTranscriptShare.ts
 *
 * Handles reading the local session transcript, redacting sensitive
 * information, and POSTing to the Anthropic transcript-share API.
 */
@Slf4j
@Service
public class FeedbackService {



    private static final String TRANSCRIPT_SHARE_URL =
            "https://api.anthropic.com/api/claude_code_shared_session_transcripts";

    private static final long MAX_TRANSCRIPT_READ_BYTES = 10L * 1024 * 1024; // 10 MB

    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final SessionStorageService sessionStorageService;

    public FeedbackService(RestTemplate restTemplate,
                           AuthService authService,
                           SessionStorageService sessionStorageService) {
        this.restTemplate = restTemplate;
        this.authService = authService;
        this.sessionStorageService = sessionStorageService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Submit simple text feedback.
     * Opens the GitHub issues page or logs the feedback.
     *
     * @param report the feedback text
     */
    public void submitFeedback(String report) {
        log.info("[FeedbackService] Feedback received: {}", report);
        // In a full implementation this would POST to the feedback API
        System.out.println("Feedback submitted. Thank you!");
    }

    /**
     * Collects the current session transcript (including sub-agent transcripts),
     * redacts sensitive information, and submits it to the Anthropic API.
     * Translated from submitTranscriptShare() in submitTranscriptShare.ts
     *
     * @param messages     the current conversation messages
     * @param trigger      what caused this share (survey type or frustration signal)
     * @param appearanceId the user's appearance/profile ID
     * @return a CompletableFuture resolving to the share result
     */
    public CompletableFuture<TranscriptShareResult> submitTranscriptShare(
            List<Object> messages,
            TranscriptShareTrigger trigger,
            String appearanceId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("[FeedbackService] Collecting transcript for sharing, trigger={}", trigger);

                // Normalise messages for the API
                List<Object> transcript = normalizeMessagesForApi(messages);

                // Collect sub-agent transcripts
                List<String> agentIds = extractAgentIdsFromMessages(messages);
                Map<String, Object> subagentTranscripts = sessionStorageService.loadSubagentTranscripts(agentIds);

                // Read raw JSONL transcript with size guard to prevent OOM
                String rawTranscriptJsonl = readRawTranscriptSafely();

                // Build the payload
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("trigger", trigger.name().toLowerCase());
                data.put("platform", System.getProperty("os.name", "unknown").toLowerCase());
                data.put("transcript", transcript);
                if (!subagentTranscripts.isEmpty()) {
                    data.put("subagentTranscripts", subagentTranscripts);
                }
                if (rawTranscriptJsonl != null) {
                    data.put("rawTranscriptJsonl", rawTranscriptJsonl);
                }

                // Redact sensitive info before transmitting
                String content = redactSensitiveInfo(toJson(data));

                // Refresh OAuth token if needed
                authService.checkAndRefreshOAuthTokenIfNeeded();

                // Build auth headers
                Optional<Map<String, String>> authHeaders = authService.getAuthHeaders();
                if (authHeaders.isEmpty()) {
                    return new TranscriptShareResult(false, null);
                }

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                authHeaders.get().forEach(headers::set);

                Map<String, Object> body = Map.of(
                        "content", content,
                        "appearance_id", appearanceId);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(
                        TRANSCRIPT_SHARE_URL, request, Map.class);

                if (response != null) {
                    String transcriptId = (String) response.get("transcript_id");
                    log.info("[FeedbackService] Transcript shared successfully, id={}", transcriptId);
                    return new TranscriptShareResult(true, transcriptId);
                }

                return new TranscriptShareResult(false, null);

            } catch (Exception e) {
                log.error("[FeedbackService] Failed to submit transcript share: {}", e.getMessage(), e);
                return new TranscriptShareResult(false, null);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private List<Object> normalizeMessagesForApi(List<Object> messages) {
        // Delegate to session/message normalisation utilities
        return sessionStorageService.normalizeMessagesForApi(messages);
    }

    private List<String> extractAgentIdsFromMessages(List<Object> messages) {
        return sessionStorageService.extractAgentIdsFromMessages(messages);
    }

    private String readRawTranscriptSafely() {
        try {
            // Use current project dir and a stub session id to find transcript
            String projectDir = sessionStorageService.getProjectDir(System.getProperty("user.dir", ""));
            // Try to list sessions to find the most recent one
            java.util.List<SessionStorageService.SessionMetadata> sessions =
                sessionStorageService.listSessions();
            if (sessions.isEmpty()) return null;
            String sessionId = sessions.get(0).getSessionId();
            if (sessionId == null) return null;
            String path = sessionStorageService.getTranscriptPath(projectDir, sessionId);
            java.io.File file = new java.io.File(path);
            if (!file.exists()) {
                return null;
            }
            if (file.length() > MAX_TRANSCRIPT_READ_BYTES) {
                log.warn("[FeedbackService] Skipping raw transcript read: file too large ({} bytes)",
                        file.length());
                return null;
            }
            return java.nio.file.Files.readString(file.toPath());
        } catch (Exception e) {
            // File may not exist or be unreadable – that's fine
            return null;
        }
    }

    /**
     * Redacts API keys, tokens, and other sensitive patterns from a JSON string.
     * Mirrors redactSensitiveInfo() from src/components/Feedback.ts
     */
    private String redactSensitiveInfo(String json) {
        if (json == null) {
            return "";
        }
        // Redact Bearer tokens and API keys
        return json
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9\\-._~+/]+=*", "$1[REDACTED]")
                .replaceAll("(?i)(\"x-api-key\"\\s*:\\s*\")[^\"]+\"", "$1[REDACTED]\"")
                .replaceAll("(?i)(sk-ant-[A-Za-z0-9\\-]{10,})", "[REDACTED]");
    }

    private String toJson(Object obj) {
        // Simple JSON serialisation – in a real project use Jackson's ObjectMapper
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Supporting types
    // -------------------------------------------------------------------------

    /**
     * Sealed result type for a transcript share attempt.
     * Translated from TranscriptShareResult in submitTranscriptShare.ts
     */
    public record TranscriptShareResult(boolean success, String transcriptId) {}

    /**
     * Enumeration of events that can trigger a transcript share.
     * Translated from TranscriptShareTrigger union type in submitTranscriptShare.ts
     */
    public enum TranscriptShareTrigger {
        BAD_FEEDBACK_SURVEY,
        GOOD_FEEDBACK_SURVEY,
        FRUSTRATION,
        MEMORY_SURVEY
    }
}
