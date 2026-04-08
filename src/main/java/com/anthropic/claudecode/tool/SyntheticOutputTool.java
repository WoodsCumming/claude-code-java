package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Synthetic output tool for returning structured output.
 * Translated from src/tools/SyntheticOutputTool/SyntheticOutputTool.ts
 *
 * Used in non-interactive sessions to return structured JSON output.
 * Only enabled when isNonInteractiveSession is true.
 */
@Slf4j
@Component
public class SyntheticOutputTool extends AbstractTool<Map<String, Object>, String> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SyntheticOutputTool.class);


    public static final String SYNTHETIC_OUTPUT_TOOL_NAME = "StructuredOutput";

    private final ObjectMapper objectMapper;

    @Autowired
    public SyntheticOutputTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return SYNTHETIC_OUTPUT_TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Return structured output in the requested format";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        // Accepts any object since schema is provided dynamically
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return schema;
    }

    @Override
    public CompletableFuture<ToolResult<String>> call(
            Map<String, Object> args,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<ToolProgress> onProgress) {

        try {
            // Validate and return the input as structured output
            String output = objectMapper.writeValueAsString(args);
            return CompletableFuture.completedFuture(ToolResult.of(output));
        } catch (Exception e) {
            log.error("Failed to serialize structured output: {}", e.getMessage());
            return CompletableFuture.completedFuture(
                ToolResult.of("Failed to serialize output: " + e.getMessage())
            );
        }
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(String content, String toolUseId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "tool_result");
        result.put("tool_use_id", toolUseId);
        result.put("content", content);
        return result;
    }

    /**
     * Check if the synthetic output tool should be enabled.
     * Translated from isSyntheticOutputToolEnabled() in SyntheticOutputTool.ts
     */
    public static boolean isSyntheticOutputToolEnabled(boolean isNonInteractiveSession) {
        return isNonInteractiveSession;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return true;
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return true;
    }
}
