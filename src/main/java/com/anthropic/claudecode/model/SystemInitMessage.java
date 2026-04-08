package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * System init message for SDK consumers.
 * Translated from src/utils/messages/systemInit.ts
 *
 * The first message on the SDK stream carrying session metadata.
 */
@Data
@lombok.Builder

public class SystemInitMessage {

    private String type = "system";
    private String subtype = "init";
    private String cwd;
    private String sessionId;
    private List<String> tools;
    private List<McpServerInfo> mcpServers;
    private String model;
    private String permissionMode;
    private List<String> slashCommands;
    private String apiKeySource;
    private List<String> betas;
    private String claudeCodeVersion;
    private String outputStyle;
    private List<String> agents;
    private List<String> skills;
    private List<PluginInfo> plugins;
    private String uuid;
    private Boolean fastModeState;

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    
    public static class McpServerInfo {
        private String name;
        private String status;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    
    public static class PluginInfo {
        private String name;
        private String path;
        private String source;

        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
    }

    /**
     * Build the system init message.
     * Translated from buildSystemInitMessage() in systemInit.ts
     */
    public static SystemInitMessage build(
            List<String> tools,
            String model,
            String permissionMode,
            String cwd,
            String sessionId,
            String version) {

        return SystemInitMessage.builder()
            .type("system")
            .subtype("init")
            .cwd(cwd)
            .sessionId(sessionId)
            .tools(tools)
            .mcpServers(List.of())
            .model(model)
            .permissionMode(permissionMode)
            .slashCommands(List.of())
            .apiKeySource("env_var")
            .betas(List.of())
            .claudeCodeVersion(version)
            .outputStyle("default")
            .agents(List.of())
            .skills(List.of())
            .plugins(List.of())
            .uuid(UUID.randomUUID().toString())
            .build();
    }
}
