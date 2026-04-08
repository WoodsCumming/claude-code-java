package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.GitUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.Data;

/**
 * Git commit attribution service.
 * Translated from src/utils/commitAttribution.ts
 *
 * Adds Co-Authored-By trailers to git commits to attribute
 * changes made with Claude Code assistance.
 */
@Slf4j
@Service
public class CommitAttributionService {



    private static final String CO_AUTHORED_BY_TRAILER = "Co-Authored-By: Claude <noreply@anthropic.com>";

    /**
     * Tracks the current attribution state.
     * Translated from AttributionState in commitAttribution.ts
     */
    @Data
    @lombok.Builder
    
    public static class AttributionState {
        private Map<String, FileAttributionState> fileStates;
        private String sessionId;
        private String modelName;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    
    public static class FileAttributionState {
        private String filePath;
        private String lastModifiedBy; // "claude" | "user"
        private long lastModifiedAt;
    }

    /**
     * Get the attribution trailer for commits.
     * Translated from getAttributionHeader() in system.ts
     */
    public String getAttributionTrailer(String modelName) {
        return CO_AUTHORED_BY_TRAILER;
    }

    /**
     * Create attribution state for the current session.
     */
    public AttributionState createInitialState(String sessionId, String modelName) {
        return AttributionState.builder()
            .fileStates(new HashMap<>())
            .sessionId(sessionId)
            .modelName(modelName)
            .build();
    }

    /**
     * Update attribution state when a file is modified.
     */
    public AttributionState trackFileModification(
            AttributionState state,
            String filePath,
            String modifiedBy) {

        Map<String, FileAttributionState> newStates = new HashMap<>(state.getFileStates());
        newStates.put(filePath, new FileAttributionState(filePath, modifiedBy, System.currentTimeMillis()));

        return AttributionState.builder()
            .fileStates(newStates)
            .sessionId(state.getSessionId())
            .modelName(state.getModelName())
            .build();
    }

    /**
     * Check if a file was modified by Claude in this session.
     */
    public boolean wasModifiedByClaude(AttributionState state, String filePath) {
        FileAttributionState fileState = state.getFileStates().get(filePath);
        return fileState != null && "claude".equals(fileState.getLastModifiedBy());
    }
}
