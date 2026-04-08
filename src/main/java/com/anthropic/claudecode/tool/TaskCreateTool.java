package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Task creation tool for structured task lists.
 * Translated from src/tools/TaskCreateTool/TaskCreateTool.ts
 *
 * Creates tasks in the session task list for tracking progress.
 */
@Slf4j
@Component
public class TaskCreateTool extends AbstractTool<TaskCreateTool.Input, TaskCreateTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskCreateTool.class);


    public static final String TOOL_NAME = "TaskCreate";

    // In-memory task store (shared with TaskUpdate, TaskGet, TaskList)
    private static final Map<String, TaskItem> taskStore = new ConcurrentHashMap<>();
    private static final AtomicInteger taskIdCounter = new AtomicInteger(1);

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "subject", Map.of(
                    "type", "string",
                    "description", "A brief title for the task"
                ),
                "description", Map.of(
                    "type", "string",
                    "description", "What needs to be done"
                ),
                "activeForm", Map.of(
                    "type", "string",
                    "description", "Present continuous form shown in spinner when in_progress"
                )
            ),
            "required", List.of("subject", "description")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        String taskId = String.valueOf(taskIdCounter.getAndIncrement());
        TaskItem task = TaskItem.builder()
            .id(taskId)
            .subject(args.getSubject())
            .description(args.getDescription())
            .activeForm(args.getActiveForm())
            .status("pending")
            .build();

        taskStore.put(taskId, task);

        return futureResult(Output.builder()
            .taskId(taskId)
            .task(task)
            .message("Task #" + taskId + " created successfully: " + args.getSubject())
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Creating task: " + input.getSubject());
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content.getMessage()
        );
    }

    public static Map<String, TaskItem> getTaskStore() { return taskStore; }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    public static class Input {
        private String subject;
        private String description;
        private String activeForm;
        private Map<String, Object> metadata;
        public Input() {}
        public String getSubject() { return subject; }
        public void setSubject(String v) { subject = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getActiveForm() { return activeForm; }
        public void setActiveForm(String v) { activeForm = v; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> v) { metadata = v; }
        public static InputBuilder builder() { return new InputBuilder(); }
        public static class InputBuilder {
            private final Input i = new Input();
            public InputBuilder subject(String v) { i.subject = v; return this; }
            public InputBuilder description(String v) { i.description = v; return this; }
            public InputBuilder activeForm(String v) { i.activeForm = v; return this; }
            public InputBuilder metadata(Map<String, Object> v) { i.metadata = v; return this; }
            public Input build() { return i; }
        }
    }

    public static class Output {
        private String taskId;
        private TaskItem task;
        private String message;
        public Output() {}
        public String getTaskId() { return taskId; }
        public void setTaskId(String v) { taskId = v; }
        public TaskItem getTask() { return task; }
        public void setTask(TaskItem v) { task = v; }
        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private final Output o = new Output();
            public OutputBuilder taskId(String v) { o.taskId = v; return this; }
            public OutputBuilder task(TaskItem v) { o.task = v; return this; }
            public OutputBuilder message(String v) { o.message = v; return this; }
            public Output build() { return o; }
        }
    }

    public static class TaskItem {
        private String id;
        private String subject;
        private String description;
        private String activeForm;
        private String status;
        private String owner;
        private List<String> blocks;
        private List<String> blockedBy;
        private Map<String, Object> metadata;
        public TaskItem() {}
        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getSubject() { return subject; }
        public void setSubject(String v) { subject = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getActiveForm() { return activeForm; }
        public void setActiveForm(String v) { activeForm = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getOwner() { return owner; }
        public void setOwner(String v) { owner = v; }
        public List<String> getBlocks() { return blocks; }
        public void setBlocks(List<String> v) { blocks = v; }
        public List<String> getBlockedBy() { return blockedBy; }
        public void setBlockedBy(List<String> v) { blockedBy = v; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> v) { metadata = v; }
        public static TaskItemBuilder builder() { return new TaskItemBuilder(); }
        public static class TaskItemBuilder {
            private final TaskItem t = new TaskItem();
            public TaskItemBuilder id(String v) { t.id = v; return this; }
            public TaskItemBuilder subject(String v) { t.subject = v; return this; }
            public TaskItemBuilder description(String v) { t.description = v; return this; }
            public TaskItemBuilder activeForm(String v) { t.activeForm = v; return this; }
            public TaskItemBuilder status(String v) { t.status = v; return this; }
            public TaskItemBuilder owner(String v) { t.owner = v; return this; }
            public TaskItemBuilder blocks(List<String> v) { t.blocks = v; return this; }
            public TaskItemBuilder blockedBy(List<String> v) { t.blockedBy = v; return this; }
            public TaskItemBuilder metadata(Map<String, Object> v) { t.metadata = v; return this; }
            public TaskItem build() { return t; }
        }
    }
}
