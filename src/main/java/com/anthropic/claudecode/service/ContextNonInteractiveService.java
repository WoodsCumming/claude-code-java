package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Message;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Non-interactive context usage service.
 * Translated from src/commands/context/context-noninteractive.ts
 *
 * Collects and formats context-window usage data for both the /context slash
 * command and the SDK get_context_usage control request.  Mirrors query.ts
 * pre-API transforms (compact boundary, projectView, microcompact) so that
 * the reported token count matches what the model actually sees.
 */
@Slf4j
@Service
public class ContextNonInteractiveService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextNonInteractiveService.class);


    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final ContextAnalysisService contextAnalysisService;
    private final MicroCompactService microCompactService;

    @Autowired
    public ContextNonInteractiveService(ContextAnalysisService contextAnalysisService,
                                         MicroCompactService microCompactService) {
        this.contextAnalysisService = contextAnalysisService;
        this.microCompactService = microCompactService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Collects raw context data for the active session.
     * Mirrors collectContextData() in context-noninteractive.ts.
     *
     * @param messages         Full current message list.
     * @param mainLoopModel    Name of the model in use.
     * @param customSystemPrompt Optional custom system prompt override.
     * @param appendSystemPrompt Optional text appended to the system prompt.
     * @return A {@link ContextData} snapshot.
     */
    public CompletableFuture<ContextData> collectContextData(List<Message> messages,
                                                              String mainLoopModel,
                                                              String customSystemPrompt,
                                                              String appendSystemPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Apply compact boundary (mirrors getMessagesAfterCompactBoundary)
                List<Message> apiView = contextAnalysisService
                    .getMessagesAfterCompactBoundary(messages);

                // Microcompact to match what the model sees
                MicroCompactService.MicrocompactResult microResult =
                    microCompactService.microcompactMessages(apiView);
                return new Object[]{ microResult.getMessages(), apiView };
            } catch (Exception e) {
                log.error("Failed to collect context data: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to collect context data: " + e.getMessage(), e);
            }
        }).thenCompose(arr -> {
            @SuppressWarnings("unchecked")
            List<Message> compactedMessages = (List<Message>) arr[0];
            @SuppressWarnings("unchecked")
            List<Message> apiView = (List<Message>) arr[1];
            return contextAnalysisService.analyzeContextUsage(
                compactedMessages,
                mainLoopModel,
                customSystemPrompt,
                appendSystemPrompt,
                apiView
            ).thenApply(ContextNonInteractiveService::convertContextData);
        });
    }

    /**
     * Entry-point for the /context slash command.
     * Mirrors call() in context-noninteractive.ts.
     *
     * @param messages         Full current message list.
     * @param mainLoopModel    Name of the model in use.
     * @param customSystemPrompt Optional custom system prompt override.
     * @param appendSystemPrompt Optional text appended to the system prompt.
     * @return A {@link TextResult} containing the Markdown table.
     */
    public CompletableFuture<TextResult> call(List<Message> messages,
                                               String mainLoopModel,
                                               String customSystemPrompt,
                                               String appendSystemPrompt) {
        return collectContextData(messages, mainLoopModel, customSystemPrompt, appendSystemPrompt)
            .thenApply(data -> new TextResult("text", formatContextAsMarkdownTable(data)));
    }

    // ---------------------------------------------------------------------------
    // Markdown formatting
    // ---------------------------------------------------------------------------

    /**
     * Renders context-usage data as a Markdown table.
     * Mirrors formatContextAsMarkdownTable() in context-noninteractive.ts.
     */
    public String formatContextAsMarkdownTable(ContextData data) {
        StringBuilder output = new StringBuilder();

        output.append("## Context Usage\n\n");
        output.append("**Model:** ").append(data.model()).append("  \n");
        output.append("**Tokens:** ")
            .append(formatTokens(data.totalTokens())).append(" / ")
            .append(formatTokens(data.rawMaxTokens()))
            .append(" (").append(data.percentage()).append("%)\n\n");

        // Main categories table
        List<ContextData.Category> visibleCategories = data.categories().stream()
            .filter(c -> c.tokens() > 0
                && !"Free space".equals(c.name())
                && !"Autocompact buffer".equals(c.name()))
            .toList();

        if (!visibleCategories.isEmpty()) {
            output.append("### Estimated usage by category\n\n");
            output.append("| Category | Tokens | Percentage |\n");
            output.append("|----------|--------|------------|\n");

            for (ContextData.Category cat : visibleCategories) {
                double pct = (data.rawMaxTokens() > 0)
                    ? (cat.tokens() * 100.0 / data.rawMaxTokens()) : 0;
                output.append("| ").append(cat.name())
                    .append(" | ").append(formatTokens(cat.tokens()))
                    .append(" | ").append(String.format("%.1f", pct)).append("% |\n");
            }

            data.categories().stream()
                .filter(c -> "Free space".equals(c.name()) && c.tokens() > 0)
                .findFirst().ifPresent(c -> {
                    double pct = (data.rawMaxTokens() > 0)
                        ? (c.tokens() * 100.0 / data.rawMaxTokens()) : 0;
                    output.append("| Free space | ").append(formatTokens(c.tokens()))
                        .append(" | ").append(String.format("%.1f", pct)).append("% |\n");
                });

            data.categories().stream()
                .filter(c -> "Autocompact buffer".equals(c.name()) && c.tokens() > 0)
                .findFirst().ifPresent(c -> {
                    double pct = (data.rawMaxTokens() > 0)
                        ? (c.tokens() * 100.0 / data.rawMaxTokens()) : 0;
                    output.append("| Autocompact buffer | ").append(formatTokens(c.tokens()))
                        .append(" | ").append(String.format("%.1f", pct)).append("% |\n");
                });

            output.append("\n");
        }

        // MCP tools
        if (data.mcpTools() != null && !data.mcpTools().isEmpty()) {
            output.append("### MCP Tools\n\n");
            output.append("| Tool | Server | Tokens |\n");
            output.append("|------|--------|--------|\n");
            for (ContextData.McpToolEntry tool : data.mcpTools()) {
                output.append("| ").append(tool.name())
                    .append(" | ").append(tool.serverName())
                    .append(" | ").append(formatTokens(tool.tokens())).append(" |\n");
            }
            output.append("\n");
        }

        // Custom agents
        if (data.agents() != null && !data.agents().isEmpty()) {
            output.append("### Custom Agents\n\n");
            output.append("| Agent Type | Source | Tokens |\n");
            output.append("|------------|--------|--------|\n");
            for (ContextData.AgentEntry agent : data.agents()) {
                output.append("| ").append(agent.agentType())
                    .append(" | ").append(resolveSourceDisplay(agent.source()))
                    .append(" | ").append(formatTokens(agent.tokens())).append(" |\n");
            }
            output.append("\n");
        }

        // Memory files
        if (data.memoryFiles() != null && !data.memoryFiles().isEmpty()) {
            output.append("### Memory Files\n\n");
            output.append("| Type | Path | Tokens |\n");
            output.append("|------|------|--------|\n");
            for (ContextData.MemoryFileEntry file : data.memoryFiles()) {
                output.append("| ").append(file.type())
                    .append(" | ").append(file.path())
                    .append(" | ").append(formatTokens(file.tokens())).append(" |\n");
            }
            output.append("\n");
        }

        // Skills
        if (data.skills() != null && data.skills().tokens() > 0
                && data.skills().skillFrontmatter() != null
                && !data.skills().skillFrontmatter().isEmpty()) {
            output.append("### Skills\n\n");
            output.append("| Skill | Source | Tokens |\n");
            output.append("|-------|--------|--------|\n");
            for (ContextData.SkillEntry skill : data.skills().skillFrontmatter()) {
                output.append("| ").append(skill.name())
                    .append(" | ").append(skill.source())
                    .append(" | ").append(formatTokens(skill.tokens())).append(" |\n");
            }
            output.append("\n");
        }

        return output.toString();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private String formatTokens(long tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    private String resolveSourceDisplay(String source) {
        return switch (source) {
            case "projectSettings" -> "Project";
            case "userSettings"    -> "User";
            case "localSettings"   -> "Local";
            case "flagSettings"    -> "Flag";
            case "policySettings"  -> "Policy";
            case "plugin"          -> "Plugin";
            case "built-in"        -> "Built-in";
            default                -> source;
        };
    }

    // ---------------------------------------------------------------------------
    // Conversion
    // ---------------------------------------------------------------------------

    /** Convert AnalyzeContextService.ContextData to the local ContextData record. */
    @SuppressWarnings("unchecked")
    private static ContextData convertContextData(AnalyzeContextService.ContextData src) {
        if (src == null) return new ContextData(List.of(), 0, 0, 0, null, List.of(), List.of(), List.of(), null, null, List.of(), List.of());

        // Convert categories
        List<ContextData.Category> cats = (src.getCategories() != null)
                ? src.getCategories().stream()
                        .map(c -> new ContextData.Category(c.getName(), c.getTokens()))
                        .collect(java.util.stream.Collectors.toList())
                : List.of();

        // Convert memory files
        List<ContextData.MemoryFileEntry> memFiles = (src.getMemoryFiles() != null)
                ? src.getMemoryFiles().stream()
                        .map(m -> new ContextData.MemoryFileEntry(m.getType(), m.getPath(), m.getTokens()))
                        .collect(java.util.stream.Collectors.toList())
                : List.of();

        // Convert MCP tools
        List<ContextData.McpToolEntry> mcpTools = (src.getMcpTools() != null)
                ? src.getMcpTools().stream()
                        .map(t -> new ContextData.McpToolEntry(t.getName(), t.getServerName(), t.getTokens()))
                        .collect(java.util.stream.Collectors.toList())
                : List.of();

        // Convert agents
        List<ContextData.AgentEntry> agents = (src.getAgents() != null)
                ? src.getAgents().stream()
                        .map(a -> new ContextData.AgentEntry(a.getAgentType(), a.getSource(), a.getTokens()))
                        .collect(java.util.stream.Collectors.toList())
                : List.of();

        return new ContextData(cats, src.getTotalTokens(), src.getRawMaxTokens(),
                src.getPercentage(), src.getModel(), memFiles, mcpTools, agents,
                null, null, List.of(), List.of());
    }

    // ---------------------------------------------------------------------------
    // Inner types
    // ---------------------------------------------------------------------------

    /**
     * Snapshot of context-window usage.
     * Mirrors ContextData in analyzeContext.ts.
     */
    public record ContextData(
        List<Category> categories,
        long totalTokens,
        long rawMaxTokens,
        double percentage,
        String model,
        List<MemoryFileEntry> memoryFiles,
        List<McpToolEntry> mcpTools,
        List<AgentEntry> agents,
        SkillsSummary skills,
        MessageBreakdown messageBreakdown,
        List<ToolEntry> systemTools,
        List<SectionEntry> systemPromptSections
    ) {
        public record Category(String name, long tokens) {}
        public record MemoryFileEntry(String type, String path, long tokens) {}
        public record McpToolEntry(String name, String serverName, long tokens) {}
        public record AgentEntry(String agentType, String source, long tokens) {}
        public record SkillsSummary(long tokens, List<SkillEntry> skillFrontmatter) {}
        public record SkillEntry(String name, String source, long tokens) {}
        public record MessageBreakdown(
            long toolCallTokens,
            long toolResultTokens,
            long attachmentTokens,
            long assistantMessageTokens,
            long userMessageTokens,
            List<ToolCallEntry> toolCallsByType,
            List<AttachmentEntry> attachmentsByType
        ) {}
        public record ToolCallEntry(String name, long callTokens, long resultTokens) {}
        public record AttachmentEntry(String name, long tokens) {}
        public record ToolEntry(String name, long tokens) {}
        public record SectionEntry(String name, long tokens) {}
    }

    /**
     * Simple text result returned by the /context slash command.
     */
    public record TextResult(String type, String value) {}
}
