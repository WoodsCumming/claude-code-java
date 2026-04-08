package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Resume agent background service.
 * Translated from src/tools/AgentTool/resumeAgent.ts
 *
 * Resumes a previously started async background agent from its persisted transcript.
 */
@Slf4j
@Service
public class ResumeAgentService {



    private final RunAgentService runAgentService;
    private final SessionStorageService sessionStorageService;
    private final AgentToolUtilsService agentToolUtilsService;
    private final TaskOutputDiskService taskOutputDiskService;
    private final BackgroundTaskService backgroundTaskService;

    @Autowired
    public ResumeAgentService(RunAgentService runAgentService,
                              SessionStorageService sessionStorageService,
                              AgentToolUtilsService agentToolUtilsService,
                              TaskOutputDiskService taskOutputDiskService,
                              BackgroundTaskService backgroundTaskService) {
        this.runAgentService = runAgentService;
        this.sessionStorageService = sessionStorageService;
        this.agentToolUtilsService = agentToolUtilsService;
        this.taskOutputDiskService = taskOutputDiskService;
        this.backgroundTaskService = backgroundTaskService;
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Mirrors the TypeScript ResumeAgentResult type.
     */
    public record ResumeAgentResult(
        String agentId,
        String description,
        String outputFile
    ) {}

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Resume a background agent identified by {@code agentId}.
     * Translated from resumeAgentBackground() in resumeAgent.ts.
     *
     * Reads the persisted transcript, reconstructs the agent definition, re-registers
     * the agent as a background task, and re-launches runAgent() asynchronously.
     * Returns immediately with the agentId, description, and output-file path so the
     * caller can track progress.
     */
    public CompletableFuture<ResumeAgentResult> resumeAgentBackground(
            String agentId,
            String prompt,
            ToolUseContext toolUseContext,
            List<AgentDefinition> activeAgentDefinitions) {

        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            // Load transcript + metadata in parallel
            SessionStorageService.AgentTranscript transcript;
            SessionStorageService.AgentMetadata meta;
            try {
                transcript = sessionStorageService.getAgentTranscript(agentId).get();
                meta = sessionStorageService.readAgentMetadata(agentId).get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load agent transcript/metadata for " + agentId, e);
            }

            if (transcript == null) {
                throw new IllegalStateException("No transcript found for agent ID: " + agentId);
            }

            // Filter transcript messages (mirrors TypeScript filter chain)
            List<Message> resumedMessages = filterTranscriptMessages(transcript.messages());

            // Reconstruct content replacement state
            Object resumedReplacementState = reconstructReplacementState(
                toolUseContext, resumedMessages, transcript.contentReplacements());

            // Resolve worktree path — fall back gracefully if the directory was removed
            String resumedWorktreePath = resolveWorktreePath(meta);

            // If the worktree still exists, bump its mtime so stale-cleanup skips it (#22355)
            if (resumedWorktreePath != null) {
                bumpMtime(Path.of(resumedWorktreePath));
            }

            // Select the agent definition
            AgentDefinition selectedAgent = selectAgentDefinition(
                meta, activeAgentDefinitions, toolUseContext);

            boolean isResumedFork = meta != null
                && ForkSubagentService.FORK_SUBAGENT_TYPE.equals(meta.agentType());

            String uiDescription = (meta != null && meta.description() != null)
                ? meta.description()
                : "(resumed)";

            // Build the full message list: transcript messages + new prompt
            List<Message> allMessages = new ArrayList<>(resumedMessages);
            allMessages.add(Message.userMessage(prompt));

            // Resolve tools for the worker
            List<Tool<?, ?>> workerTools = isResumedFork
                ? getTools(toolUseContext)
                : agentToolUtilsService.assembleWorkerToolPool(selectedAgent, toolUseContext);

            // Build run params
            RunAgentService.RunAgentParams runAgentParams = new RunAgentService.RunAgentParams(
                selectedAgent,
                allMessages,
                toolUseContext,
                /* isAsync */ true,
                /* canShowPermissionPrompts */ null,
                /* forkContextMessages */ null,
                agentToolUtilsService.getQuerySourceForAgent(selectedAgent),
                /* override */ isResumedFork
                    ? new RunAgentService.RunAgentOverride(null, null,
                        getRenderedSystemPrompt(toolUseContext), null, null)
                    : null,
                /* model */ null,
                /* maxTurns */ null,
                /* preserveToolUseResults */ null,
                workerTools,
                /* allowedTools */ null,
                /* onCacheSafeParams */ null,
                resumedReplacementState,
                /* useExactTools */ isResumedFork ? Boolean.TRUE : null,
                resumedWorktreePath,
                meta != null ? meta.description() : null,
                /* transcriptSubdir */ null,
                /* onQueryProgress */ null
            );

            // Register as background task
            backgroundTaskService.registerAsyncAgent(
                agentId, uiDescription, prompt, selectedAgent, toolUseContext);

            // Launch agent asynchronously — fire-and-forget
            CompletableFuture.runAsync(() -> {
                try {
                    if (resumedWorktreePath != null) {
                        System.setProperty("user.dir", resumedWorktreePath);
                    }
                    runAgentService.runAgent(runAgentParams)
                        .whenComplete((msgs, err) -> {
                            if (err != null) {
                                log.error("[ResumeAgent] Agent {} failed: {}", agentId, err.getMessage(), err);
                            } else {
                                log.debug("[ResumeAgent] Agent {} completed with {} messages",
                                    agentId, msgs != null ? msgs.size() : 0);
                            }
                            backgroundTaskService.completeAsyncAgent(agentId, err);
                        });
                } catch (Exception e) {
                    log.error("[ResumeAgent] Failed to launch agent {}: {}", agentId, e.getMessage(), e);
                    backgroundTaskService.completeAsyncAgent(agentId, e);
                }
            });

            String outputFile = taskOutputDiskService.getTaskOutputPath(agentId);
            return new ResumeAgentResult(agentId, uiDescription, outputFile);
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Apply the same message-filter pipeline used in the TypeScript resumeAgentBackground:
     * filterWhitespaceOnlyAssistantMessages → filterOrphanedThinkingOnlyMessages
     * → filterUnresolvedToolUses
     */
    private List<Message> filterTranscriptMessages(List<Message> messages) {
        if (messages == null) return new ArrayList<>();
        // Mirror TypeScript filter chain order
        List<Message> filtered = Message.filterWhitespaceOnlyAssistantMessages(messages);
        filtered = Message.filterOrphanedThinkingOnlyMessages(filtered);
        filtered = Message.filterUnresolvedToolUses(filtered);
        return filtered;
    }

    /**
     * Reconstruct content replacement state for stable prompt cache replay.
     * Translates reconstructForSubagentResume() from utils/toolResultStorage.ts.
     */
    private Object reconstructReplacementState(
            ToolUseContext toolUseContext,
            List<Message> resumedMessages,
            Object contentReplacements) {
        // Delegate to ToolResultStorageService if available; return parent state as fallback
        if (toolUseContext != null) {
            return toolUseContext.getContentReplacementState();
        }
        return null;
    }

    /**
     * Verify the stored worktree path still exists; fall back to null if it was removed.
     */
    private String resolveWorktreePath(SessionStorageService.AgentMetadata meta) {
        if (meta == null || meta.worktreePath() == null) return null;
        Path p = Path.of(meta.worktreePath());
        try {
            if (Files.isDirectory(p)) return meta.worktreePath();
            log.debug("Resumed worktree {} no longer exists; falling back to parent cwd",
                meta.worktreePath());
            return null;
        } catch (Exception e) {
            log.debug("Error checking worktree path {}: {}", meta.worktreePath(), e.getMessage());
            return null;
        }
    }

    private void bumpMtime(Path path) {
        try {
            Instant now = Instant.now();
            Files.setLastModifiedTime(path, FileTime.from(now));
        } catch (IOException e) {
            log.debug("Could not bump mtime for {}: {}", path, e.getMessage());
        }
    }

    /**
     * Select the agent definition to resume with.
     * Mirrors the agent-selection logic in resumeAgentBackground.
     */
    private AgentDefinition selectAgentDefinition(
            SessionStorageService.AgentMetadata meta,
            List<AgentDefinition> activeAgentDefinitions,
            ToolUseContext toolUseContext) {

        if (meta != null && ForkSubagentService.FORK_SUBAGENT_TYPE.equals(meta.agentType())) {
            return AgentDefinition.FORK_AGENT;
        }

        if (meta != null && meta.agentType() != null && activeAgentDefinitions != null) {
            return activeAgentDefinitions.stream()
                .filter(a -> meta.agentType().equals(a.getAgentType()))
                .findFirst()
                .orElse(AgentDefinition.GENERAL_PURPOSE_AGENT);
        }

        return AgentDefinition.GENERAL_PURPOSE_AGENT;
    }

    private List<Tool<?, ?>> getTools(ToolUseContext toolUseContext) {
        if (toolUseContext != null
                && toolUseContext.getOptions() != null
                && toolUseContext.getOptions().getTools() != null) {
            return toolUseContext.getOptions().getTools();
        }
        return List.of();
    }

    private Object getRenderedSystemPrompt(ToolUseContext toolUseContext) {
        if (toolUseContext != null) {
            return toolUseContext.getRenderedSystemPrompt();
        }
        return null;
    }
}
