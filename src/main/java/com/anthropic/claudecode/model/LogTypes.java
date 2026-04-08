package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.*;

/**
 * Log-related types.
 * Translated from src/types/logs.ts
 */
public class LogTypes {

    /**
     * Serialized message for transcript storage.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SerializedMessage {
        private String type;
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

    /**
     * Log option for session listing.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LogOption {
        private String date;
        private String fullPath;
        private int messageCount;
        private Long fileSize;
        private boolean isSidechain;
        private boolean isLite;
        private String sessionId;
        private String teamName;
        private String agentName;
        private String agentColor;
        private boolean isTeammate;
        private String summary;
        private String customTitle;
        private String tag;
        private String gitBranch;
        private String projectPath;
        private Integer prNumber;
        private String prUrl;
        private String prRepository;
        private String mode;
        private String firstPrompt;
    }

    /**
     * Summary message type.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SummaryMessage {
        private String type = "summary";
        private String leafUuid;
        private String summary;

        public String getLeafUuid() { return leafUuid; }
        public void setLeafUuid(String v) { leafUuid = v; }
        public String getSummary() { return summary; }
        public void setSummary(String v) { summary = v; }
    }

    /**
     * File history snapshot message.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileHistorySnapshotMessage {
        private String type = "file_history_snapshot";
        private String messageId;
        private long timestamp;

        public String getMessageId() { return messageId; }
        public void setMessageId(String v) { messageId = v; }
    }

    /**
     * Attribution snapshot message.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttributionSnapshotMessage {
        private String type = "attribution_snapshot";
        private String sessionId;
        private long timestamp;

    }
}
