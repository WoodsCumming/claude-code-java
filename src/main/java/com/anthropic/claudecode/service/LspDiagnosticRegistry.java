package com.anthropic.claudecode.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP Diagnostic Registry.
 * Translated from src/services/lsp/LSPDiagnosticRegistry.ts
 *
 * Stores LSP diagnostics received asynchronously from LSP servers via
 * textDocument/publishDiagnostics notifications.  Follows the same pattern
 * as AsyncHookRegistry for consistent async attachment delivery.
 *
 * Pattern:
 *  1. LSP server sends publishDiagnostics notification.
 *  2. registerPendingLSPDiagnostic() stores the diagnostic.
 *  3. checkForLSPDiagnostics() retrieves pending diagnostics (deduplicated).
 *  4. Caller converts the result to attachments and delivers to the conversation.
 */
@Slf4j
@Service
public class LspDiagnosticRegistry {



    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int MAX_DIAGNOSTICS_PER_FILE = 10;
    private static final int MAX_TOTAL_DIAGNOSTICS = 30;
    /** Maximum number of file URIs tracked for cross-turn deduplication. */
    private static final int MAX_DELIVERED_FILES = 500;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Map<String, PendingLSPDiagnostic> pendingDiagnostics = new ConcurrentHashMap<>();

    /**
     * Cross-turn deduplication: maps file URI → set of diagnostic keys.
     * Bounded to MAX_DELIVERED_FILES entries (oldest evicted) to prevent unbounded growth.
     */
    private final LinkedHashMap<String, Set<String>> deliveredDiagnostics =
        new LinkedHashMap<>(MAX_DELIVERED_FILES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                return size() > MAX_DELIVERED_FILES;
            }
        };

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PendingLSPDiagnostic {
        private String serverName;
        private List<DiagnosticFile> files;
        private long timestamp;
        private boolean attachmentSent;

        public String getServerName() { return serverName; }
        public void setServerName(String v) { serverName = v; }
        public List<DiagnosticFile> getFiles() { return files; }
        public void setFiles(List<DiagnosticFile> v) { files = v; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long v) { timestamp = v; }
        public boolean isAttachmentSent() { return attachmentSent; }
        public void setAttachmentSent(boolean v) { attachmentSent = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DiagnosticFile {
        private String uri;
        private List<Diagnostic> diagnostics;

        public String getUri() { return uri; }
        public void setUri(String v) { uri = v; }
        public List<Diagnostic> getDiagnostics() { return diagnostics; }
        public void setDiagnostics(List<Diagnostic> v) { diagnostics = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Diagnostic {
        private String message;
        private String severity;  // "Error" | "Warning" | "Info" | "Hint"
        private DiagnosticRange range;
        private String source;
        private String code;

        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public String getSeverity() { return severity; }
        public void setSeverity(String v) { severity = v; }
        public DiagnosticRange getRange() { return range; }
        public void setRange(DiagnosticRange v) { range = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
        public String getCode() { return code; }
        public void setCode(String v) { code = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DiagnosticRange {
        private DiagnosticPosition start;
        private DiagnosticPosition end;

        public DiagnosticPosition getStart() { return start; }
        public void setStart(DiagnosticPosition v) { start = v; }
        public DiagnosticPosition getEnd() { return end; }
        public void setEnd(DiagnosticPosition v) { end = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DiagnosticPosition {
        private int line;
        private int character;

        public int getLine() { return line; }
        public void setLine(int v) { line = v; }
        public int getCharacter() { return character; }
        public void setCharacter(int v) { character = v; }
    }

    public record DiagnosticResult(String serverName, List<DiagnosticFile> files) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Register LSP diagnostics received from a server.
     * Translated from registerPendingLSPDiagnostic() in LSPDiagnosticRegistry.ts
     */
    public void registerPendingLSPDiagnostic(String serverName, List<DiagnosticFile> files) {
        String diagnosticId = UUID.randomUUID().toString();
        log.debug("LSP Diagnostics: Registering {} diagnostic file(s) from {} (ID: {})",
            files.size(), serverName, diagnosticId);
        pendingDiagnostics.put(diagnosticId, new PendingLSPDiagnostic(
            serverName, files, System.currentTimeMillis(), false));
    }

    /**
     * Get all pending LSP diagnostics that haven't been delivered yet.
     * Deduplicates diagnostics to prevent sending the same diagnostic multiple times.
     * Marks diagnostics as sent to prevent duplicate delivery.
     * Translated from checkForLSPDiagnostics() in LSPDiagnosticRegistry.ts
     *
     * @return list of at most one DiagnosticResult containing all deduplicated diagnostics
     */
    public synchronized List<DiagnosticResult> checkForLSPDiagnostics() {
        log.debug("LSP Diagnostics: Checking registry - {} pending", pendingDiagnostics.size());

        List<DiagnosticFile> allFiles = new ArrayList<>();
        Set<String> serverNames = new LinkedHashSet<>();
        List<PendingLSPDiagnostic> diagnosticsToMark = new ArrayList<>();

        for (PendingLSPDiagnostic diag : pendingDiagnostics.values()) {
            if (!diag.isAttachmentSent()) {
                allFiles.addAll(diag.getFiles());
                serverNames.add(diag.getServerName());
                diagnosticsToMark.add(diag);
            }
        }

        if (allFiles.isEmpty()) {
            return List.of();
        }

        // Deduplicate
        List<DiagnosticFile> dedupedFiles;
        try {
            dedupedFiles = deduplicateDiagnosticFiles(allFiles);
        } catch (Exception e) {
            log.error("Failed to deduplicate LSP diagnostics: {}", e.getMessage());
            dedupedFiles = allFiles;
        }

        // Mark as sent, then remove from pending map
        for (PendingLSPDiagnostic diag : diagnosticsToMark) {
            diag.setAttachmentSent(true);
        }
        pendingDiagnostics.entrySet().removeIf(e -> e.getValue().isAttachmentSent());

        int originalCount = allFiles.stream().mapToInt(f -> f.getDiagnostics().size()).sum();
        int dedupedCount = dedupedFiles.stream().mapToInt(f -> f.getDiagnostics().size()).sum();
        if (originalCount > dedupedCount) {
            log.debug("LSP Diagnostics: Deduplication removed {} duplicate diagnostic(s)",
                originalCount - dedupedCount);
        }

        // Apply volume limiting: sort by severity, cap per file and total
        int totalDiagnostics = 0;
        int truncatedCount = 0;
        for (DiagnosticFile file : dedupedFiles) {
            file.getDiagnostics().sort(Comparator.comparingInt(d -> severityToNumber(d.getSeverity())));

            if (file.getDiagnostics().size() > MAX_DIAGNOSTICS_PER_FILE) {
                truncatedCount += file.getDiagnostics().size() - MAX_DIAGNOSTICS_PER_FILE;
                file.setDiagnostics(file.getDiagnostics().subList(0, MAX_DIAGNOSTICS_PER_FILE));
            }

            int remainingCapacity = MAX_TOTAL_DIAGNOSTICS - totalDiagnostics;
            if (file.getDiagnostics().size() > remainingCapacity) {
                truncatedCount += file.getDiagnostics().size() - remainingCapacity;
                file.setDiagnostics(file.getDiagnostics().subList(0, remainingCapacity));
            }
            totalDiagnostics += file.getDiagnostics().size();
        }

        dedupedFiles.removeIf(f -> f.getDiagnostics().isEmpty());

        if (truncatedCount > 0) {
            log.debug("LSP Diagnostics: Volume limiting removed {} diagnostic(s) (max {}/file, {} total)",
                truncatedCount, MAX_DIAGNOSTICS_PER_FILE, MAX_TOTAL_DIAGNOSTICS);
        }

        // Track delivered diagnostics for cross-turn deduplication
        for (DiagnosticFile file : dedupedFiles) {
            Set<String> delivered = deliveredDiagnostics.computeIfAbsent(
                file.getUri(), k -> new HashSet<>());
            for (Diagnostic diag : file.getDiagnostics()) {
                try {
                    delivered.add(createDiagnosticKey(diag));
                } catch (Exception e) {
                    log.error("Failed to track delivered diagnostic in {}: {}", file.getUri(), e.getMessage());
                }
            }
        }

        int finalCount = dedupedFiles.stream().mapToInt(f -> f.getDiagnostics().size()).sum();
        if (finalCount == 0) {
            log.debug("LSP Diagnostics: No new diagnostics to deliver (all filtered by deduplication)");
            return List.of();
        }

        log.debug("LSP Diagnostics: Delivering {} file(s) with {} diagnostic(s) from {} server(s)",
            dedupedFiles.size(), finalCount, serverNames.size());

        return List.of(new DiagnosticResult(String.join(", ", serverNames), dedupedFiles));
    }

    /**
     * Clear all pending diagnostics (does NOT clear deliveredDiagnostics).
     * Translated from clearAllLSPDiagnostics() in LSPDiagnosticRegistry.ts
     */
    public synchronized void clearAllLSPDiagnostics() {
        log.debug("LSP Diagnostics: Clearing {} pending diagnostic(s)", pendingDiagnostics.size());
        pendingDiagnostics.clear();
    }

    /**
     * Reset all diagnostic state including cross-turn tracking.
     * Translated from resetAllLSPDiagnosticState() in LSPDiagnosticRegistry.ts
     */
    public synchronized void resetAllLSPDiagnosticState() {
        log.debug("LSP Diagnostics: Resetting all state ({} pending, {} files tracked)",
            pendingDiagnostics.size(), deliveredDiagnostics.size());
        pendingDiagnostics.clear();
        deliveredDiagnostics.clear();
    }

    /**
     * Clear delivered diagnostics for a specific file.
     * Should be called when a file is edited so new diagnostics for that file
     * are shown even if they match previously delivered ones.
     * Translated from clearDeliveredDiagnosticsForFile() in LSPDiagnosticRegistry.ts
     */
    public synchronized void clearDeliveredDiagnosticsForFile(String fileUri) {
        if (deliveredDiagnostics.containsKey(fileUri)) {
            log.debug("LSP Diagnostics: Clearing delivered diagnostics for {}", fileUri);
            deliveredDiagnostics.remove(fileUri);
        }
    }

    /**
     * Get count of pending diagnostics.
     * Translated from getPendingLSPDiagnosticCount() in LSPDiagnosticRegistry.ts
     */
    public int getPendingLSPDiagnosticCount() {
        return pendingDiagnostics.size();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Maps severity string to numeric value for sorting.
     * Error=1, Warning=2, Info=3, Hint=4
     */
    private static int severityToNumber(String severity) {
        if (severity == null) return 4;
        return switch (severity) {
            case "Error"   -> 1;
            case "Warning" -> 2;
            case "Info"    -> 3;
            case "Hint"    -> 4;
            default        -> 4;
        };
    }

    /**
     * Create a stable key for a diagnostic used in deduplication.
     */
    private String createDiagnosticKey(Diagnostic diag) throws JsonProcessingException {
        Map<String, Object> keyData = new LinkedHashMap<>();
        keyData.put("message", diag.getMessage());
        keyData.put("severity", diag.getSeverity());
        keyData.put("range", diag.getRange());
        keyData.put("source", diag.getSource());
        keyData.put("code", diag.getCode());
        return objectMapper.writeValueAsString(keyData);
    }

    /**
     * Deduplicate diagnostics by file URI and content, also filtering out
     * diagnostics delivered in previous turns.
     */
    private List<DiagnosticFile> deduplicateDiagnosticFiles(List<DiagnosticFile> allFiles) {
        Map<String, Set<String>> fileMap = new LinkedHashMap<>();
        Map<String, DiagnosticFile> dedupedMap = new LinkedHashMap<>();

        for (DiagnosticFile file : allFiles) {
            fileMap.computeIfAbsent(file.getUri(), k -> new HashSet<>());
            dedupedMap.computeIfAbsent(file.getUri(), k ->
                new DiagnosticFile(file.getUri(), new ArrayList<>()));

            Set<String> seenDiagnostics = fileMap.get(file.getUri());
            DiagnosticFile dedupedFile = dedupedMap.get(file.getUri());
            Set<String> previouslyDelivered = deliveredDiagnostics.getOrDefault(
                file.getUri(), Set.of());

            for (Diagnostic diag : file.getDiagnostics()) {
                try {
                    String key = createDiagnosticKey(diag);
                    if (seenDiagnostics.contains(key) || previouslyDelivered.contains(key)) {
                        continue;
                    }
                    seenDiagnostics.add(key);
                    dedupedFile.getDiagnostics().add(diag);
                } catch (Exception e) {
                    String truncated = diag.getMessage() != null
                        ? diag.getMessage().substring(0, Math.min(100, diag.getMessage().length()))
                        : "<no message>";
                    log.error("Failed to deduplicate diagnostic in {}: {}. Diagnostic message: {}",
                        file.getUri(), e.getMessage(), truncated);
                    dedupedFile.getDiagnostics().add(diag);
                }
            }
        }

        return dedupedMap.values().stream()
            .filter(f -> !f.getDiagnostics().isEmpty())
            .toList();
    }
}
