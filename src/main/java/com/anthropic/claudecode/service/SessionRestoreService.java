package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Session restore service for resuming previous sessions.
 * Translated from src/utils/sessionRestore.ts
 *
 * Handles restoring session state (messages, file history, attribution,
 * agent settings, worktree) from persisted transcripts on --resume / --continue.
 */
@Slf4j
@Service
public class SessionRestoreService {



    /**
     * Result of a session restore operation.
     * Translated from ResumeResult / ProcessedResume in sessionRestore.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RestoreResult {
        private boolean success;
        /** Non-null when success == false */
        private String error;
        private List<Message> messages;
        /** Name of the agent that was active when the session was saved, if any. */
        private String agentName;
        /** Model override stored in the session, if any. */
        private String model;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean v) { success = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> v) { messages = v; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String v) { agentName = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
    }

    private final SessionStorageService sessionStorageService;
    private final SessionService sessionService;
    private final ModelService modelService;

    @Autowired
    public SessionRestoreService(SessionStorageService sessionStorageService,
                                  SessionService sessionService,
                                  ModelService modelService) {
        this.sessionStorageService = sessionStorageService;
        this.sessionService = sessionService;
        this.modelService = modelService;
    }

    /**
     * Restore a session from its persisted transcript.
     * Switches the active session ID and reloads all messages.
     * Translated from the resume path in processResumedConversation() in sessionRestore.ts
     */
    public CompletableFuture<RestoreResult> restoreSession(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Message> messages = sessionStorageService.loadTranscript(sessionId);
                if (messages.isEmpty()) {
                    return new RestoreResult(false,
                            "Session not found or empty: " + sessionId, List.of(), null, null);
                }
                sessionService.resumeSession(sessionId);
                log.info("Restored session {} with {} messages", sessionId, messages.size());
                return new RestoreResult(true, null, messages, null, null);
            } catch (Exception e) {
                log.error("Failed to restore session {}: {}", sessionId, e.getMessage());
                return new RestoreResult(false, e.getMessage(), List.of(), null, null);
            }
        });
    }

    /**
     * Return the ID of the most recent session, if one exists.
     * Mirrors the getLastSessionId pattern used by --continue in sessionRestore.ts
     */
    public Optional<String> getLastSessionId() {
        List<SessionStorageService.SessionMetadata> sessions = sessionStorageService.listSessions();
        if (sessions.isEmpty()) return Optional.empty();
        return Optional.of(sessions.get(0).getSessionId());
    }

    /**
     * Extract the TodoWrite todos from the last assistant message in the transcript.
     * Translated from extractTodosFromTranscript() in sessionRestore.ts
     *
     * @param messages Transcript messages in chronological order.
     * @return List of todo strings, or empty list if none found.
     */
    public List<String> extractTodosFromTranscript(List<Message> messages) {
        // Walk backwards through the messages looking for the last TodoWrite block
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!(msg instanceof Message.AssistantMessage assistantMsg)) continue;
            // Delegate to the model's content to find a TODO_WRITE tool_use block
            List<String> todos = assistantMsg.extractTodoWriteItems();
            if (todos != null && !todos.isEmpty()) {
                return todos;
            }
        }
        return List.of();
    }

    /**
     * Restore session state (file history, attribution, todos) from a loaded transcript.
     * Translated from restoreSessionStateFromLog() in sessionRestore.ts
     *
     * @param messages The messages loaded from the transcript.
     * @return A {@link SessionState} record capturing all restored state.
     */
    public SessionState restoreSessionStateFromLog(List<Message> messages) {
        List<String> todos = extractTodosFromTranscript(messages);
        log.debug("Restored session state: {} messages, {} todos", messages.size(), todos.size());
        return new SessionState(messages, todos);
    }

    /**
     * Lightweight value object holding all state restored for a session.
     */
    public record SessionState(List<Message> messages, List<String> todos) {}
}
