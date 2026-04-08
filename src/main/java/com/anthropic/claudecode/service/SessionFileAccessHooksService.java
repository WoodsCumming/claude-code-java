package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.MemoryFileDetection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Session file access analytics hooks service.
 * Translated from src/utils/sessionFileAccessHooks.ts
 *
 * Tracks access to session memory and transcript files via Read, Grep, Glob tools.
 * Also tracks memdir file access via Read, Grep, Glob, Edit, and Write tools.
 */
@Slf4j
@Service
public class SessionFileAccessHooksService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionFileAccessHooksService.class);

    // Tool name constants — mirrors the TypeScript *_TOOL_NAME constants
    public static final String FILE_READ_TOOL_NAME  = "Read";
    public static final String FILE_EDIT_TOOL_NAME  = "Edit";
    public static final String FILE_WRITE_TOOL_NAME = "Write";
    public static final String GREP_TOOL_NAME       = "Grep";
    public static final String GLOB_TOOL_NAME       = "Glob";

    /**
     * Detected session file type — mirrors the union type in sessionFileAccessHooks.ts
     */
    public enum SessionFileType {
        SESSION_MEMORY,
        SESSION_TRANSCRIPT
    }

    private final AnalyticsService analyticsService;
    private final HookService hookService;

    @Autowired
    public SessionFileAccessHooksService(AnalyticsService analyticsService,
                                          HookService hookService) {
        this.analyticsService = analyticsService;
        this.hookService = hookService;
    }

    /**
     * Extract a file path from a tool input map for the given tool name.
     * Covers Read, Edit, and Write (all use the "file_path" key).
     * Translated from getFilePathFromInput() in sessionFileAccessHooks.ts
     */
    public String getFilePathFromInput(String toolName, Map<String, Object> toolInput) {
        if (toolInput == null) return null;
        return switch (toolName) {
            case FILE_READ_TOOL_NAME,
                 FILE_EDIT_TOOL_NAME,
                 FILE_WRITE_TOOL_NAME -> (String) toolInput.get("file_path");
            default -> null;
        };
    }

    /**
     * Detect whether a tool invocation touches a session memory or transcript file.
     * Translated from getSessionFileTypeFromInput() in sessionFileAccessHooks.ts
     */
    public SessionFileType getSessionFileTypeFromInput(String toolName,
                                                        Map<String, Object> toolInput) {
        if (toolInput == null) return null;
        return switch (toolName) {
            case FILE_READ_TOOL_NAME -> {
                String path = (String) toolInput.get("file_path");
                yield path != null ? toSessionFileType(MemoryFileDetection.detectSessionFileType(path)) : null;
            }
            case GREP_TOOL_NAME -> {
                String path    = (String) toolInput.get("path");
                String glob    = (String) toolInput.get("glob");
                if (path != null) {
                    SessionFileType t = toSessionFileType(MemoryFileDetection.detectSessionFileType(path));
                    if (t != null) yield t;
                }
                if (glob != null) {
                    yield toSessionFileType(MemoryFileDetection.detectSessionFileType(glob));
                }
                yield null;
            }
            case GLOB_TOOL_NAME -> {
                String path    = (String) toolInput.get("path");
                String pattern = (String) toolInput.get("pattern");
                if (path != null) {
                    SessionFileType t = toSessionFileType(MemoryFileDetection.detectSessionFileType(path));
                    if (t != null) yield t;
                }
                if (pattern != null) {
                    yield toSessionFileType(MemoryFileDetection.detectSessionFileType(pattern));
                }
                yield null;
            }
            default -> null;
        };
    }

    private static SessionFileType toSessionFileType(java.util.Optional<String> opt) {
        return opt.map(s -> switch (s) {
            case "session" -> SessionFileType.SESSION_TRANSCRIPT;
            case "memory", "project", "claude_config" -> SessionFileType.SESSION_MEMORY;
            default -> null;
        }).orElse(null);
    }

    /**
     * Returns true when the tool invocation accesses a memory file (session memory
     * or a memdir / auto-mem file).
     * Translated from isMemoryFileAccess() in sessionFileAccessHooks.ts
     */
    public boolean isMemoryFileAccess(String toolName, Map<String, Object> toolInput) {
        if (getSessionFileTypeFromInput(toolName, toolInput) == SessionFileType.SESSION_MEMORY) {
            return true;
        }
        String filePath = getFilePathFromInput(toolName, toolInput);
        return filePath != null && MemoryFileDetection.isAutoMemFile(filePath);
    }

    /**
     * Handle a PostToolUse event: log analytics events for session/memory file access.
     * Translated from handleSessionFileAccess() in sessionFileAccessHooks.ts
     *
     * @param toolName  Name of the tool that was used.
     * @param toolInput The tool's input parameters.
     * @param subagentName Optional subagent name for analytics context.
     */
    public void handlePostToolUse(String toolName,
                                   Map<String, Object> toolInput,
                                   String subagentName) {
        SessionFileType fileType = getSessionFileTypeFromInput(toolName, toolInput);
        Map<String, Object> subagentProps = subagentName != null
                ? Map.of("subagent_name", subagentName)
                : Map.of();

        if (fileType == SessionFileType.SESSION_MEMORY) {
            analyticsService.logEvent("tengu_session_memory_accessed", subagentProps);
        } else if (fileType == SessionFileType.SESSION_TRANSCRIPT) {
            analyticsService.logEvent("tengu_transcript_accessed", subagentProps);
        }

        // Memdir / auto-mem access tracking
        String filePath = getFilePathFromInput(toolName, toolInput);
        if (filePath != null && MemoryFileDetection.isAutoMemFile(filePath)) {
            analyticsService.logEvent("tengu_memdir_accessed",
                    merge(Map.of("tool", toolName), subagentProps));
            switch (toolName) {
                case FILE_READ_TOOL_NAME  -> analyticsService.logEvent("tengu_memdir_file_read",  subagentProps);
                case FILE_EDIT_TOOL_NAME  -> analyticsService.logEvent("tengu_memdir_file_edit",  subagentProps);
                case FILE_WRITE_TOOL_NAME -> analyticsService.logEvent("tengu_memdir_file_write", subagentProps);
                default -> { /* not a memory-mutating tool */ }
            }
        }
    }

    /**
     * Register PostToolUse hooks for Read, Grep, Glob, Edit, and Write tools.
     * Translated from registerSessionFileAccessHooks() in sessionFileAccessHooks.ts
     */
    public void registerSessionFileAccessHooks() {
        String[] trackedTools = {
            FILE_READ_TOOL_NAME,
            GREP_TOOL_NAME,
            GLOB_TOOL_NAME,
            FILE_EDIT_TOOL_NAME,
            FILE_WRITE_TOOL_NAME
        };
        for (String toolName : trackedTools) {
            hookService.registerPostToolUseHook(toolName, (input, toolUseId) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> toolInput = (Map<String, Object>) input.get("tool_input");
                String subagent = (String) input.get("subagent_name");
                handlePostToolUse(toolName, toolInput, subagent);
            });
        }
        log.debug("Registered session file access hooks for {} tools", trackedTools.length);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        java.util.HashMap<String, Object> result = new java.util.HashMap<>(a);
        result.putAll(b);
        return result;
    }
}
