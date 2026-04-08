package com.anthropic.claudecode.config;

import com.anthropic.claudecode.service.QueryEngine;
import com.anthropic.claudecode.service.StreamingToolExecutor;
import com.anthropic.claudecode.service.ToolExecutionService;
import com.anthropic.claudecode.model.ToolUseContext;
import com.anthropic.claudecode.tool.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/**
 * Tool registration configuration.
 * Registers all available tools as Spring beans.
 *
 * Translated from src/tools.ts - the tool assembly function.
 */
@Configuration
public class ToolConfig {

    /**
     * Register all tools.
     * Translated from getTools() / assembleToolPool() in tools.ts
     */
    @Bean
    public QueryEngine.StreamingToolExecutorFactory streamingToolExecutorFactory(
            ToolExecutionService toolExecutionService) {
        return config -> {
            ToolUseContext ctx = new ToolUseContext();
            return new StreamingToolExecutor(ctx, toolExecutionService);
        };
    }

    @Bean
    public List<com.anthropic.claudecode.tool.Tool<?, ?>> tools(
            BashTool bashTool,
            FileReadTool fileReadTool,
            FileWriteTool fileWriteTool,
            FileEditTool fileEditTool,
            GlobTool globTool,
            GrepTool grepTool,
            WebSearchTool webSearchTool,
            WebFetchTool webFetchTool,
            AgentTool agentTool,
            TodoWriteTool todoWriteTool,
            TaskCreateTool taskCreateTool,
            TaskUpdateTool taskUpdateTool,
            TaskListTool taskListTool,
            TaskGetTool taskGetTool,
            NotebookEditTool notebookEditTool,
            AskUserQuestionTool askUserQuestionTool,
            EnterPlanModeTool enterPlanModeTool,
            ExitPlanModeTool exitPlanModeTool,
            CronCreateTool cronCreateTool,
            CronDeleteTool cronDeleteTool,
            CronListTool cronListTool,
            SleepTool sleepTool,
            SendMessageTool sendMessageTool,
            TaskOutputTool taskOutputTool,
            TaskStopTool taskStopTool,
            ConfigTool configTool,
            EnterWorktreeTool enterWorktreeTool,
            ExitWorktreeTool exitWorktreeTool,
            TeamCreateTool teamCreateTool,
            TeamDeleteTool teamDeleteTool,
            ReplTool replTool,
            BriefTool briefTool,
            SyntheticOutputTool syntheticOutputTool) {

        return List.of(
            // Core file tools
            fileReadTool,
            fileWriteTool,
            fileEditTool,
            globTool,
            grepTool,

            // Shell tools
            bashTool,

            // Web tools
            webSearchTool,
            webFetchTool,

            // Agent tools
            agentTool,
            sendMessageTool,

            // Task management
            todoWriteTool,
            taskCreateTool,
            taskUpdateTool,
            taskListTool,
            taskGetTool,

            // Notebook
            notebookEditTool,

            // Interactive
            askUserQuestionTool,
            enterPlanModeTool,
            exitPlanModeTool,

            // Scheduling
            cronCreateTool,
            cronDeleteTool,
            cronListTool,

            // Utilities
            sleepTool,

            // Background task management
            taskOutputTool,
            taskStopTool,

            // Configuration
            configTool,

            // Worktree management
            enterWorktreeTool,
            exitWorktreeTool,

            // Team management
            teamCreateTool,
            teamDeleteTool,

            // REPL and output tools
            replTool,
            briefTool,
            syntheticOutputTool
        );
    }
}
