package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.*;

/**
 * Local agent task state types.
 * Translated from src/tasks/LocalAgentTask/LocalAgentTask.tsx
 */
public class LocalAgentTaskState {

    /**
     * Tool activity during agent execution.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ToolActivity {
        private String toolName;
        private Map<String, Object> input;
        private String activityDescription;
        private boolean search;
        private boolean read;

        public String getToolName() { return toolName; }
        public void setToolName(String v) { toolName = v; }
        public Map<String, Object> getInput() { return input; }
        public void setInput(Map<String, Object> v) { input = v; }
        public String getActivityDescription() { return activityDescription; }
        public void setActivityDescription(String v) { activityDescription = v; }
        public boolean isSearch() { return search; }
        public void setSearch(boolean v) { search = v; }
        public boolean isRead() { return read; }
        public void setRead(boolean v) { read = v; }
    }

    /**
     * Agent progress tracking.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AgentProgress {
        private int toolUseCount;
        private int tokenCount;
        private ToolActivity lastActivity;
        private List<ToolActivity> recentActivities;
        private String summary;

        public int getToolUseCount() { return toolUseCount; }
        public void setToolUseCount(int v) { toolUseCount = v; }
        public int getTokenCount() { return tokenCount; }
        public void setTokenCount(int v) { tokenCount = v; }
        public ToolActivity getLastActivity() { return lastActivity; }
        public void setLastActivity(ToolActivity v) { lastActivity = v; }
        public List<ToolActivity> getRecentActivities() { return recentActivities; }
        public void setRecentActivities(List<ToolActivity> v) { recentActivities = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
    }

    /**
     * Local agent task state.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskState {
        private String id;
        private String type = "local_agent";
        private String status = "pending"; // pending | running | completed | failed | killed
        private String description;
        private String toolUseId;
        private long startTime;
        private AgentProgress progress;
        private String outputFile;
        private int outputOffset = 0;
        private boolean notified = false;
        private boolean backgrounded = false;
        private String agentType;
        private String model;
        private List<Message> messages = new ArrayList<>();

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
        public AgentProgress getProgress() { return progress; }
        public void setProgress(AgentProgress v) { progress = v; }
        public String getOutputFile() { return outputFile; }
        public void setOutputFile(String v) { outputFile = v; }
        public int getOutputOffset() { return outputOffset; }
        public void setOutputOffset(int v) { outputOffset = v; }
        public boolean isNotified() { return notified; }
        public void setNotified(boolean v) { notified = v; }
        public boolean isBackgrounded() { return backgrounded; }
        public void setBackgrounded(boolean v) { backgrounded = v; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> v) { messages = v; }
    }

    /**
     * Check if a task is a local agent task.
     */
    public static boolean isLocalAgentTask(Object task) {
        if (!(task instanceof TaskState ts)) return false;
        return "local_agent".equals(ts.getType());
    }
}
