package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * REPL tool for executing code in a REPL-like environment.
 * Translated from src/tools/REPLTool/
 *
 * The REPL tool provides a higher-level interface that wraps primitive tools
 * (Read, Write, Edit, Glob, Grep, Bash) and presents them as a single tool
 * to the model when REPL mode is enabled.
 */
@Slf4j
@Component
@SuppressWarnings({"unchecked", "rawtypes"})
public class ReplTool extends AbstractTool<Map<String, Object>, String> {



    public static final String REPL_TOOL_NAME = "REPL";

    @Autowired
    public ReplTool() {
    }

    @Override
    public String getName() {
        return REPL_TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Execute code in a REPL environment with access to file system tools";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> commandProp = new LinkedHashMap<>();
        commandProp.put("type", "string");
        commandProp.put("description", "The command or code to execute");
        properties.put("command", commandProp);

        schema.put("properties", properties);
        schema.put("required", List.of("command"));
        return schema;
    }

    @Override
    public CompletableFuture<ToolResult<String>> call(
            Map<String, Object> args,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<ToolProgress> onProgress) {

        String command = (String) args.get("command");
        if (command == null || command.isBlank()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("No command provided")
            );
        }

        log.debug("[REPL] Executing: {}", command);

        // In REPL mode, the model uses this tool to make primitive tool calls
        // The actual execution is handled by the tool orchestration layer
        return CompletableFuture.completedFuture(
            ToolResult.success("REPL execution completed")
        );
    }

    /**
     * Check if REPL mode is enabled.
     * Translated from isReplModeEnabled() in constants.ts
     */
    public static boolean isReplModeEnabled() {
        String replMode = System.getenv("CLAUDE_CODE_REPL");
        if (replMode != null && (replMode.equals("0") || replMode.equals("false"))) {
            return false;
        }
        String legacyMode = System.getenv("CLAUDE_REPL_MODE");
        return "1".equals(legacyMode) || "true".equalsIgnoreCase(legacyMode);
    }
}
