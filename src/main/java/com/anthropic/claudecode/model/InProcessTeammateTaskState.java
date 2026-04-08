package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.*;

/**
 * In-process teammate task state.
 * Translated from src/tasks/InProcessTeammateTask/types.ts
 */
public class InProcessTeammateTaskState {

    /**
     * Teammate identity stored in task state.
     */
    @Data
    public static class TeammateIdentity {
        private String agentId;
        private String agentName;
        private String teamName;
        private String color;
        private boolean planModeRequired;
        private String parentSessionId;

        public TeammateIdentity() {}
        public TeammateIdentity(String agentId, String agentName, String teamName, String color, boolean planModeRequired, String parentSessionId) {
            this.agentId = agentId; this.agentName = agentName; this.teamName = teamName;
            this.color = color; this.planModeRequired = planModeRequired; this.parentSessionId = parentSessionId;
        }
        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { agentId = v; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String v) { agentName = v; }
        public String getTeamName() { return teamName; }
        public void setTeamName(String v) { teamName = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public boolean isPlanModeRequired() { return planModeRequired; }
        public void setPlanModeRequired(boolean v) { planModeRequired = v; }
        public String getParentSessionId() { return parentSessionId; }
        public void setParentSessionId(String v) { parentSessionId = v; }
    }

    /**
     * In-process teammate task state.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskState {
        private String id;
        private String type = "in_process_teammate";
        private String status = "running";
        private String description;
        private String toolUseId;
        private long startTime;
        private TeammateIdentity identity;
        private String prompt;
        private String model;
        private boolean awaitingPlanApproval;
        private boolean planApproved;
        private List<Message> messages = new ArrayList<>();
        private List<String> outputFile = new ArrayList<>();
        private int outputOffset = 0;
        private boolean notified = false;

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String v) { toolUseId = v; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long v) { startTime = v; }
        public TeammateIdentity getIdentity() { return identity; }
        public void setIdentity(TeammateIdentity v) { identity = v; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public boolean isAwaitingPlanApproval() { return awaitingPlanApproval; }
        public void setAwaitingPlanApproval(boolean v) { awaitingPlanApproval = v; }
        public boolean isPlanApproved() { return planApproved; }
        public void setPlanApproved(boolean v) { planApproved = v; }
        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> v) { messages = v; }
        public List<String> getOutputFile() { return outputFile; }
        public void setOutputFile(List<String> v) { outputFile = v; }
        public int getOutputOffset() { return outputOffset; }
        public void setOutputOffset(int v) { outputOffset = v; }
        public boolean isNotified() { return notified; }
        public void setNotified(boolean v) { notified = v; }
    }

    /**
     * Check if a task state is an in-process teammate task.
     * Translated from isInProcessTeammateTask() in types.ts
     */
    public static boolean isInProcessTeammateTask(Object task) {
        if (!(task instanceof TaskState ts)) return false;
        return "in_process_teammate".equals(ts.getType());
    }
}
