package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Find relevant memories service.
 * Translated from src/memdir/findRelevantMemories.ts
 *
 * Finds memory files relevant to a query by scanning memory file headers
 * and asking a model to select the most relevant ones.
 */
@Slf4j
@Service
public class FindRelevantMemoriesService {



    /**
     * System prompt for the memory selection model call.
     * Translated from SELECT_MEMORIES_SYSTEM_PROMPT in findRelevantMemories.ts
     */
    private static final String SELECT_MEMORIES_SYSTEM_PROMPT =
            "You are selecting memories that will be useful to Claude Code as it processes a user's query. " +
            "You will be given the user's query and a list of available memory files with their filenames and descriptions.\n\n" +
            "Return a list of filenames for the memories that will clearly be useful to Claude Code as it processes " +
            "the user's query (up to 5). Only include memories that you are certain will be helpful based on their " +
            "name and description.\n" +
            "- If you are unsure if a memory will be useful in processing the user's query, then do not include it " +
            "in your list. Be selective and discerning.\n" +
            "- If there are no memories in the list that would clearly be useful, feel free to return an empty list.\n" +
            "- If a list of recently-used tools is provided, do not select memories that are usage reference or API " +
            "documentation for those tools (Claude Code is already exercising them). DO still select memories " +
            "containing warnings, gotchas, or known issues about those tools — active use is exactly when those matter.";

    /**
     * A relevant memory: absolute file path + modification time.
     * Translated from RelevantMemory in findRelevantMemories.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RelevantMemory {
        private String path;
        private long mtimeMs;

        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public long getMtimeMs() { return mtimeMs; }
        public void setMtimeMs(long v) { mtimeMs = v; }
    }

    private final MemoryScanService memoryScanService;
    private final SideQueryService sideQueryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public FindRelevantMemoriesService(
            MemoryScanService memoryScanService,
            SideQueryService sideQueryService) {
        this.memoryScanService = memoryScanService;
        this.sideQueryService = sideQueryService;
    }

    /**
     * Find memory files relevant to a query.
     *
     * Returns absolute file paths + mtime of the most relevant memories (up to 5).
     * Excludes MEMORY.md (already loaded in system prompt).
     * mtime is threaded through so callers can surface freshness without a second stat.
     *
     * {@code alreadySurfaced} filters paths shown in prior turns before the model call,
     * so the selector spends its 5-slot budget on fresh candidates.
     *
     * Translated from findRelevantMemories() in findRelevantMemories.ts
     *
     * @param query          the user's current query
     * @param memoryDir      path to the memory directory to scan
     * @param recentTools    tools recently used (to avoid surfacing their reference docs)
     * @param alreadySurfaced paths already surfaced in prior turns (excluded from selection)
     */
    public CompletableFuture<List<RelevantMemory>> findRelevantMemories(
            String query,
            String memoryDir,
            List<String> recentTools,
            Set<String> alreadySurfaced) {

        return memoryScanService.scanMemoryFiles(memoryDir)
                .thenCompose(allMemories -> {
                    List<MemoryScanService.MemoryHeader> memories = allMemories.stream()
                            .filter(m -> !alreadySurfaced.contains(m.getFilePath()))
                            .collect(Collectors.toList());

                    if (memories.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<RelevantMemory>of());
                    }

                    return selectRelevantMemories(query, memories, recentTools)
                            .thenApply(selectedFilenames -> {
                                Map<String, MemoryScanService.MemoryHeader> byFilename = new LinkedHashMap<>();
                                for (MemoryScanService.MemoryHeader m : memories) {
                                    byFilename.put(m.getFilename(), m);
                                }

                                return selectedFilenames.stream()
                                        .map(byFilename::get)
                                        .filter(Objects::nonNull)
                                        .map(m -> new RelevantMemory(m.getFilePath(), m.getMtimeMs()))
                                        .collect(Collectors.toList());
                            });
                });
    }

    /**
     * Convenience overload with empty defaults for recentTools and alreadySurfaced.
     */
    public CompletableFuture<List<RelevantMemory>> findRelevantMemories(
            String query,
            String memoryDir) {
        return findRelevantMemories(query, memoryDir, List.of(), Set.of());
    }

    /**
     * Ask the model to select which memories are relevant to the query.
     * Translated from selectRelevantMemories() in findRelevantMemories.ts
     */
    private CompletableFuture<List<String>> selectRelevantMemories(
            String query,
            List<MemoryScanService.MemoryHeader> memories,
            List<String> recentTools) {

        Set<String> validFilenames = memories.stream()
                .map(MemoryScanService.MemoryHeader::getFilename)
                .collect(Collectors.toSet());

        String manifest = memoryScanService.formatMemoryManifest(memories);

        String toolsSection = (recentTools != null && !recentTools.isEmpty())
                ? "\n\nRecently used tools: " + String.join(", ", recentTools)
                : "";

        String userContent = "Query: " + query + "\n\nAvailable memories:\n" + manifest + toolsSection;

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", userContent)
        );

        // JSON schema output: { "selected_memories": ["filename1", "filename2", ...] }
        SideQueryService.SideQueryOptions options = SideQueryService.SideQueryOptions.builder()
                .model(getDefaultSonnetModel())
                .system(SELECT_MEMORIES_SYSTEM_PROMPT)
                .skipSystemPromptPrefix(true)
                .messages(messages)
                .maxTokens(256)
                .outputFormat(Map.of(
                        "type", "json_schema",
                        "schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "selected_memories", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string")
                                        )
                                ),
                                "required", List.of("selected_memories"),
                                "additionalProperties", false
                        )
                ))
                .querySource("memdir_relevance")
                .build();

        return sideQueryService.sideQuery(options)
                .thenApply(response -> {
                    try {
                        JsonNode parsed = objectMapper.readTree(response);
                        JsonNode selected = parsed.get("selected_memories");
                        if (selected == null || !selected.isArray()) return List.<String>of();

                        List<String> result = new ArrayList<>();
                        selected.forEach(node -> {
                            String filename = node.asText();
                            if (validFilenames.contains(filename)) {
                                result.add(filename);
                            }
                        });
                        return result;
                    } catch (Exception e) {
                        log.debug("[memdir] selectRelevantMemories parse failed: {}", e.getMessage());
                        return List.<String>of();
                    }
                })
                .exceptionally(e -> {
                    log.warn("[memdir] selectRelevantMemories failed: {}", e.getMessage());
                    return List.of();
                });
    }

    /**
     * Get the default Sonnet model for side queries.
     * TODO: inject from ModelService when available.
     */
    private String getDefaultSonnetModel() {
        return "claude-sonnet-4-5";
    }
}
