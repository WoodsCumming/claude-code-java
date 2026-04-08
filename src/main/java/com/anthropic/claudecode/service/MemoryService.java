package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.model.Message;
import com.anthropic.claudecode.util.EnvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Memory extraction and management service.
 * Translated from src/services/extractMemories/extractMemories.ts
 *
 * Extracts durable memories from conversation transcripts and
 * writes them to the auto-memory directory.
 */
@Slf4j
@Service
public class MemoryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemoryService.class);


    private static final String MEMORY_DIR = "memory";
    private static final String AUTO_MEM_SUBDIR = "automem";

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public MemoryService(AnthropicClient anthropicClient, ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Get the auto-memory path for a project.
     * Translated from getAutoMemPath() in paths.ts
     */
    public String getAutoMemPath(String projectPath) {
        if (projectPath == null) return null;
        return EnvUtils.getClaudeConfigHomeDir() + "/projects/" +
            sanitizeProjectPath(projectPath) + "/" + MEMORY_DIR + "/" + AUTO_MEM_SUBDIR;
    }

    /**
     * Check if auto-memory is enabled.
     * Translated from isAutoMemoryEnabled() in paths.ts
     */
    public boolean isAutoMemoryEnabled() {
        return !EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_AUTO_MEMORY"));
    }

    /**
     * Save a memory entry.
     */
    public void saveMemory(String projectPath, String memoryType, String content) {
        String memDir = getAutoMemPath(projectPath);
        if (memDir == null) return;

        try {
            new File(memDir).mkdirs();
            String filename = memoryType + "-" + System.currentTimeMillis() + ".md";
            Files.writeString(Paths.get(memDir + "/" + filename), content);
            log.debug("Saved memory: {}", filename);
        } catch (Exception e) {
            log.warn("Could not save memory: {}", e.getMessage());
        }
    }

    /**
     * Load all memories for a project.
     */
    public List<MemoryEntry> loadMemories(String projectPath) {
        List<MemoryEntry> memories = new ArrayList<>();
        String memDir = getAutoMemPath(projectPath);
        if (memDir == null) return memories;

        File dir = new File(memDir);
        if (!dir.exists()) return memories;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null) return memories;

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                memories.add(new MemoryEntry(
                    file.getName().replace(".md", ""),
                    content,
                    file.lastModified()
                ));
            } catch (Exception e) {
                log.warn("Could not load memory {}: {}", file.getName(), e.getMessage());
            }
        }

        return memories;
    }

    /**
     * Extract memories from conversation messages.
     * Simplified version of extractMemories() in extractMemories.ts
     */
    public CompletableFuture<Void> extractMemories(
            List<Message> messages,
            String projectPath,
            String model) {

        if (!isAutoMemoryEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // Build extraction prompt
                String extractionPrompt = buildExtractionPrompt(messages);

                // Call API to extract memories
                List<Map<String, Object>> apiMessages = List.of(
                    Map.of("role", "user", "content", extractionPrompt)
                );

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                    .model(model)
                    .maxTokens(4096)
                    .messages(apiMessages)
                    .build();

                AnthropicClient.MessageResponse response = anthropicClient
                    .createMessage(request)
                    .get();

                // Parse and save memories
                String memoryContent = extractTextFromResponse(response);
                if (memoryContent != null && !memoryContent.isBlank()) {
                    saveMemory(projectPath, "session", memoryContent);
                }

            } catch (Exception e) {
                log.debug("Memory extraction failed: {}", e.getMessage());
            }
        });
    }

    private String buildExtractionPrompt(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Extract key learnings and preferences from this conversation that would be useful to remember for future sessions. Format as a concise bulleted list.\n\n");
        sb.append("Conversation:\n");

        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage userMsg) {
                sb.append("User: ");
                if (userMsg.getContent() != null) {
                    userMsg.getContent().stream()
                        .filter(b -> b instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock)
                        .forEach(b -> sb.append(((com.anthropic.claudecode.model.ContentBlock.TextBlock) b).getText()));
                }
                sb.append("\n");
            } else if (msg instanceof Message.AssistantMessage assistantMsg) {
                sb.append("Assistant: ");
                if (assistantMsg.getContent() != null) {
                    assistantMsg.getContent().stream()
                        .filter(b -> b instanceof com.anthropic.claudecode.model.ContentBlock.TextBlock)
                        .forEach(b -> sb.append(((com.anthropic.claudecode.model.ContentBlock.TextBlock) b).getText()));
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String extractTextFromResponse(AnthropicClient.MessageResponse response) {
        if (response.getContent() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : response.getContent()) {
            if ("text".equals(block.get("type"))) {
                sb.append(block.get("text"));
            }
        }
        return sb.toString();
    }

    private String sanitizeProjectPath(String path) {
        return path.replace("/", "-").replace("\\", "-").replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    public record MemoryEntry(String id, String content, long timestamp) {}
}
