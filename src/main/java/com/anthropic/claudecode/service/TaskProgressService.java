package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Task progress service for formatting and emitting SDK progress events.
 * Translated from src/utils/task/outputFormatting.ts and src/utils/task/sdkProgress.ts
 *
 * Covers two TypeScript source files:
 *   - outputFormatting.ts: getMaxTaskOutputLength(), formatTaskOutput()
 *   - sdkProgress.ts:      emitTaskProgress()
 */
@Slf4j
@Service
public class TaskProgressService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskProgressService.class);


    /**
     * Upper limit for task output length (env-capped).
     * Translated from TASK_MAX_OUTPUT_UPPER_LIMIT in outputFormatting.ts
     */
    public static final int TASK_MAX_OUTPUT_UPPER_LIMIT = 160_000;

    /**
     * Default task output length.
     * Translated from TASK_MAX_OUTPUT_DEFAULT in outputFormatting.ts
     */
    public static final int TASK_MAX_OUTPUT_DEFAULT = 32_000;

    private final SdkEventQueueService sdkEventQueueService;
    private final TaskOutputDiskService taskOutputDiskService;

    @Autowired
    public TaskProgressService(SdkEventQueueService sdkEventQueueService,
                                TaskOutputDiskService taskOutputDiskService) {
        this.sdkEventQueueService = sdkEventQueueService;
        this.taskOutputDiskService = taskOutputDiskService;
    }

    // =========================================================================
    // outputFormatting.ts — output length + truncation
    // =========================================================================

    /**
     * Get the effective maximum task output length.
     * Reads TASK_MAX_OUTPUT_LENGTH env var, clamped to [0, UPPER_LIMIT].
     * Translated from getMaxTaskOutputLength() in outputFormatting.ts
     */
    public int getMaxTaskOutputLength() {
        String envVal = System.getenv("TASK_MAX_OUTPUT_LENGTH");
        if (envVal != null) {
            try {
                int parsed = Integer.parseInt(envVal.trim());
                return Math.min(Math.max(parsed, 0), TASK_MAX_OUTPUT_UPPER_LIMIT);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return TASK_MAX_OUTPUT_DEFAULT;
    }

    /**
     * Format task output for API consumption, truncating if too large.
     * When truncated, includes a header with the file path and returns
     * the last N characters that fit within the limit.
     * Translated from formatTaskOutput() in outputFormatting.ts
     */
    public FormatResult formatTaskOutput(String output, String taskId) {
        int maxLen = getMaxTaskOutputLength();
        if (output.length() <= maxLen) {
            return new FormatResult(output, false);
        }
        String filePath = taskOutputDiskService.getTaskOutputPath(taskId);
        String header = "[Truncated. Full output: " + filePath + "]\n\n";
        int availableSpace = maxLen - header.length();
        if (availableSpace <= 0) {
            return new FormatResult(header, true);
        }
        String truncated = output.substring(output.length() - availableSpace);
        return new FormatResult(header + truncated, true);
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    
    public static class FormatResult {
        private String content;
        private boolean wasTruncated;

        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public boolean isWasTruncated() { return wasTruncated; }
        public void setWasTruncated(boolean v) { wasTruncated = v; }
    }

    // =========================================================================
    // sdkProgress.ts — task_progress SDK event emission
    // =========================================================================

    /**
     * Emit a task_progress SDK event.
     * Translated from emitTaskProgress() in sdkProgress.ts
     *
     * Shared by background agents (per tool_use in runAsyncAgentLifecycle)
     * and workflows (per flushProgress batch).
     */
    public void emitTaskProgress(EmitTaskProgressParams params) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "system");
        event.put("subtype", "task_progress");
        event.put("task_id", params.getTaskId());
        if (params.getToolUseId() != null) {
            event.put("tool_use_id", params.getToolUseId());
        }
        event.put("description", params.getDescription());

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("total_tokens", params.getTotalTokens());
        usage.put("tool_uses", params.getToolUses());
        usage.put("duration_ms", System.currentTimeMillis() - params.getStartTime());
        event.put("usage", usage);

        if (params.getLastToolName() != null) {
            event.put("last_tool_name", params.getLastToolName());
        }
        if (params.getSummary() != null) {
            event.put("summary", params.getSummary());
        }
        if (params.getWorkflowProgress() != null && !params.getWorkflowProgress().isEmpty()) {
            event.put("workflow_progress", params.getWorkflowProgress());
        }

        sdkEventQueueService.enqueueSdkEvent(event);
    }

    /**
     * Convenience overload for callers that don't need workflow_progress.
     */
    public void emitTaskProgress(String taskId, String toolUseId, String description,
                                  long startTime, int totalTokens, int toolUses,
                                  String lastToolName, String summary) {
        EmitTaskProgressParams params = EmitTaskProgressParams.builder()
            .taskId(taskId)
            .toolUseId(toolUseId)
            .description(description)
            .startTime(startTime)
            .totalTokens(totalTokens)
            .toolUses(toolUses)
            .lastToolName(lastToolName)
            .summary(summary)
            .build();
        emitTaskProgress(params);
    }

    /**
     * Params for emitTaskProgress.
     * Translated from the params object in emitTaskProgress() in sdkProgress.ts
     */
    @Data
    @lombok.Builder
    
    public static class EmitTaskProgressParams {
        private String taskId;
        private String toolUseId;          // nullable
        private String description;
        private long startTime;
        private int totalTokens;
        private int toolUses;
        private String lastToolName;       // nullable
        private String summary;            // nullable
        private List<Map<String, Object>> workflowProgress;  // nullable — SdkWorkflowProgress[]
    }
}
