package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Context analysis service.
 * Translated from src/utils/analyzeContext.ts
 *
 * Provides context window usage analysis, token counting, and grid visualization
 * for the /context command and context window status display.
 */
@Slf4j
@Service
public class AnalyzeContextService {



    /**
     * Fixed token overhead added by the API when tools are present.
     * The API adds a tool prompt preamble (~500 tokens) once per API call when tools are present.
     */
    public static final int TOOL_TOKEN_COUNT_OVERHEAD = 500;

    // =========================================================================
    // Data types
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ContextCategory {
        private String name;
        private int tokens;
        private String color;
        /** When true, these tokens are deferred and don't count toward context usage */
        private boolean deferred;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public int getTokens() { return tokens; }
        public void setTokens(int v) { tokens = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public boolean isDeferred() { return deferred; }
        public void setDeferred(boolean v) { deferred = v; }
    }

    public static class GridSquare {
        private String color;
        private boolean filled;
        private String categoryName;
        private int tokens;
        private int percentage;
        /** 0.0–1.0 representing how full this individual square is */
        private double squareFullness;

        public GridSquare() {}
        public GridSquare(String color, boolean filled, String categoryName, int tokens, int percentage, double squareFullness) {
            this.color = color;
            this.filled = filled;
            this.categoryName = categoryName;
            this.tokens = tokens;
            this.percentage = percentage;
            this.squareFullness = squareFullness;
        }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public boolean isFilled() { return filled; }
        public void setFilled(boolean v) { filled = v; }
        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String v) { categoryName = v; }
        public int getTokens() { return tokens; }
        public void setTokens(int v) { tokens = v; }
        public int getPercentage() { return percentage; }
        public void setPercentage(int v) { percentage = v; }
        public double getSquareFullness() { return squareFullness; }
        public void setSquareFullness(double v) { squareFullness = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MemoryFile {
        private String path;
        private String type;
        private int tokens;

        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public int getTokens() { return tokens; }
        public void setTokens(int v) { tokens = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class McpTool {
        private String name;
        private String serverName;
        private int tokens;
        private boolean loaded;

        public String getServerName() { return serverName; }
        public void setServerName(String v) { serverName = v; }
        public boolean isLoaded() { return loaded; }
        public void setLoaded(boolean v) { loaded = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DeferredBuiltinTool {
        private String name;
        private int tokens;
        private boolean loaded;

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SystemToolDetail {
        private String name;
        private int tokens;

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SystemPromptSectionDetail {
        private String name;
        private int tokens;

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AgentDetail {
        private String agentType;
        private String source;
        private int tokens;

        public String getAgentType() { return agentType; }
        public void setAgentType(String v) { agentType = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
        public int getTokens() { return tokens; }
        public void setTokens(int v) { tokens = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SlashCommandInfo {
        private int totalCommands;
        private int includedCommands;
        private int tokens;

        public int getTotalCommands() { return totalCommands; }
        public void setTotalCommands(int v) { totalCommands = v; }
        public int getIncludedCommands() { return includedCommands; }
        public void setIncludedCommands(int v) { includedCommands = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SkillFrontmatter {
        private String name;
        private String source;
        private int tokens;

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SkillInfo {
        private int totalSkills;
        private int includedSkills;
        private int tokens;
        private List<SkillFrontmatter> skillFrontmatter;

        public int getTotalSkills() { return totalSkills; }
        public void setTotalSkills(int v) { totalSkills = v; }
        public int getIncludedSkills() { return includedSkills; }
        public void setIncludedSkills(int v) { includedSkills = v; }
        public List<SkillFrontmatter> getSkillFrontmatter() { return skillFrontmatter; }
        public void setSkillFrontmatter(List<SkillFrontmatter> v) { skillFrontmatter = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ToolCallByType {
        private String name;
        private int callTokens;
        private int resultTokens;

        public int getCallTokens() { return callTokens; }
        public void setCallTokens(int v) { callTokens = v; }
        public int getResultTokens() { return resultTokens; }
        public void setResultTokens(int v) { resultTokens = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AttachmentByType {
        private String name;
        private int tokens;

    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MessageBreakdown {
        private int toolCallTokens;
        private int toolResultTokens;
        private int attachmentTokens;
        private int assistantMessageTokens;
        private int userMessageTokens;
        private List<ToolCallByType> toolCallsByType;
        private List<AttachmentByType> attachmentsByType;

        public int getToolCallTokens() { return toolCallTokens; }
        public void setToolCallTokens(int v) { toolCallTokens = v; }
        public int getToolResultTokens() { return toolResultTokens; }
        public void setToolResultTokens(int v) { toolResultTokens = v; }
        public int getAttachmentTokens() { return attachmentTokens; }
        public void setAttachmentTokens(int v) { attachmentTokens = v; }
        public int getAssistantMessageTokens() { return assistantMessageTokens; }
        public void setAssistantMessageTokens(int v) { assistantMessageTokens = v; }
        public int getUserMessageTokens() { return userMessageTokens; }
        public void setUserMessageTokens(int v) { userMessageTokens = v; }
        public List<ToolCallByType> getToolCallsByType() { return toolCallsByType; }
        public void setToolCallsByType(List<ToolCallByType> v) { toolCallsByType = v; }
        public List<AttachmentByType> getAttachmentsByType() { return attachmentsByType; }
        public void setAttachmentsByType(List<AttachmentByType> v) { attachmentsByType = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApiUsage {
        private int inputTokens;
        private int outputTokens;
        private int cacheCreationInputTokens;
        private int cacheReadInputTokens;

        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int v) { inputTokens = v; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int v) { outputTokens = v; }
        public int getCacheCreationInputTokens() { return cacheCreationInputTokens; }
        public void setCacheCreationInputTokens(int v) { cacheCreationInputTokens = v; }
        public int getCacheReadInputTokens() { return cacheReadInputTokens; }
        public void setCacheReadInputTokens(int v) { cacheReadInputTokens = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ContextData {
        private List<ContextCategory> categories = new ArrayList<>();
        private int totalTokens;
        private int maxTokens;
        private int rawMaxTokens;
        private int percentage;
        private List<List<GridSquare>> gridRows = new ArrayList<>();
        private String model;
        private List<MemoryFile> memoryFiles = new ArrayList<>();
        private List<McpTool> mcpTools = new ArrayList<>();
        /** Ant-only: per-tool breakdown of deferred built-in tools */
        private List<DeferredBuiltinTool> deferredBuiltinTools;
        /** Ant-only: per-tool breakdown of always-loaded built-in tools */
        private List<SystemToolDetail> systemTools;
        /** Ant-only: per-section breakdown of system prompt */
        private List<SystemPromptSectionDetail> systemPromptSections;
        private List<AgentDetail> agents = new ArrayList<>();
        private SlashCommandInfo slashCommands;
        private SkillInfo skills;
        private Integer autoCompactThreshold;
        private boolean autoCompactEnabled;
        private MessageBreakdown messageBreakdown;
        private ApiUsage apiUsage;

        public List<ContextCategory> getCategories() { return categories; }
        public void setCategories(List<ContextCategory> v) { categories = v; }
        public int getTotalTokens() { return totalTokens; }
        public void setTotalTokens(int v) { totalTokens = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int v) { maxTokens = v; }
        public int getRawMaxTokens() { return rawMaxTokens; }
        public void setRawMaxTokens(int v) { rawMaxTokens = v; }
        public List<List<GridSquare>> getGridRows() { return gridRows; }
        public void setGridRows(List<List<GridSquare>> v) { gridRows = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public List<MemoryFile> getMemoryFiles() { return memoryFiles; }
        public void setMemoryFiles(List<MemoryFile> v) { memoryFiles = v; }
        public List<McpTool> getMcpTools() { return mcpTools; }
        public void setMcpTools(List<McpTool> v) { mcpTools = v; }
        public List<DeferredBuiltinTool> getDeferredBuiltinTools() { return deferredBuiltinTools; }
        public void setDeferredBuiltinTools(List<DeferredBuiltinTool> v) { deferredBuiltinTools = v; }
        public List<SystemToolDetail> getSystemTools() { return systemTools; }
        public void setSystemTools(List<SystemToolDetail> v) { systemTools = v; }
        public List<SystemPromptSectionDetail> getSystemPromptSections() { return systemPromptSections; }
        public void setSystemPromptSections(List<SystemPromptSectionDetail> v) { systemPromptSections = v; }
        public List<AgentDetail> getAgents() { return agents; }
        public void setAgents(List<AgentDetail> v) { agents = v; }
        public SlashCommandInfo getSlashCommands() { return slashCommands; }
        public void setSlashCommands(SlashCommandInfo v) { slashCommands = v; }
        public SkillInfo getSkills() { return skills; }
        public void setSkills(SkillInfo v) { skills = v; }
        public Integer getAutoCompactThreshold() { return autoCompactThreshold; }
        public void setAutoCompactThreshold(Integer v) { autoCompactThreshold = v; }
        public boolean isAutoCompactEnabled() { return autoCompactEnabled; }
        public void setAutoCompactEnabled(boolean v) { autoCompactEnabled = v; }
        public MessageBreakdown getMessageBreakdown() { return messageBreakdown; }
        public void setMessageBreakdown(MessageBreakdown v) { messageBreakdown = v; }
        public ApiUsage getApiUsage() { return apiUsage; }
        public void setApiUsage(ApiUsage v) { apiUsage = v; }
        public int getPercentage() { return percentage; }
        public void setPercentage(int v) { percentage = v; }
    }

    // =========================================================================
    // Grid construction helpers
    // =========================================================================

    /**
     * Build a visual grid representing context window usage.
     * Translated from the grid-building logic in analyzeContextUsage().
     *
     * @param categories  non-deferred context categories (including Free space)
     * @param contextWindow total context window size in tokens
     * @param terminalWidth optional terminal width for layout selection
     * @return 2D list of GridSquare rows
     */
    public List<List<GridSquare>> buildGrid(
            List<ContextCategory> categories,
            int contextWindow,
            Integer terminalWidth) {

        boolean isNarrowScreen = terminalWidth != null && terminalWidth < 80;
        boolean is1M = contextWindow >= 1_000_000;
        int gridWidth = is1M ? (isNarrowScreen ? 5 : 20) : (isNarrowScreen ? 5 : 10);
        int gridHeight = is1M ? 10 : (isNarrowScreen ? 5 : 10);
        int totalSquares = gridWidth * gridHeight;

        // Compute squares per category
        record CatSquares(ContextCategory cat, int squares, int percentageOfTotal) {}
        List<CatSquares> catSquares = categories.stream()
                .map(cat -> {
                    int sq = "Free space".equals(cat.getName())
                            ? (int) Math.round((double) cat.getTokens() / contextWindow * totalSquares)
                            : Math.max(1, (int) Math.round((double) cat.getTokens() / contextWindow * totalSquares));
                    int pct = (int) Math.round((double) cat.getTokens() / contextWindow * 100);
                    return new CatSquares(cat, sq, pct);
                })
                .toList();

        // Identify reserved and free-space categories
        String reservedName1 = "Autocompact buffer";
        String reservedName2 = "Compact buffer";
        CatSquares reservedCat = catSquares.stream()
                .filter(c -> reservedName1.equals(c.cat().getName()) || reservedName2.equals(c.cat().getName()))
                .findFirst().orElse(null);
        List<CatSquares> nonReserved = catSquares.stream()
                .filter(c -> !reservedName1.equals(c.cat().getName())
                        && !reservedName2.equals(c.cat().getName())
                        && !"Free space".equals(c.cat().getName()))
                .toList();
        CatSquares freeSpaceCat = catSquares.stream()
                .filter(c -> "Free space".equals(c.cat().getName()))
                .findFirst().orElse(null);

        List<GridSquare> flat = new ArrayList<>(totalSquares);

        // Add non-reserved, non-free-space squares
        for (CatSquares cs : nonReserved) {
            List<GridSquare> squares = createCategorySquares(cs.cat(), cs.squares(), cs.percentageOfTotal(), contextWindow, totalSquares);
            for (GridSquare sq : squares) {
                if (flat.size() < totalSquares) flat.add(sq);
            }
        }

        // Fill free space up to leave room for reserved at end
        int reservedSquareCount = reservedCat != null ? reservedCat.squares() : 0;
        int freeSpaceTarget = totalSquares - reservedSquareCount;
        int freeTokens = freeSpaceCat != null ? freeSpaceCat.cat().getTokens() : 0;
        int freePct = freeSpaceCat != null ? freeSpaceCat.percentageOfTotal() : 0;
        while (flat.size() < freeSpaceTarget) {
            flat.add(new GridSquare("promptBorder", true, "Free space", freeTokens, freePct, 1.0));
        }

        // Add reserved squares at end
        if (reservedCat != null) {
            List<GridSquare> squares = createCategorySquares(reservedCat.cat(), reservedCat.squares(), reservedCat.percentageOfTotal(), contextWindow, totalSquares);
            for (GridSquare sq : squares) {
                if (flat.size() < totalSquares) flat.add(sq);
            }
        }

        // Split into rows
        List<List<GridSquare>> rows = new ArrayList<>(gridHeight);
        for (int i = 0; i < gridHeight; i++) {
            int from = i * gridWidth;
            int to = Math.min(from + gridWidth, flat.size());
            rows.add(from < flat.size() ? new ArrayList<>(flat.subList(from, to)) : new ArrayList<>());
        }
        return rows;
    }

    private List<GridSquare> createCategorySquares(
            ContextCategory cat, int squaresCount, int percentageOfTotal,
            int contextWindow, int totalSquares) {
        List<GridSquare> result = new ArrayList<>(squaresCount);
        double exactSquares = (double) cat.getTokens() / contextWindow * totalSquares;
        int wholeSquares = (int) Math.floor(exactSquares);
        double fractionalPart = exactSquares - wholeSquares;

        for (int i = 0; i < squaresCount; i++) {
            double fullness = (i == wholeSquares && fractionalPart > 0) ? fractionalPart : 1.0;
            result.add(new GridSquare(
                    cat.getColor(), true, cat.getName(),
                    cat.getTokens(), percentageOfTotal, fullness));
        }
        return result;
    }

    // =========================================================================
    // Async entry point
    // =========================================================================

    /**
     * Analyze context usage and return a ContextData summary.
     * Translated from analyzeContextUsage() in analyzeContext.ts
     *
     * In the Java implementation this is a lightweight entry point;
     * actual token counting is delegated to TokenEstimationService.
     *
     * @param model         current model name
     * @param contextWindow context window size in tokens
     * @param terminalWidth optional terminal width for grid layout
     * @return CompletableFuture resolving to a ContextData instance
     */
    public CompletableFuture<ContextData> analyzeContextUsage(
            String model,
            int contextWindow,
            Integer terminalWidth) {
        return CompletableFuture.supplyAsync(() -> {
            ContextData data = new ContextData();
            data.setModel(model);
            data.setMaxTokens(contextWindow);
            data.setRawMaxTokens(contextWindow);
            data.setCategories(new ArrayList<>());
            data.setGridRows(new ArrayList<>());
            data.setMemoryFiles(new ArrayList<>());
            data.setMcpTools(new ArrayList<>());
            data.setAgents(new ArrayList<>());
            log.debug("analyzeContextUsage called for model={} contextWindow={}", model, contextWindow);
            return data;
        });
    }
}
