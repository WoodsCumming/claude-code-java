package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.FrontmatterParser;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Memory-directory scanning primitives.
 * Split out of findRelevantMemories so extractMemories can import the scan without
 * pulling in the API-client chain.
 * Translated from src/memdir/memoryScan.ts
 */
@Slf4j
@Service
public class MemoryScanService {



    private static final int MAX_MEMORY_FILES = 200;
    private static final int FRONTMATTER_MAX_LINES = 30;

    /**
     * Parsed header data for a single memory file.
     * Translated from MemoryHeader in memoryScan.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MemoryHeader {
        /** Relative path within the memory directory (e.g., "user_role.md") */
        private String filename;
        /** Absolute path to the file */
        private String filePath;
        /** File modification time in milliseconds since epoch */
        private long mtimeMs;
        /** One-line description from frontmatter, or null if absent */
        private String description;
        /** Memory type from frontmatter (user/feedback/project/reference), or null */
        private String type;

        public String getFilename() { return filename; }
        public void setFilename(String v) { filename = v; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String v) { filePath = v; }
        public long getMtimeMs() { return mtimeMs; }
        public void setMtimeMs(long v) { mtimeMs = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
    

    }

    /**
     * Scan a memory directory for .md files, read their frontmatter, and return
     * a header list sorted newest-first (capped at MAX_MEMORY_FILES). Shared by
     * findRelevantMemories (query-time recall) and extractMemories.
     *
     * Single-pass: stats and content are read together, then sorted.
     * Translated from scanMemoryFiles() in memoryScan.ts
     *
     * @param memoryDir the directory to scan
     * @return CompletableFuture resolving to list of memory headers, empty on error
     */
    public CompletableFuture<List<MemoryHeader>> scanMemoryFiles(String memoryDir) {
        return CompletableFuture.supplyAsync(() -> {
            Path dir = Path.of(memoryDir);
            if (!Files.isDirectory(dir)) return List.of();

            List<String> mdFiles;
            try (Stream<Path> walk = Files.walk(dir)) {
                mdFiles = walk
                        .filter(p -> p.toString().endsWith(".md"))
                        .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                        .map(p -> dir.relativize(p).toString())
                        .collect(Collectors.toList());
            } catch (IOException e) {
                log.debug("[memdir] Could not list {}: {}", memoryDir, e.getMessage());
                return List.of();
            }

            List<MemoryHeader> headers = new ArrayList<>();
            for (String relativePath : mdFiles) {
                Path filePath = dir.resolve(relativePath);
                try {
                    String content = readFirstNLines(filePath.toFile(), FRONTMATTER_MAX_LINES);
                    long mtimeMs = Files.getLastModifiedTime(filePath).toMillis();
                    FrontmatterParser.FrontmatterData frontmatter = FrontmatterParser.parseFrontmatter(content);

                    MemoryHeader header = new MemoryHeader();
                    header.setFilename(relativePath);
                    header.setFilePath(filePath.toString());
                    header.setMtimeMs(mtimeMs);
                    header.setDescription(frontmatter.getDescription());
                    header.setType(frontmatter.getType());
                    headers.add(header);
                } catch (Exception e) {
                    log.debug("[memdir] Could not scan {}: {}", relativePath, e.getMessage());
                }
            }

            // Sort newest-first and cap at MAX_MEMORY_FILES
            return headers.stream()
                    .sorted(Comparator.comparingLong(MemoryHeader::getMtimeMs).reversed())
                    .limit(MAX_MEMORY_FILES)
                    .collect(Collectors.toList());
        });
    }

    /**
     * Synchronous variant of scanMemoryFiles for callers that cannot use CompletableFuture.
     */
    public List<MemoryHeader> scanMemoryFilesSync(String memoryDir) {
        try {
            return scanMemoryFiles(memoryDir).get();
        } catch (Exception e) {
            log.debug("[memdir] scanMemoryFiles failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Format memory headers as a text manifest: one line per file with
     * [type] filename (ISO timestamp): description.
     * Used by both the recall selector prompt and the extraction-agent prompt.
     * Translated from formatMemoryManifest() in memoryScan.ts
     */
    public String formatMemoryManifest(List<MemoryHeader> memories) {
        if (memories.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MemoryHeader m : memories) {
            String tag = (m.getType() != null && !m.getType().isBlank())
                    ? "[" + m.getType() + "] "
                    : "";
            String ts = Instant.ofEpochMilli(m.getMtimeMs()).toString(); // ISO 8601
            if (m.getDescription() != null && !m.getDescription().isBlank()) {
                sb.append("- ").append(tag).append(m.getFilename())
                  .append(" (").append(ts).append("): ").append(m.getDescription())
                  .append("\n");
            } else {
                sb.append("- ").append(tag).append(m.getFilename())
                  .append(" (").append(ts).append(")\n");
            }
        }
        // Remove trailing newline to match TypeScript .join('\n') behavior
        String result = sb.toString();
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String readFirstNLines(File file, int maxLines) throws IOException {
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null && lineCount < maxLines) {
                sb.append(line).append("\n");
                lineCount++;
            }
        }
        return sb.toString();
    }
}
