package com.anthropic.claudecode.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs context-size health checks for the {@code doctor} command.
 *
 * Checks:
 *   1. Oversized CLAUDE.md memory files
 *   2. Overly large agent-description token budgets
 *   3. Large MCP-tool context
 *   4. Unreachable permission rules (shadowed by broader rules)
 *
 * Translated from src/utils/doctorContextWarnings.ts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorContextWarningsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DoctorContextWarningsService.class);


    // Token budget threshold above which MCP tools are considered "large"
    private static final int MCP_TOOLS_THRESHOLD = 25_000;

    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /**
     * Category tag that identifies which subsystem the warning belongs to.
     * Translated from the TypeScript union literal type.
     */
    public enum WarningType {
        CLAUDEMD_FILES,
        AGENT_DESCRIPTIONS,
        MCP_TOOLS,
        UNREACHABLE_RULES
    }

    public enum Severity { WARNING, ERROR }

    /**
     * A single context-health warning with supporting detail lines.
     * Translated from the TypeScript type {@code ContextWarning}.
     */
    public record ContextWarning(
            WarningType type,
            Severity severity,
            String message,
            List<String> details,
            int currentValue,
            int threshold
    ) {}

    /**
     * Aggregated result of all context checks.
     * Translated from the TypeScript type {@code ContextWarnings}.
     */
    public record ContextWarnings(
            ContextWarning claudeMdWarning,
            ContextWarning agentWarning,
            ContextWarning mcpWarning,
            ContextWarning unreachableRulesWarning
    ) {}

    // -----------------------------------------------------------------------
    // Dependencies injected by Spring
    // -----------------------------------------------------------------------

    private final ClaudeMdService claudeMdService;
    private final TokenEstimationService tokenEstimationService;
    private final PermissionContextService permissionContextService;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Run all four context checks in parallel and return the combined result.
     * Translated from {@code checkContextWarnings()} in doctorContextWarnings.ts
     */
    public CompletableFuture<ContextWarnings> checkContextWarnings() {
        CompletableFuture<ContextWarning> claudeMdFuture =
                CompletableFuture.supplyAsync(this::checkClaudeMdFiles);
        CompletableFuture<ContextWarning> agentFuture =
                CompletableFuture.supplyAsync(this::checkAgentDescriptions);
        CompletableFuture<ContextWarning> mcpFuture =
                CompletableFuture.supplyAsync(this::checkMcpTools);
        CompletableFuture<ContextWarning> unreachableFuture =
                CompletableFuture.supplyAsync(this::checkUnreachableRules);

        return CompletableFuture.allOf(claudeMdFuture, agentFuture, mcpFuture, unreachableFuture)
                .thenApply(v -> new ContextWarnings(
                        claudeMdFuture.join(),
                        agentFuture.join(),
                        mcpFuture.join(),
                        unreachableFuture.join()));
    }

    // -----------------------------------------------------------------------
    // Individual checks
    // -----------------------------------------------------------------------

    /**
     * Check for CLAUDE.md memory files that exceed the character threshold.
     * Translated from {@code checkClaudeMdFiles()} in doctorContextWarnings.ts
     */
    private ContextWarning checkClaudeMdFiles() {
        try {
            int maxChars = claudeMdService.getMaxMemoryCharacterCount();
            List<ClaudeMdService.MemoryFile> largeFiles = claudeMdService.getLargeMemoryFiles();

            if (largeFiles.isEmpty()) return null;

            List<String> details = largeFiles.stream()
                    .sorted(Comparator.comparingInt(ClaudeMdService.MemoryFile::contentLength).reversed())
                    .map(f -> f.path() + ": " + String.format("%,d", f.contentLength()) + " chars")
                    .toList();

            String message = largeFiles.size() == 1
                    ? String.format("Large CLAUDE.md file detected (%,d chars > %,d)",
                            largeFiles.get(0).contentLength(), maxChars)
                    : String.format("%d large CLAUDE.md files detected (each > %,d chars)",
                            largeFiles.size(), maxChars);

            return new ContextWarning(
                    WarningType.CLAUDEMD_FILES,
                    Severity.WARNING,
                    message,
                    details,
                    largeFiles.size(),
                    maxChars);
        } catch (Exception e) {
            log.warn("Failed to check CLAUDE.md files", e);
            return null;
        }
    }

    /**
     * Check whether agent-description tokens exceed the threshold.
     * Translated from {@code checkAgentDescriptions()} in doctorContextWarnings.ts
     */
    private ContextWarning checkAgentDescriptions() {
        try {
            int threshold = tokenEstimationService.getAgentDescriptionsThreshold();
            List<TokenEstimationService.AgentTokenInfo> agentTokens =
                    tokenEstimationService.getAgentDescriptionTokens();

            int total = agentTokens.stream().mapToInt(TokenEstimationService.AgentTokenInfo::tokens).sum();
            if (total <= threshold) return null;

            List<TokenEstimationService.AgentTokenInfo> sorted = agentTokens.stream()
                    .sorted(Comparator.comparingInt(TokenEstimationService.AgentTokenInfo::tokens).reversed())
                    .toList();

            List<String> details = new ArrayList<>();
            sorted.stream().limit(5)
                    .forEach(a -> details.add(a.name() + ": ~" + String.format("%,d", a.tokens()) + " tokens"));
            if (sorted.size() > 5) {
                details.add("(" + (sorted.size() - 5) + " more custom agents)");
            }

            return new ContextWarning(
                    WarningType.AGENT_DESCRIPTIONS,
                    Severity.WARNING,
                    String.format("Large agent descriptions (~%,d tokens > %,d)", total, threshold),
                    details,
                    total,
                    threshold);
        } catch (Exception e) {
            log.warn("Failed to check agent descriptions", e);
            return null;
        }
    }

    /**
     * Check whether MCP tool context exceeds the token threshold.
     * Translated from {@code checkMcpTools()} in doctorContextWarnings.ts
     */
    private ContextWarning checkMcpTools() {
        try {
            List<McpService.McpToolInfo> mcpTools = permissionContextService.getMcpTools();
            if (mcpTools.isEmpty()) return null;

            // Group by server name (format: mcp__servername__toolname)
            Map<String, ServerInfo> byServer = new HashMap<>();
            int totalTokens = 0;

            for (McpService.McpToolInfo tool : mcpTools) {
                String[] parts = tool.name().split("__");
                String serverName = parts.length > 1 ? parts[1] : "unknown";
                int tokens = tokenEstimationService.estimateTokens(
                        tool.name() + " " + tool.description());
                totalTokens += tokens;

                ServerInfo info = byServer.computeIfAbsent(serverName,
                        k -> new ServerInfo(k, 0, 0));
                byServer.put(serverName,
                        new ServerInfo(serverName, info.count() + 1, info.tokens() + tokens));
            }

            if (totalTokens <= MCP_TOOLS_THRESHOLD) return null;

            List<ServerInfo> sorted = byServer.values().stream()
                    .sorted(Comparator.comparingInt(ServerInfo::tokens).reversed())
                    .toList();

            List<String> details = new ArrayList<>();
            sorted.stream().limit(5)
                    .forEach(s -> details.add(s.name() + ": " + s.count()
                            + " tools (~" + String.format("%,d", s.tokens()) + " tokens)"));
            if (sorted.size() > 5) {
                details.add("(" + (sorted.size() - 5) + " more servers)");
            }

            return new ContextWarning(
                    WarningType.MCP_TOOLS,
                    Severity.WARNING,
                    String.format("Large MCP tools context (~%,d tokens > %,d)",
                            totalTokens, MCP_TOOLS_THRESHOLD),
                    details,
                    totalTokens,
                    MCP_TOOLS_THRESHOLD);
        } catch (Exception e) {
            log.warn("Failed to check MCP tools", e);
            return null;
        }
    }

    /**
     * Check for permission rules that can never be reached (shadowed by
     * broader deny/ask rules above them).
     * Translated from {@code checkUnreachableRules()} in doctorContextWarnings.ts
     */
    private ContextWarning checkUnreachableRules() {
        try {
            List<PermissionContextService.UnreachableRule> unreachable =
                    permissionContextService.detectUnreachableRules();

            if (unreachable.isEmpty()) return null;

            List<String> details = new ArrayList<>();
            for (PermissionContextService.UnreachableRule r : unreachable) {
                details.add(r.ruleValue() + ": " + r.reason());
                details.add("  Fix: " + r.fix());
            }

            int count = unreachable.size();
            String noun = count == 1 ? "unreachable permission rule" : "unreachable permission rules";

            return new ContextWarning(
                    WarningType.UNREACHABLE_RULES,
                    Severity.WARNING,
                    count + " " + noun + " detected",
                    details,
                    count,
                    0);
        } catch (Exception e) {
            log.warn("Failed to check unreachable rules", e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Internal helper record
    // -----------------------------------------------------------------------

    private record ServerInfo(String name, int count, int tokens) {}
}
