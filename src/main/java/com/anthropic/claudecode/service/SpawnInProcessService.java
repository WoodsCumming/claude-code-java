package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.InProcessTeammateTaskState;
import com.anthropic.claudecode.util.AgentIdUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * In-process teammate spawning service.
 * Translated from src/utils/swarm/spawnInProcess.ts
 *
 * Creates and registers in-process teammate tasks.
 */
@Slf4j
@Service
public class SpawnInProcessService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpawnInProcessService.class);


    private final TaskFrameworkService taskFrameworkService;
    private final SdkEventQueueService sdkEventQueueService;
    private final AppState appState;

    @Autowired
    public SpawnInProcessService(TaskFrameworkService taskFrameworkService,
                                  SdkEventQueueService sdkEventQueueService,
                                  AppState appState) {
        this.taskFrameworkService = taskFrameworkService;
        this.sdkEventQueueService = sdkEventQueueService;
        this.appState = appState;
    }

    /**
     * Spawn an in-process teammate.
     * Translated from spawnInProcessTeammate() in spawnInProcess.ts
     */
    public CompletableFuture<InProcessSpawnResult> spawnInProcessTeammate(
            InProcessSpawnConfig config) {

        return CompletableFuture.supplyAsync(() -> {
            String agentId = AgentIdUtils.createAgentId();
            String taskId = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);

            String sessionId = appState.getReplBridgeSessionId();

            InProcessTeammateTaskState.TeammateIdentity identity =
                new InProcessTeammateTaskState.TeammateIdentity(
                    agentId,
                    config.getAgentName(),
                    config.getTeamName(),
                    config.getColor(),
                    config.isPlanModeRequired(),
                    sessionId
                );

            // Register the task
            TaskFrameworkService.TaskState state = TaskFrameworkService.TaskState.builder()
                .id(taskId)
                .type("in_process_teammate")
                .status("running")
                .description(config.getDescription())
                .startTime(System.currentTimeMillis())
                .metadata(new LinkedHashMap<>())
                .build();
            taskFrameworkService.registerTask(state);

            log.info("Spawned in-process teammate: {} ({})", config.getAgentName(), agentId);
            return new InProcessSpawnResult(taskId, agentId, config.getAgentName(), true, null);
        });
    }

    /**
     * Request graceful shutdown of a teammate.
     * Translated from requestShutdown() in spawnInProcess.ts
     */
    public boolean requestShutdown(String agentId, String reason, Object context) {
        log.info("[SpawnInProcess] requestShutdown for {} reason: {}", agentId, reason);
        // In a full implementation, send a shutdown signal to the running agent thread.
        return true;
    }

    /**
     * Force kill an in-process teammate.
     * Translated from killInProcessTeammate() in spawnInProcess.ts
     */
    public boolean killInProcessTeammate(String agentId, Object context) {
        log.info("[SpawnInProcess] killInProcessTeammate for {}", agentId);
        // In a full implementation, interrupt the agent thread and clean up.
        return true;
    }

    /**
     * Check if a teammate is still active.
     * Translated from isTeammateActive() in spawnInProcess.ts
     */
    public boolean isTeammateActive(String agentId, Object context) {
        // In a full implementation, check if the agent task is still running.
        return taskFrameworkService.getTask(agentId).isPresent();
    }

    public static class InProcessSpawnConfig {
        private String agentName;
        private String teamName;
        private String agentType;
        private String prompt;
        private String model;
        private String color;
        private boolean planModeRequired;
        private String description;
        private String cwd;
        public InProcessSpawnConfig() {}
        public InProcessSpawnConfig(String agentName, String teamName, String agentType, String prompt,
                                     String model, String color, boolean planModeRequired,
                                     String description, String cwd) {
            this.agentName = agentName; this.teamName = teamName; this.agentType = agentType;
            this.prompt = prompt; this.model = model; this.color = color;
            this.planModeRequired = planModeRequired; this.description = description; this.cwd = cwd;
        }
        public String getAgentName() { return agentName; }
        public void setAgentName(String v) { agentName = v; }
        public String getTeamName() { return teamName; }
        public void setTeamName(String v) { teamName = v; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public boolean isPlanModeRequired() { return planModeRequired; }
        public void setPlanModeRequired(boolean v) { planModeRequired = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public static InProcessSpawnConfigBuilder builder() { return new InProcessSpawnConfigBuilder(); }
        public static class InProcessSpawnConfigBuilder {
            private final InProcessSpawnConfig c = new InProcessSpawnConfig();
            public InProcessSpawnConfigBuilder agentName(String v) { c.agentName = v; return this; }
            public InProcessSpawnConfigBuilder teamName(String v) { c.teamName = v; return this; }
            public InProcessSpawnConfigBuilder agentType(String v) { c.agentType = v; return this; }
            public InProcessSpawnConfigBuilder prompt(String v) { c.prompt = v; return this; }
            public InProcessSpawnConfigBuilder model(String v) { c.model = v; return this; }
            public InProcessSpawnConfigBuilder color(String v) { c.color = v; return this; }
            public InProcessSpawnConfigBuilder planModeRequired(boolean v) { c.planModeRequired = v; return this; }
            public InProcessSpawnConfigBuilder description(String v) { c.description = v; return this; }
            public InProcessSpawnConfigBuilder cwd(String v) { c.cwd = v; return this; }
            public InProcessSpawnConfig build() { return c; }
        }
    }

    public static class InProcessSpawnResult {
        private String taskId;
        private String agentId;
        private String agentName;
        private boolean success;
        private String error;
        public InProcessSpawnResult() {}
        public InProcessSpawnResult(String taskId, String agentId, String agentName, boolean success, String error) {
            this.taskId = taskId; this.agentId = agentId; this.agentName = agentName; this.success = success; this.error = error;
        }
        public String getTaskId() { return taskId; }
        public String getAgentId() { return agentId; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
    }
}
