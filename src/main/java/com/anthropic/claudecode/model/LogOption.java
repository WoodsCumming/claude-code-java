package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import java.util.*;

/**
 * Log/session option for session listing.
 * Translated from LogOption type in src/types/logs.ts
 */
@Data
@lombok.Builder

public class LogOption {

    private String date;
    private List<SerializedMessage> messages;
    private String fullPath;
    private int value;
    private Date created;
    private Date modified;
    private String firstPrompt;
    private int messageCount;
    private Long fileSize;
    private boolean isSidechain;
    private Boolean isLite;
    private String sessionId;
    private String teamName;
    private String agentName;
    private String agentColor;
    private String agentSetting;
    private Boolean isTeammate;
    private String leafUuid;
    private String summary;
    private String customTitle;
    private String tag;
    private String gitBranch;
    private String projectPath;
    private Integer prNumber;
    private String prUrl;
    private String prRepository;
    private String mode;

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SerializedMessage {
        private String type;
        private String uuid;
        private String cwd;
        private String userType;
        private String entrypoint;
        private String sessionId;
        private String timestamp;
        private String version;
        private String gitBranch;
        private String slug;

        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public String getUserType() { return userType; }
        public void setUserType(String v) { userType = v; }
        public String getEntrypoint() { return entrypoint; }
        public void setEntrypoint(String v) { entrypoint = v; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String v) { timestamp = v; }
        public String getVersion() { return version; }
        public void setVersion(String v) { version = v; }
        public String getGitBranch() { return gitBranch; }
        public void setGitBranch(String v) { gitBranch = v; }
        public String getSlug() { return slug; }
        public void setSlug(String v) { slug = v; }
    }
}
