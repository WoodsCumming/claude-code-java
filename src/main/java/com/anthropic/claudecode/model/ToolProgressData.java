package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

/**
 * Tool progress data types.
 * Translated from src/types/tools.ts (inferred from usage)
 */
public sealed interface ToolProgressData permits
        ToolProgressData.BashProgress,
        ToolProgressData.WebSearchProgress,
        ToolProgressData.MCPProgress,
        ToolProgressData.AgentToolProgress,
        ToolProgressData.SkillToolProgress,
        ToolProgressData.TaskOutputProgress,
        ToolProgressData.REPLToolProgress {

    String getType();

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor 
    final class BashProgress implements ToolProgressData {
        private final String type = "bash_progress";
        private String stdout;
        private String stderr;
        private boolean running;
        @Override public String getType() { return type; }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor 
    final class WebSearchProgress implements ToolProgressData {
        private final String type = "web_search_progress";
        private String query;
        private String status;
        @Override public String getType() { return type; }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor 
    final class MCPProgress implements ToolProgressData {
        private final String type = "mcp_progress";
        private String serverName;
        private String toolName;
        private String status;
        @Override public String getType() { return type; }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor 
    final class AgentToolProgress implements ToolProgressData {
        private final String type = "agent_tool_progress";
        private String agentId;
        private String message;
        private String status;
        @Override public String getType() { return type; }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor 
    final class SkillToolProgress implements ToolProgressData {
        private final String type = "skill_tool_progress";
        private String skillName;
        private String status;
        @Override public String getType() { return type; }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor 
    final class TaskOutputProgress implements ToolProgressData {
        private final String type = "task_output_progress";
        private String taskId;
        private String output;
        private String status;
        @Override public String getType() { return type; }
    }

    @Data @Builder @NoArgsConstructor
@AllArgsConstructor 
    final class REPLToolProgress implements ToolProgressData {
        private final String type = "repl_tool_progress";
        private String output;
        @Override public String getType() { return type; }
    }
}
