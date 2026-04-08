package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.FileStateCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Query helper service.
 * Translated from src/utils/queryHelpers.ts
 *
 * Provides:
 *   - isResultSuccessful()          — checks whether a query result is usable
 *   - normalizeMessage()            — maps internal Message to SDKMessage
 *   - handleOrphanedPermission()    — replays a pending permission + tool result on resume
 *   - extractReadFilesFromMessages()— rebuilds FileStateCache from conversation history
 *   - extractBashToolsFromMessages()— collects CLI tool names used in Bash calls
 */
@Slf4j
@Service
public class QueryHelperService {



    // ---- Tool progress throttle (mirrors queryHelpers.ts constants) ----
    private static final int MAX_TOOL_PROGRESS_TRACKING_ENTRIES = 100;
    private static final long TOOL_PROGRESS_THROTTLE_MS = 30_000L;

    /** LRU-ish map: toolUseID → last-sent epoch millis. */
    private final Map<String, Long> toolProgressLastSentTime = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_TOOL_PROGRESS_TRACKING_ENTRIES;
        }
    };

    private static final Pattern SYSTEM_REMINDER_PATTERN =
        Pattern.compile("<system-reminder>[\\s\\S]*?</system-reminder>");

    /** Line-number prefix pattern: one or more digits followed by a tab. */
    private static final Pattern LINE_NUMBER_PREFIX = Pattern.compile("^\\d+\\t");

    private final SessionStorageService sessionStorageService;

    @Autowired
    public QueryHelperService(SessionStorageService sessionStorageService) {
        this.sessionStorageService = sessionStorageService;
    }

    // =========================================================================
    // isResultSuccessful
    // =========================================================================

    /**
     * Checks if the result should be considered successful based on the last message.
     *
     * Returns true if:
     *   - Last message is assistant with text/thinking content
     *   - Last message is user with only tool_result blocks
     *   - Last message is the user prompt but the API completed with end_turn
     *     (model chose to emit no content blocks)
     *
     * Translated from isResultSuccessful() in queryHelpers.ts
     */
    public boolean isResultSuccessful(Message message, String stopReason) {
        if (message == null) return false;

        if (message instanceof Message.AssistantMessage assistantMsg) {
            List<ContentBlock> content = assistantMsg.getContent();
            if (content == null || content.isEmpty()) {
                // end_turn with zero content blocks is still legitimate (API observed behaviour).
                return "end_turn".equals(stopReason);
            }
            ContentBlock last = content.get(content.size() - 1);
            return last instanceof ContentBlock.TextBlock
                || last instanceof ContentBlock.ThinkingBlock
                || last instanceof ContentBlock.RedactedThinkingBlock;
        }

        if (message instanceof Message.UserMessage userMsg) {
            List<ContentBlock> content = userMsg.getContent();
            if (content == null || content.isEmpty()) return false;
            // All blocks must be tool_result type.
            for (ContentBlock block : content) {
                if (!(block instanceof ContentBlock.ToolResultBlock)) return false;
            }
            return true;
        }

        // Carve-out: API completed with end_turn but yielded no assistant content.
        return "end_turn".equals(stopReason);
    }

    // =========================================================================
    // extractReadFilesFromMessages
    // =========================================================================

    /**
     * Extract read files from messages and rebuild a FileStateCache.
     *
     * First pass: find all FileReadTool / FileWriteTool / FileEditTool uses.
     * Second pass: find corresponding tool_result blocks and cache file content.
     *
     * Translated from extractReadFilesFromMessages() in queryHelpers.ts
     *
     * @param messages  full conversation history
     * @param cwd       current working directory for path expansion
     * @param maxSize   max entries in the returned cache (default 10)
     */
    public FileStateCache extractReadFilesFromMessages(
            List<Message> messages,
            String cwd,
            int maxSize) {

        FileStateCache cache = FileStateCache.withSizeLimit(maxSize > 0 ? maxSize : 10);

        // First pass: collect tool_use blocks from assistant messages.
        Map<String, String> fileReadToolUseIds = new LinkedHashMap<>();
        Map<String, FileWriteData> fileWriteToolUseIds = new LinkedHashMap<>();
        Map<String, String> fileEditToolUseIds = new LinkedHashMap<>();

        for (Message message : messages) {
            if (!(message instanceof Message.AssistantMessage assistantMsg)) continue;
            List<ContentBlock> content = assistantMsg.getContent();
            if (content == null) continue;

            for (ContentBlock block : content) {
                if (!(block instanceof ContentBlock.ToolUseBlock toolUse)) continue;

                switch (toolUse.getName()) {
                    case "Read" -> {
                        // Ranged reads (offset/limit set) are NOT added to the cache.
                        Object input = toolUse.getInput();
                        if (input instanceof Map<?, ?> inputMap) {
                            Object filePath = inputMap.get("file_path");
                            boolean hasOffset = inputMap.containsKey("offset");
                            boolean hasLimit  = inputMap.containsKey("limit");
                            if (filePath instanceof String fp && !hasOffset && !hasLimit) {
                                fileReadToolUseIds.put(toolUse.getId(), expandPath(fp, cwd));
                            }
                        }
                    }
                    case "Write" -> {
                        Object input = toolUse.getInput();
                        if (input instanceof Map<?, ?> inputMap) {
                            Object fp = inputMap.get("file_path");
                            Object ct = inputMap.get("content");
                            if (fp instanceof String filePath && ct instanceof String fileContent) {
                                fileWriteToolUseIds.put(toolUse.getId(),
                                    new FileWriteData(expandPath(filePath, cwd), fileContent));
                            }
                        }
                    }
                    case "Edit" -> {
                        Object input = toolUse.getInput();
                        if (input instanceof Map<?, ?> inputMap) {
                            Object fp = inputMap.get("file_path");
                            if (fp instanceof String filePath) {
                                fileEditToolUseIds.put(toolUse.getId(), expandPath(filePath, cwd));
                            }
                        }
                    }
                }
            }
        }

        // Second pass: match tool_result blocks and populate the cache.
        for (Message message : messages) {
            if (!(message instanceof Message.UserMessage userMsg)) continue;
            List<ContentBlock> content = userMsg.getContent();
            if (content == null) continue;

            for (ContentBlock block : content) {
                if (!(block instanceof ContentBlock.ToolResultBlock result)) continue;
                String toolUseId = result.getToolUseId();
                if (toolUseId == null) continue;

                // Handle Read tool results.
                String readPath = fileReadToolUseIds.get(toolUseId);
                if (readPath != null && result.getContent() instanceof String rawContent) {
                    String content2 = rawContent;
                    if (!content2.startsWith("FILE_UNCHANGED")) {
                        // Strip system-reminder blocks.
                        content2 = SYSTEM_REMINDER_PATTERN.matcher(content2).replaceAll("");
                        // Strip line-number prefixes (cat -n format).
                        String fileContent = Arrays.stream(content2.split("\n"))
                            .map(line -> LINE_NUMBER_PREFIX.matcher(line).replaceFirst(""))
                            .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b)
                            .trim();
                        if (userMsg.getTimestamp() != null) {
                            cache.set(readPath, fileContent, System.currentTimeMillis());
                        }
                    }
                }

                // Handle Write tool results — use content from the tool input.
                FileWriteData writeData = fileWriteToolUseIds.get(toolUseId);
                if (writeData != null && userMsg.getTimestamp() != null) {
                    cache.set(writeData.filePath(), writeData.content(),
                        System.currentTimeMillis());
                }

                // Handle Edit tool results — read current disk content.
                // (Skipped if the tool returned an error.)
                String editPath = fileEditToolUseIds.get(toolUseId);
                if (editPath != null && !Boolean.TRUE.equals(result.getIsError())) {
                    try {
                        String diskContent = java.nio.file.Files.readString(java.nio.file.Path.of(editPath));
                        long mtime = java.nio.file.Files.getLastModifiedTime(
                            java.nio.file.Path.of(editPath)).toMillis();
                        cache.set(editPath, diskContent, mtime);
                    } catch (java.io.IOException e) {
                        // File deleted or inaccessible since the Edit — skip silently.
                        log.debug("Could not read edited file at {}: {}", editPath, e.getMessage());
                    }
                }
            }
        }

        return cache;
    }

    // =========================================================================
    // extractBashToolsFromMessages
    // =========================================================================

    /**
     * Extract the top-level CLI tools used in Bash tool calls from message history.
     * Returns a deduplicated set of command names (e.g. "vercel", "aws", "git").
     *
     * Translated from extractBashToolsFromMessages() in queryHelpers.ts
     */
    public Set<String> extractBashToolsFromMessages(List<Message> messages) {
        Set<String> tools = new LinkedHashSet<>();
        for (Message message : messages) {
            if (!(message instanceof Message.AssistantMessage assistantMsg)) continue;
            List<ContentBlock> content = assistantMsg.getContent();
            if (content == null) continue;

            for (ContentBlock block : content) {
                if (!(block instanceof ContentBlock.ToolUseBlock toolUse)) continue;
                if (!"Bash".equals(toolUse.getName())) continue;

                Object input = toolUse.getInput();
                if (!(input instanceof Map<?, ?> inputMap)) continue;
                Object cmd = inputMap.get("command");
                if (!(cmd instanceof String command)) continue;

                String cliName = extractCliName(command);
                if (cliName != null) {
                    tools.add(cliName);
                }
            }
        }
        return tools;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static final Set<String> STRIPPED_COMMANDS = Set.of("sudo");

    /**
     * Extract the actual CLI name from a bash command string, skipping
     * env-var assignments (e.g. {@code FOO=bar vercel} → {@code vercel})
     * and prefixes in STRIPPED_COMMANDS.
     * Translated from extractCliName() in queryHelpers.ts
     */
    private String extractCliName(String command) {
        if (command == null || command.isBlank()) return null;
        String[] tokens = command.trim().split("\\s+");
        for (String token : tokens) {
            // Skip env-var assignments like FOO=bar
            if (token.matches("^[A-Za-z_]\\w*=.*")) continue;
            // Skip stripped prefixes (e.g. sudo)
            if (STRIPPED_COMMANDS.contains(token)) continue;
            return token;
        }
        return null;
    }

    /**
     * Expand a possibly-relative file path against the given cwd.
     */
    private String expandPath(String filePath, String cwd) {
        if (filePath.startsWith("/")) return filePath;
        return cwd + "/" + filePath;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private record FileWriteData(String filePath, String content) {}
}
