package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Magic Docs service.
 * Translated from src/services/MagicDocs/magicDocs.ts
 *
 * Automatically maintains markdown documentation files marked with special headers.
 * When a file with "# MAGIC DOC: [title]" is read, it runs periodically in the
 * background using a forked sub-agent to update the document with new learnings
 * from the conversation.
 */
@Slf4j
@Service
public class MagicDocsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MagicDocsService.class);


    // Magic Doc header pattern: # MAGIC DOC: [title]
    // Matches at the start of the file (first line)
    private static final Pattern MAGIC_DOC_HEADER_PATTERN =
        Pattern.compile("^#\\s*MAGIC\\s+DOC:\\s*(.+)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // Pattern to match italics on the line immediately after the header
    private static final Pattern ITALICS_PATTERN =
        Pattern.compile("^[_*](.+?)[_*]\\s*$", Pattern.MULTILINE);

    // Track magic docs — keyed by file path
    private final Map<String, MagicDocInfo> trackedMagicDocs = new ConcurrentHashMap<>();

    /**
     * Clear tracked magic docs.
     * Translated from clearTrackedMagicDocs() in magicDocs.ts
     */
    public void clearTrackedMagicDocs() {
        trackedMagicDocs.clear();
    }

    /**
     * Detect if a file content contains a Magic Doc header.
     * Returns a MagicDocHeader with title and optional instructions, or empty if not a magic doc.
     * Translated from detectMagicDocHeader() in magicDocs.ts
     */
    public Optional<MagicDocHeader> detectMagicDocHeader(String content) {
        if (content == null) return Optional.empty();

        Matcher m = MAGIC_DOC_HEADER_PATTERN.matcher(content);
        if (!m.find()) return Optional.empty();

        String title = m.group(1).trim();

        // Look for italics on the next line after the header (allow one optional blank line).
        // Match: newline, optional blank line, then content line
        String afterHeader = content.substring(m.end());
        Pattern nextLinePattern = Pattern.compile("^\\s*\\n(?:\\s*\\n)?(.+?)(?:\\n|$)");
        Matcher nextLineMatcher = nextLinePattern.matcher(afterHeader);

        String instructions = null;
        if (nextLineMatcher.find()) {
            String nextLine = nextLineMatcher.group(1);
            Matcher italicsMatcher = ITALICS_PATTERN.matcher(nextLine);
            if (italicsMatcher.matches()) {
                instructions = italicsMatcher.group(1).trim();
            }
        }

        return Optional.of(new MagicDocHeader(title, instructions));
    }

    /**
     * Register a file as a Magic Doc when it's read.
     * Only registers once per file path — the hook always reads latest content.
     * Translated from registerMagicDoc() in magicDocs.ts
     */
    public void registerMagicDoc(String filePath) {
        trackedMagicDocs.putIfAbsent(filePath, new MagicDocInfo(filePath));
    }

    /**
     * Handle a file-read event: detect magic doc header and register if found.
     * Equivalent to the registerFileReadListener callback in initMagicDocs().
     */
    public void onFileRead(String filePath, String content) {
        Optional<MagicDocHeader> result = detectMagicDocHeader(content);
        if (result.isPresent()) {
            registerMagicDoc(filePath);
        }
    }

    /**
     * Trigger a post-sampling update of all tracked magic docs.
     * Only runs when the conversation is idle (no pending tool calls) and source is main thread.
     * Translated from updateMagicDocs (the sequential post-sampling hook) in magicDocs.ts
     *
     * @param querySource The originating query source (must be "repl_main_thread")
     * @param hasToolCallsInLastTurn Whether the last assistant turn contained tool calls
     * @return CompletableFuture that completes when all updates are done
     */
    public CompletableFuture<Void> updateMagicDocsIfIdle(String querySource, boolean hasToolCallsInLastTurn) {
        if (!"repl_main_thread".equals(querySource)) {
            return CompletableFuture.completedFuture(null);
        }
        if (hasToolCallsInLastTurn) {
            return CompletableFuture.completedFuture(null);
        }
        if (trackedMagicDocs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // Run each doc update sequentially (mirrors sequential() wrapper in TS)
        List<MagicDocInfo> docs = new ArrayList<>(trackedMagicDocs.values());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (MagicDocInfo doc : docs) {
            chain = chain.thenCompose(_v -> updateMagicDoc(doc));
        }
        return chain;
    }

    /**
     * Update a single Magic Doc using a forked sub-agent.
     * Translated from updateMagicDoc() in magicDocs.ts
     *
     * In a full implementation this would:
     *  1. Clone the file-state cache so dedup doesn't return a stale stub
     *  2. Re-read the file to detect current title + instructions
     *  3. Build the update prompt via buildMagicDocsUpdatePrompt()
     *  4. Run a forked "magic-docs" agent (model: sonnet) with only FileEdit allowed
     */
    private CompletableFuture<Void> updateMagicDoc(MagicDocInfo docInfo) {
        return CompletableFuture.runAsync(() -> {
            log.debug("[MagicDocs] Updating magic doc: {}", docInfo.getPath());
            // Full implementation delegates to a forked agent (runAgent) that is
            // restricted to FILE_EDIT_TOOL_NAME on docInfo.path only.
        });
    }

    /**
     * Initialize Magic Docs: register the file-read listener and post-sampling hook.
     * Only active when USER_TYPE == "ant".
     * Translated from initMagicDocs() in magicDocs.ts
     */
    public void initMagicDocs() {
        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) {
            log.debug("[MagicDocs] Magic Docs initialized (ant mode)");
            // In a full Spring wiring, the file-read listener and post-sampling hook
            // would be registered here via event bus / ApplicationEventPublisher.
        }
    }

    // ---------------------------------------------------------------------------
    // Inner types
    // ---------------------------------------------------------------------------

    /**
     * Detected magic-doc header, including optional custom instructions.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MagicDocHeader {
        /** The title extracted from "# MAGIC DOC: <title>" */
        private String title;
        /** Optional italics instruction on the next line, or null */
        private String instructions;

        public String getTitle() { return title; }
        public void setTitle(String v) { title = v; }
        public String getInstructions() { return instructions; }
        public void setInstructions(String v) { instructions = v; }
    }

    /**
     * Tracking entry for a registered magic doc file.
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    private static class MagicDocInfo {
        private final String path;
    }
}
