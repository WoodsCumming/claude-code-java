package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Cron job creation tool.
 * Translated from src/tools/ScheduleCronTool/CronCreateTool.ts
 *
 * Schedules prompts to be enqueued at future times using cron expressions.
 */
@Slf4j
@Component
public class CronCreateTool extends AbstractTool<CronCreateTool.Input, CronCreateTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CronCreateTool.class);


    public static final String TOOL_NAME = "CronCreate";
    private static final int MAX_JOBS = 50;
    private static final int DEFAULT_MAX_AGE_DAYS = 7;

    // In-memory cron job store
    private static final Map<String, CronJob> cronJobs = new ConcurrentHashMap<>();
    private static final AtomicInteger jobIdCounter = new AtomicInteger(1);

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String getSearchHint() { return "schedule a recurring or one-shot prompt"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "cron", Map.of(
                    "type", "string",
                    "description", "Standard 5-field cron expression in local time"
                ),
                "prompt", Map.of(
                    "type", "string",
                    "description", "The prompt to enqueue at each fire time"
                ),
                "recurring", Map.of(
                    "type", "boolean",
                    "description", "true = fire on every cron match. false = fire once then delete"
                ),
                "durable", Map.of(
                    "type", "boolean",
                    "description", "true = persist across sessions. false (default) = in-memory only"
                )
            ),
            "required", List.of("cron", "prompt")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        if (cronJobs.size() >= MAX_JOBS) {
            return futureResult(Output.builder()
                .message("Error: Maximum number of cron jobs (" + MAX_JOBS + ") reached")
                .build());
        }

        String jobId = "cron-" + jobIdCounter.getAndIncrement();
        boolean recurring = args.getRecurring() != null ? args.getRecurring() : true;
        boolean durable = args.getDurable() != null ? args.getDurable() : false;

        CronJob job = CronJob.builder()
            .id(jobId)
            .cron(args.getCron())
            .prompt(args.getPrompt())
            .recurring(recurring)
            .durable(durable)
            .createdAt(System.currentTimeMillis())
            .build();

        cronJobs.put(jobId, job);

        String humanSchedule = cronToHuman(args.getCron(), recurring);

        log.info("Created cron job {}: {} ({})", jobId, args.getCron(), humanSchedule);

        return futureResult(Output.builder()
            .id(jobId)
            .humanSchedule(humanSchedule)
            .recurring(recurring)
            .durable(durable)
            .message("Scheduled: " + humanSchedule)
            .build());
    }

    private String cronToHuman(String cron, boolean recurring) {
        if (cron == null) return "unknown schedule";
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) return cron;

        // Simple human-readable description
        String minute = parts[0];
        String hour = parts[1];

        if ("*".equals(minute) && "*".equals(hour)) {
            return recurring ? "every minute" : "once at next minute";
        }
        if (minute.startsWith("*/")) {
            return recurring ? "every " + minute.substring(2) + " minutes" : "once";
        }
        if ("*".equals(minute)) {
            return recurring ? "every hour at minute 0" : "once at next hour";
        }

        return recurring
            ? "recurring: " + cron
            : "once: " + cron;
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Creating cron job: " + input.getCron());
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId,
            "content", content.getMessage() != null ? content.getMessage() : "Cron job created: " + content.getId());
    }

    public static Map<String, CronJob> getCronJobs() { return cronJobs; }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String cron;
        private String prompt;
        private Boolean recurring;
        private Boolean durable;
    
        public String getCron() { return cron; }
    
        public Boolean getDurable() { return durable; }
    
        public String getPrompt() { return prompt; }
    
        public Boolean getRecurring() { return recurring; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String id;
        private String humanSchedule;
        private boolean recurring;
        private Boolean durable;
        private String message;
    
        public String getId() { return id; }
    
        public String getMessage() { return message; }
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String id;
            private String humanSchedule;
            private boolean recurring;
            private Boolean durable;
            private String message;
            public OutputBuilder id(String v) { this.id = v; return this; }
            public OutputBuilder humanSchedule(String v) { this.humanSchedule = v; return this; }
            public OutputBuilder recurring(boolean v) { this.recurring = v; return this; }
            public OutputBuilder durable(Boolean v) { this.durable = v; return this; }
            public OutputBuilder message(String v) { this.message = v; return this; }
            public Output build() {
                Output o = new Output();
                o.id = id;
                o.humanSchedule = humanSchedule;
                o.recurring = recurring;
                o.durable = durable;
                o.message = message;
                return o;
            }
        }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class CronJob {
        private String id;
        private String cron;
        private String prompt;
        private boolean recurring;
        private boolean durable;
        private long createdAt;
    
        public static CronJobBuilder builder() { return new CronJobBuilder(); }
        public static class CronJobBuilder {
            private String id;
            private String cron;
            private String prompt;
            private boolean recurring;
            private boolean durable;
            private long createdAt;
            public CronJobBuilder id(String v) { this.id = v; return this; }
            public CronJobBuilder cron(String v) { this.cron = v; return this; }
            public CronJobBuilder prompt(String v) { this.prompt = v; return this; }
            public CronJobBuilder recurring(boolean v) { this.recurring = v; return this; }
            public CronJobBuilder durable(boolean v) { this.durable = v; return this; }
            public CronJobBuilder createdAt(long v) { this.createdAt = v; return this; }
            public CronJob build() {
                CronJob o = new CronJob();
                o.id = id;
                o.cron = cron;
                o.prompt = prompt;
                o.recurring = recurring;
                o.durable = durable;
                o.createdAt = createdAt;
                return o;
            }
        }
    }
}
