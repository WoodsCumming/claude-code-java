package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diagnostic tracking service — tracks IDE diagnostics from LSP servers.
 * Translated from src/services/diagnosticTracking.ts
 *
 * Captures a baseline of diagnostics before a file is edited, then surfaces
 * only the new diagnostics that appeared after editing.
 */
@Slf4j
@Service
public class DiagnosticTrackingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DiagnosticTrackingService.class);


    private static final int MAX_DIAGNOSTICS_SUMMARY_CHARS = 4000;

    /** Singleton, mirroring the exported diagnosticTracker constant in TS. */
    private static volatile DiagnosticTrackingService instance;

    // -------------------------------------------------------------------------
    // Domain types
    // -------------------------------------------------------------------------

    /** Severity levels for a diagnostic. */
    public enum Severity { Error, Warning, Info, Hint }

    /**
     * Source position within a file.
     * Translated from the range.start/end shape in diagnosticTracking.ts
     */
    public record Position(int line, int character) {}

    /**
     * Line/character range in a file.
     */
    public record Range(Position start, Position end) {}

    /**
     * A single IDE diagnostic entry.
     * Translated from Diagnostic interface in diagnosticTracking.ts
     */
    @Data
    public static class Diagnostic {
        private String message;
        private Severity severity;
        private Range range;
        private String source;
        private String code;

        public Diagnostic() {}

        public Diagnostic(String message, Severity severity, Range range,
                          String source, String code) {
            this.message = message;
            this.severity = severity;
            this.range = range;
            this.source = source;
            this.code = code;
        }

        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public Severity getSeverity() { return severity; }
        public void setSeverity(Severity v) { severity = v; }
        public Range getRange() { return range; }
        public void setRange(Range v) { range = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
        public String getCode() { return code; }
        public void setCode(String v) { code = v; }
    }

    /**
     * A file URI with its associated diagnostics.
     * Translated from DiagnosticFile interface in diagnosticTracking.ts
     */
    @Data
    public static class DiagnosticFile {
        private String uri;
        private List<Diagnostic> diagnostics;

        public DiagnosticFile() {}

        public DiagnosticFile(String uri, List<Diagnostic> diagnostics) {
            this.uri = uri;
            this.diagnostics = diagnostics != null ? diagnostics : List.of();
        }

        public String getUri() { return uri; }
        public void setUri(String v) { uri = v; }
        public List<Diagnostic> getDiagnostics() { return diagnostics; }
        public void setDiagnostics(List<Diagnostic> v) { diagnostics = v; }
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Baseline diagnostics captured before a file is edited. */
    private final Map<String, List<Diagnostic>> baseline = new ConcurrentHashMap<>();

    /** Timestamps of the last diagnostic fetch per normalized path. */
    private final Map<String, Long> lastProcessedTimestamps = new ConcurrentHashMap<>();

    /**
     * Last known diagnostics from _claude_fs_right: URIs per normalized path.
     * Used to detect when the "diff" view changes.
     */
    private final Map<String, List<Diagnostic>> rightFileDiagnosticsState = new ConcurrentHashMap<>();

    private volatile boolean initialized = false;

    // -------------------------------------------------------------------------
    // Singleton access
    // -------------------------------------------------------------------------

    public static DiagnosticTrackingService getInstance() {
        if (instance == null) {
            synchronized (DiagnosticTrackingService.class) {
                if (instance == null) {
                    instance = new DiagnosticTrackingService();
                }
            }
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialize the service with an IDE MCP client connection.
     * Translated from initialize() in diagnosticTracking.ts
     */
    public void initialize() {
        if (initialized) return;
        initialized = true;
        log.debug("[diagnosticTracking] initialized");
    }

    /**
     * Shut down and clear all state.
     * Translated from shutdown() in diagnosticTracking.ts
     */
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            initialized = false;
            baseline.clear();
            rightFileDiagnosticsState.clear();
            lastProcessedTimestamps.clear();
            log.debug("[diagnosticTracking] shutdown");
        });
    }

    /**
     * Reset tracking state while keeping the service initialized.
     * Translated from reset() in diagnosticTracking.ts
     */
    public void reset() {
        baseline.clear();
        rightFileDiagnosticsState.clear();
        lastProcessedTimestamps.clear();
        log.debug("[diagnosticTracking] reset");
    }

    // -------------------------------------------------------------------------
    // Baseline capture
    // -------------------------------------------------------------------------

    /**
     * Capture baseline diagnostics for a file before editing.
     * Translated from beforeFileEdited() in diagnosticTracking.ts
     *
     * @param filePath   the file about to be edited
     * @param currentDiagnostics the diagnostics currently reported for this file
     */
    public void beforeFileEdited(String filePath, List<Diagnostic> currentDiagnostics) {
        if (!initialized) return;

        String normalizedPath = normalizeFileUri(filePath);
        List<Diagnostic> copy = currentDiagnostics != null
            ? new ArrayList<>(currentDiagnostics) : new ArrayList<>();
        baseline.put(normalizedPath, copy);
        lastProcessedTimestamps.put(normalizedPath, System.currentTimeMillis());
        log.debug("[diagnosticTracking] baseline set for {}: {} diagnostics",
                  normalizedPath, copy.size());
    }

    // -------------------------------------------------------------------------
    // New diagnostics query
    // -------------------------------------------------------------------------

    /**
     * Return diagnostics that are new since the baseline was captured.
     * Only considers files that have been edited (i.e., have a baseline entry).
     * Translated from getNewDiagnostics() in diagnosticTracking.ts
     *
     * @param allCurrentDiagnostics all diagnostics currently reported by the IDE
     * @return list of files with only their new (post-baseline) diagnostics
     */
    public List<DiagnosticFile> getNewDiagnostics(List<DiagnosticFile> allCurrentDiagnostics) {
        if (!initialized || allCurrentDiagnostics == null) return List.of();

        // Build a lookup of _claude_fs_right: files with baselines
        Map<String, DiagnosticFile> rightFileMap = new HashMap<>();
        allCurrentDiagnostics.stream()
            .filter(f -> f.getUri() != null && f.getUri().startsWith("_claude_fs_right:"))
            .filter(f -> baseline.containsKey(normalizeFileUri(f.getUri())))
            .forEach(f -> rightFileMap.put(normalizeFileUri(f.getUri()), f));

        List<DiagnosticFile> result = new ArrayList<>();

        // Process file:// diagnostics that have a baseline
        allCurrentDiagnostics.stream()
            .filter(f -> f.getUri() != null && f.getUri().startsWith("file://"))
            .filter(f -> baseline.containsKey(normalizeFileUri(f.getUri())))
            .forEach(file -> {
                String normalizedPath = normalizeFileUri(file.getUri());
                List<Diagnostic> baselineDiags = baseline.getOrDefault(normalizedPath, List.of());

                // Prefer _claude_fs_right diagnostics when available and changed
                DiagnosticFile fileToUse = file;
                DiagnosticFile rightFile = rightFileMap.get(normalizedPath);
                if (rightFile != null) {
                    List<Diagnostic> previousRight = rightFileDiagnosticsState.get(normalizedPath);
                    if (previousRight == null ||
                        !areDiagnosticArraysEqual(previousRight, rightFile.getDiagnostics())) {
                        fileToUse = rightFile;
                    }
                    rightFileDiagnosticsState.put(normalizedPath, rightFile.getDiagnostics());
                }

                // Find diagnostics not in baseline
                List<Diagnostic> newDiags = fileToUse.getDiagnostics().stream()
                    .filter(d -> baselineDiags.stream().noneMatch(b -> areDiagnosticsEqual(d, b)))
                    .toList();

                if (!newDiags.isEmpty()) {
                    result.add(new DiagnosticFile(file.getUri(), newDiags));
                }

                // Update baseline with current diagnostics
                baseline.put(normalizedPath, new ArrayList<>(fileToUse.getDiagnostics()));
            });

        return result;
    }

    // -------------------------------------------------------------------------
    // Query lifecycle
    // -------------------------------------------------------------------------

    /**
     * Handle the start of a new query: initialize or reset the tracker.
     * Translated from handleQueryStart() in diagnosticTracking.ts
     */
    public void handleQueryStart() {
        if (!initialized) {
            initialize();
        } else {
            reset();
        }
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    /**
     * Format diagnostics into a human-readable summary string.
     * Translated from formatDiagnosticsSummary() in diagnosticTracking.ts
     */
    public static String formatDiagnosticsSummary(List<DiagnosticFile> files) {
        if (files == null || files.isEmpty()) return "";

        final String TRUNCATION_MARKER = "…[truncated]";

        StringBuilder sb = new StringBuilder();
        for (DiagnosticFile file : files) {
            if (sb.length() > 0) sb.append("\n\n");

            String filename = file.getUri() != null
                ? file.getUri().substring(file.getUri().lastIndexOf('/') + 1)
                : "unknown";
            sb.append(filename).append(":\n");

            if (file.getDiagnostics() != null) {
                for (Diagnostic d : file.getDiagnostics()) {
                    String symbol = getSeveritySymbol(d.getSeverity());
                    int line = d.getRange() != null ? d.getRange().start().line() + 1 : 0;
                    int col  = d.getRange() != null ? d.getRange().start().character() + 1 : 0;
                    String code   = d.getCode() != null ? " [" + d.getCode() + "]" : "";
                    String source = d.getSource() != null ? " (" + d.getSource() + ")" : "";
                    sb.append("  ").append(symbol)
                      .append(" [Line ").append(line).append(':').append(col).append("] ")
                      .append(d.getMessage())
                      .append(code)
                      .append(source)
                      .append('\n');
                }
            }
        }

        String result = sb.toString();
        if (result.length() > MAX_DIAGNOSTICS_SUMMARY_CHARS) {
            return result.substring(0, MAX_DIAGNOSTICS_SUMMARY_CHARS - TRUNCATION_MARKER.length())
                + TRUNCATION_MARKER;
        }
        return result;
    }

    /**
     * Get the display symbol for a diagnostic severity.
     * Translated from getSeveritySymbol() in diagnosticTracking.ts
     */
    public static String getSeveritySymbol(Severity severity) {
        if (severity == null) return "•";
        return switch (severity) {
            case Error   -> "✖";
            case Warning -> "⚠";
            case Info    -> "ℹ";
            case Hint    -> "★";
        };
    }

    // -------------------------------------------------------------------------
    // Path normalization
    // -------------------------------------------------------------------------

    /**
     * Strip protocol prefixes and normalize path separators.
     * Translated from normalizeFileUri() in diagnosticTracking.ts
     */
    String normalizeFileUri(String fileUri) {
        if (fileUri == null) return "";

        String normalized = fileUri;
        for (String prefix : List.of("file://", "_claude_fs_right:", "_claude_fs_left:")) {
            if (fileUri.startsWith(prefix)) {
                normalized = fileUri.substring(prefix.length());
                break;
            }
        }

        // Windows: lowercase drive letter, normalize separators
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            normalized = normalized.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        }

        return normalized;
    }

    // -------------------------------------------------------------------------
    // Equality helpers
    // -------------------------------------------------------------------------

    private boolean areDiagnosticsEqual(Diagnostic a, Diagnostic b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.getMessage(), b.getMessage()) &&
               Objects.equals(a.getSeverity(), b.getSeverity()) &&
               Objects.equals(a.getSource(), b.getSource()) &&
               Objects.equals(a.getCode(), b.getCode()) &&
               rangesEqual(a.getRange(), b.getRange());
    }

    private boolean rangesEqual(Range a, Range b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return positionsEqual(a.start(), b.start()) && positionsEqual(a.end(), b.end());
    }

    private boolean positionsEqual(Position a, Position b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.line() == b.line() && a.character() == b.character();
    }

    private boolean areDiagnosticArraysEqual(List<Diagnostic> a, List<Diagnostic> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        return a.stream().allMatch(da -> b.stream().anyMatch(db -> areDiagnosticsEqual(da, db))) &&
               b.stream().allMatch(db -> a.stream().anyMatch(da -> areDiagnosticsEqual(da, db)));
    }

    // -------------------------------------------------------------------------
    // Simple accessors
    // -------------------------------------------------------------------------

    public boolean isInitialized() {
        return initialized;
    }

    public List<Diagnostic> getBaseline(String fileUri) {
        return baseline.getOrDefault(normalizeFileUri(fileUri), List.of());
    }
}
