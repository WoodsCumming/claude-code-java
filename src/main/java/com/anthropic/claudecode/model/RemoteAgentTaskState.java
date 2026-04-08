package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.*;

/**
 * Remote agent task state types.
 * Translated from src/tasks/RemoteAgentTask/RemoteAgentTask.tsx
 */
public class RemoteAgentTaskState {

    public enum RemoteTaskType {
        BACKGROUND("background"),
        PR_REVIEW("pr_review"),
        ISSUE("issue");

        private final String value;
        RemoteTaskType(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RemoteTaskMetadata {
        private Integer prNumber;
        private String repo;
        private String issueNumber;
        private String branch;

        public Integer getPrNumber() { return prNumber; }
        public void setPrNumber(Integer v) { prNumber = v; }
        public String getRepo() { return repo; }
        public void setRepo(String v) { repo = v; }
        public String getIssueNumber() { return issueNumber; }
        public void setIssueNumber(String v) { issueNumber = v; }
        public String getBranch() { return branch; }
        public void setBranch(String v) { branch = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskState {
        private String id;
        private String type = "remote_agent";
        private String status = "running";
        private String description;
        private String toolUseId;
        private long startTime;
        private RemoteTaskType remoteTaskType;
        private RemoteTaskMetadata remoteTaskMetadata;
        private String sessionId;
        private String command;
        private String title;
        private List<TodoItem> todoList = new ArrayList<>();
        private String outputFile;
        private int outputOffset = 0;
        private boolean notified = false;
        private List<Map<String, Object>> log = new ArrayList<>();

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
        public RemoteTaskType getRemoteTaskType() { return remoteTaskType; }
        public void setRemoteTaskType(RemoteTaskType v) { remoteTaskType = v; }
        public RemoteTaskMetadata getRemoteTaskMetadata() { return remoteTaskMetadata; }
        public void setRemoteTaskMetadata(RemoteTaskMetadata v) { remoteTaskMetadata = v; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getCommand() { return command; }
        public void setCommand(String v) { command = v; }
        public String getTitle() { return title; }
        public void setTitle(String v) { title = v; }
        public List<TodoItem> getTodoList() { return todoList; }
        public void setTodoList(List<TodoItem> v) { todoList = v; }
        public String getOutputFile() { return outputFile; }
        public void setOutputFile(String v) { outputFile = v; }
        public int getOutputOffset() { return outputOffset; }
        public void setOutputOffset(int v) { outputOffset = v; }
        public boolean isNotified() { return notified; }
        public void setNotified(boolean v) { notified = v; }
        public List<Map<String, Object>> getLog() { return log; }
        public void setLog(List<Map<String, Object>> v) { log = v; }
    }
}
