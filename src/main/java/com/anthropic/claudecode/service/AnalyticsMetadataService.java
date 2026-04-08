package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared event metadata enrichment for analytics systems.
 * Translated from src/services/analytics/metadata.ts
 *
 * This module provides a single source of truth for collecting and formatting
 * event metadata across all analytics systems (Datadog, 1P).
 */
@Slf4j
@Service
public class AnalyticsMetadataService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalyticsMetadataService.class);


    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int TOOL_INPUT_STRING_TRUNCATE_AT = 512;
    private static final int TOOL_INPUT_STRING_TRUNCATE_TO = 128;
    private static final int TOOL_INPUT_MAX_JSON_CHARS = 4 * 1024;
    private static final int TOOL_INPUT_MAX_COLLECTION_ITEMS = 20;
    private static final int TOOL_INPUT_MAX_DEPTH = 2;
    private static final int MAX_FILE_EXTENSION_LENGTH = 10;

    /** Allow-list of bash commands whose arguments we extract file extensions from. */
    private static final Set<String> FILE_COMMANDS = Set.of(
        "rm", "mv", "cp", "touch", "mkdir", "chmod", "chown",
        "cat", "head", "tail", "sort", "stat", "diff", "wc", "grep", "rg", "sed"
    );

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final ModelService modelService;

    @Autowired
    public AnalyticsMetadataService(ModelService modelService) {
        this.modelService = modelService;
    }

    // -------------------------------------------------------------------------
    // Data records (mirrors TypeScript types)
    // -------------------------------------------------------------------------

    /**
     * Environment context metadata.
     * Translated from EnvContext type in metadata.ts
     */
    public record EnvContext(
        String platform,
        String platformRaw,
        String arch,
        String nodeVersion,
        String terminal,
        String packageManagers,
        String runtimes,
        boolean isRunningWithBun,
        boolean isCi,
        boolean isClaubbit,
        boolean isClaudeCodeRemote,
        boolean isLocalAgentMode,
        boolean isConductor,
        String remoteEnvironmentType,
        String coworkerType,
        String claudeCodeContainerId,
        String claudeCodeRemoteSessionId,
        String tags,
        boolean isGithubAction,
        boolean isClaudeCodeAction,
        boolean isClaudeAiAuth,
        String version,
        String versionBase,
        String buildTime,
        String deploymentEnvironment,
        String githubEventName,
        String githubActionsRunnerEnvironment,
        String githubActionsRunnerOs,
        String githubActionRef,
        String wslVersion,
        String linuxDistroId,
        String linuxDistroVersion,
        String linuxKernel,
        String vcs
    ) {}

    /**
     * Process metrics included with all analytics events.
     * Translated from ProcessMetrics type in metadata.ts
     */
    public record ProcessMetrics(
        double uptime,
        long rss,
        long heapTotal,
        long heapUsed,
        long external,
        long arrayBuffers,
        Long constrainedMemory,
        long cpuUserUs,
        long cpuSystemUs,
        Double cpuPercent
    ) {}

    /**
     * Core event metadata shared across all analytics systems.
     * Translated from EventMetadata type in metadata.ts
     */
    public record EventMetadata(
        String model,
        String sessionId,
        String userType,
        String betas,
        EnvContext envContext,
        String entrypoint,
        String agentSdkVersion,
        String isInteractive,
        String clientType,
        ProcessMetrics processMetrics,
        String sweBenchRunId,
        String sweBenchInstanceId,
        String sweBenchTaskId,
        String agentId,
        String parentSessionId,
        String agentType,
        String teamName,
        String subscriptionType,
        String rh,
        Boolean kairosActive,
        String skillMode,
        String observerMode
    ) {}

    /**
     * Core event metadata for 1P event logging (snake_case format).
     * Translated from FirstPartyEventLoggingCoreMetadata in metadata.ts
     */
    public record FirstPartyEventLoggingCoreMetadata(
        String session_id,
        String model,
        String user_type,
        String betas,
        String entrypoint,
        String agent_sdk_version,
        boolean is_interactive,
        String client_type,
        String swe_bench_run_id,
        String swe_bench_instance_id,
        String swe_bench_task_id,
        String agent_id,
        String parent_session_id,
        String agent_type,
        String team_name
    ) {}

    /**
     * Options for enriching event metadata.
     * Translated from EnrichMetadataOptions in metadata.ts
     */
    public record EnrichMetadataOptions(
        Object model,
        Object betas,
        Map<String, Object> additionalMetadata
    ) {
        public static EnrichMetadataOptions empty() {
            return new EnrichMetadataOptions(null, null, null);
        }
    }

    // -------------------------------------------------------------------------
    // MCP tool helpers
    // -------------------------------------------------------------------------

    /**
     * Sanitizes tool names for analytics logging to avoid PII exposure.
     *
     * MCP tool names follow the format {@code mcp__<server>__<tool>} and can
     * reveal user-specific server configurations (PII-medium). This function
     * redacts MCP tool names while preserving built-in tool names.
     *
     * Translated from sanitizeToolNameForAnalytics() in metadata.ts
     */
    public static String sanitizeToolNameForAnalytics(String toolName) {
        if (toolName == null) return "";
        if (toolName.startsWith("mcp__")) {
            return "mcp_tool";
        }
        return toolName;
    }

    /**
     * Check if detailed tool name logging is enabled (OTEL_LOG_TOOL_DETAILS=1).
     * Translated from isToolDetailsLoggingEnabled() in metadata.ts
     */
    public static boolean isToolDetailsLoggingEnabled() {
        return EnvUtils.isEnvTruthy(System.getenv("OTEL_LOG_TOOL_DETAILS"));
    }

    /**
     * Check if detailed MCP tool name logging is enabled for analytics events.
     * Translated from isAnalyticsToolDetailsLoggingEnabled() in metadata.ts
     */
    public static boolean isAnalyticsToolDetailsLoggingEnabled(
            String mcpServerType,
            String mcpServerBaseUrl) {
        if ("local-agent".equals(System.getenv("CLAUDE_CODE_ENTRYPOINT"))) {
            return true;
        }
        if ("claudeai-proxy".equals(mcpServerType)) {
            return true;
        }
        // Note: isOfficialMcpUrl check omitted — requires MCP registry integration
        return false;
    }

    /**
     * Result of extracting MCP server/tool details.
     * Translated from the return type of extractMcpToolDetails() in metadata.ts
     */
    public record McpToolDetails(String serverName, String mcpToolName) {}

    /**
     * Extract MCP server and tool names from a full MCP tool name.
     * Format: {@code mcp__<server>__<tool>}
     *
     * Translated from extractMcpToolDetails() in metadata.ts
     */
    public static Optional<McpToolDetails> extractMcpToolDetails(String toolName) {
        if (toolName == null || !toolName.startsWith("mcp__")) {
            return Optional.empty();
        }
        String[] parts = toolName.split("__", -1);
        if (parts.length < 3) {
            return Optional.empty();
        }
        String serverName = parts[1];
        // Tool name may contain __ so rejoin remaining parts
        String mcpToolName = String.join("__", Arrays.copyOfRange(parts, 2, parts.length));
        if (serverName.isEmpty() || mcpToolName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new McpToolDetails(serverName, mcpToolName));
    }

    /**
     * Spreadable helper for logEvent payloads — returns map with
     * {@code mcpServerName} and {@code mcpToolName} if the logging gate passes,
     * empty map otherwise.
     *
     * Translated from mcpToolDetailsForAnalytics() in metadata.ts
     */
    public static Map<String, String> mcpToolDetailsForAnalytics(
            String toolName,
            String mcpServerType,
            String mcpServerBaseUrl) {
        Optional<McpToolDetails> details = extractMcpToolDetails(toolName);
        if (details.isEmpty()) {
            return Map.of();
        }
        if (!isAnalyticsToolDetailsLoggingEnabled(mcpServerType, mcpServerBaseUrl)) {
            return Map.of();
        }
        return Map.of(
            "mcpServerName", details.get().serverName(),
            "mcpToolName", details.get().mcpToolName()
        );
    }

    /**
     * Extract skill name from Skill tool input.
     * Translated from extractSkillName() in metadata.ts
     */
    public static Optional<String> extractSkillName(String toolName, Object input) {
        if (!"Skill".equals(toolName)) {
            return Optional.empty();
        }
        if (input instanceof Map<?, ?> map) {
            Object skill = map.get("skill");
            if (skill instanceof String s) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // File extension helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts and sanitizes a file extension for analytics logging.
     *
     * Returns {@code "other"} for extensions exceeding MAX_FILE_EXTENSION_LENGTH
     * to avoid logging potentially sensitive data, or empty string if no extension.
     *
     * Translated from getFileExtensionForAnalytics() in metadata.ts
     */
    public static String getFileExtensionForAnalytics(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) return "";
        // Check that dot is in the filename part (after last slash)
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (dot < lastSlash) return "";
        String ext = filePath.substring(dot + 1).toLowerCase();
        if (ext.isEmpty() || ext.equals(".")) return "";
        if (ext.length() > MAX_FILE_EXTENSION_LENGTH) return "other";
        return ext;
    }

    /**
     * Extracts file extensions from a bash command for analytics.
     * Best-effort: splits on compound operators and whitespace.
     *
     * Translated from getFileExtensionsFromBashCommand() in metadata.ts
     */
    public static String getFileExtensionsFromBashCommand(
            String command,
            String simulatedSedEditFilePath) {
        if (command == null) return null;
        if (!command.contains(".") && simulatedSedEditFilePath == null) return null;

        StringBuilder result = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();

        if (simulatedSedEditFilePath != null) {
            String ext = getFileExtensionForAnalytics(simulatedSedEditFilePath);
            if (!ext.isEmpty()) {
                seen.add(ext);
                result.append(ext);
            }
        }

        // Split on compound operators: &&, ||, ;, |
        String[] subCommands = command.split("\\s*(?:&&|\\|\\||[;|])\\s*");
        for (String subCmd : subCommands) {
            if (subCmd.isEmpty()) continue;
            String[] tokens = subCmd.trim().split("\\s+");
            if (tokens.length < 2) continue;

            // Extract base command name (strip leading path)
            String firstToken = tokens[0];
            int slashIdx = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
            String baseCmd = slashIdx >= 0 ? firstToken.substring(slashIdx + 1) : firstToken;
            if (!FILE_COMMANDS.contains(baseCmd)) continue;

            for (int i = 1; i < tokens.length; i++) {
                String arg = tokens[i];
                if (arg.startsWith("-")) continue; // skip flags
                String ext = getFileExtensionForAnalytics(arg);
                if (!ext.isEmpty() && seen.add(ext)) {
                    if (result.length() > 0) result.append(',');
                    result.append(ext);
                }
            }
        }

        return result.length() > 0 ? result.toString() : null;
    }

    // -------------------------------------------------------------------------
    // Metadata enrichment
    // -------------------------------------------------------------------------

    /**
     * Get core event metadata shared across all analytics systems.
     *
     * Collects environment, runtime, and context information that should be
     * included with all analytics events.
     *
     * Translated from getEventMetadata() in metadata.ts
     */
    public CompletableFuture<EventMetadata> getEventMetadata(EnrichMetadataOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            String model = (options != null && options.model() != null)
                ? String.valueOf(options.model())
                : modelService.getMainLoopModel();

            String sessionId = Optional.ofNullable(System.getenv("CLAUDE_CODE_SESSION_ID"))
                .orElse(UUID.randomUUID().toString());
            String userType = Optional.ofNullable(System.getenv("USER_TYPE")).orElse("");

            EnvContext envContext = buildEnvContext();

            return new EventMetadata(
                model,
                sessionId,
                userType,
                null,
                envContext,
                System.getenv("CLAUDE_CODE_ENTRYPOINT"),
                System.getenv("CLAUDE_AGENT_SDK_VERSION"),
                String.valueOf(System.console() != null),
                Optional.ofNullable(System.getenv("CLAUDE_CODE_CLIENT_TYPE")).orElse("cli"),
                buildProcessMetrics(),
                Optional.ofNullable(System.getenv("SWE_BENCH_RUN_ID")).orElse(""),
                Optional.ofNullable(System.getenv("SWE_BENCH_INSTANCE_ID")).orElse(""),
                Optional.ofNullable(System.getenv("SWE_BENCH_TASK_ID")).orElse(""),
                System.getenv("CLAUDE_CODE_AGENT_ID"),
                System.getenv("CLAUDE_CODE_PARENT_SESSION_ID"),
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );
        });
    }

    /**
     * Build the environment context object.
     * Translated from buildEnvContext() in metadata.ts
     */
    private EnvContext buildEnvContext() {
        String platform = Optional.ofNullable(System.getenv("CLAUDE_CODE_HOST_PLATFORM"))
            .orElseGet(() -> System.getProperty("os.name", "unknown").toLowerCase());
        String arch = System.getProperty("os.arch", "unknown");
        String javaVersion = System.getProperty("java.version", "unknown");
        boolean isCi = EnvUtils.isEnvTruthy(System.getenv("CI"));
        boolean isGithubAction = EnvUtils.isEnvTruthy(System.getenv("GITHUB_ACTIONS"));
        boolean isClaudeCodeAction = EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_ACTION"));
        boolean isClaudeCodeRemote = EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"));

        return new EnvContext(
            platform,
            platform,
            arch,
            javaVersion,
            System.getenv("TERM"),
            "",
            "java",
            false,
            isCi,
            EnvUtils.isEnvTruthy(System.getenv("CLAUBBIT")),
            isClaudeCodeRemote,
            "local-agent".equals(System.getenv("CLAUDE_CODE_ENTRYPOINT")),
            false,
            System.getenv("CLAUDE_CODE_REMOTE_ENVIRONMENT_TYPE"),
            System.getenv("CLAUDE_CODE_COWORKER_TYPE"),
            System.getenv("CLAUDE_CODE_CONTAINER_ID"),
            System.getenv("CLAUDE_CODE_REMOTE_SESSION_ID"),
            System.getenv("CLAUDE_CODE_TAGS"),
            isGithubAction,
            isClaudeCodeAction,
            false,
            "1.0.0",
            null,
            "",
            "production",
            isGithubAction ? System.getenv("GITHUB_EVENT_NAME") : null,
            isGithubAction ? System.getenv("RUNNER_ENVIRONMENT") : null,
            isGithubAction ? System.getenv("RUNNER_OS") : null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    /**
     * Build process metrics.
     * Translated from buildProcessMetrics() in metadata.ts
     */
    private ProcessMetrics buildProcessMetrics() {
        try {
            Runtime rt = Runtime.getRuntime();
            long totalMemory = rt.totalMemory();
            long freeMemory = rt.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            com.sun.management.OperatingSystemMXBean osMXBean =
                (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();

            double uptime = java.lang.management.ManagementFactory
                .getRuntimeMXBean().getUptime() / 1000.0;
            double cpuPercent = osMXBean.getCpuLoad() * 100;

            return new ProcessMetrics(
                uptime,
                totalMemory,
                totalMemory,
                usedMemory,
                0L,
                0L,
                null,
                0L,
                0L,
                Double.isNaN(cpuPercent) ? null : cpuPercent
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert EventMetadata to 1P event logging format (snake_case fields).
     * Translated from to1PEventFormat() in metadata.ts
     */
    public static Map<String, Object> to1PEventFormat(
            EventMetadata metadata,
            Map<String, Object> userMetadata,
            Map<String, Object> additionalMetadata) {

        Map<String, Object> env = new LinkedHashMap<>();
        EnvContext ctx = metadata.envContext();
        if (ctx != null) {
            env.put("platform", ctx.platform());
            env.put("platform_raw", ctx.platformRaw());
            env.put("arch", ctx.arch());
            env.put("node_version", ctx.nodeVersion());
            env.put("terminal", ctx.terminal() != null ? ctx.terminal() : "unknown");
            env.put("package_managers", ctx.packageManagers());
            env.put("runtimes", ctx.runtimes());
            env.put("is_running_with_bun", ctx.isRunningWithBun());
            env.put("is_ci", ctx.isCi());
            env.put("is_claubbit", ctx.isClaubbit());
            env.put("is_claude_code_remote", ctx.isClaudeCodeRemote());
            env.put("is_local_agent_mode", ctx.isLocalAgentMode());
            env.put("is_conductor", ctx.isConductor());
            env.put("is_github_action", ctx.isGithubAction());
            env.put("is_claude_code_action", ctx.isClaudeCodeAction());
            env.put("is_claude_ai_auth", ctx.isClaudeAiAuth());
            env.put("version", ctx.version());
            env.put("build_time", ctx.buildTime());
            env.put("deployment_environment", ctx.deploymentEnvironment());
            if (ctx.remoteEnvironmentType() != null)
                env.put("remote_environment_type", ctx.remoteEnvironmentType());
            if (ctx.coworkerType() != null)
                env.put("coworker_type", ctx.coworkerType());
            if (ctx.claudeCodeContainerId() != null)
                env.put("claude_code_container_id", ctx.claudeCodeContainerId());
            if (ctx.claudeCodeRemoteSessionId() != null)
                env.put("claude_code_remote_session_id", ctx.claudeCodeRemoteSessionId());
            if (ctx.tags() != null)
                env.put("tags", Arrays.stream(ctx.tags().split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .toList());
            if (ctx.githubEventName() != null)
                env.put("github_event_name", ctx.githubEventName());
            if (ctx.githubActionsRunnerEnvironment() != null)
                env.put("github_actions_runner_environment", ctx.githubActionsRunnerEnvironment());
            if (ctx.githubActionsRunnerOs() != null)
                env.put("github_actions_runner_os", ctx.githubActionsRunnerOs());
            if (ctx.githubActionRef() != null)
                env.put("github_action_ref", ctx.githubActionRef());
            if (ctx.wslVersion() != null)
                env.put("wsl_version", ctx.wslVersion());
            if (ctx.linuxDistroId() != null)
                env.put("linux_distro_id", ctx.linuxDistroId());
            if (ctx.linuxDistroVersion() != null)
                env.put("linux_distro_version", ctx.linuxDistroVersion());
            if (ctx.linuxKernel() != null)
                env.put("linux_kernel", ctx.linuxKernel());
            if (ctx.vcs() != null)
                env.put("vcs", ctx.vcs());
            if (ctx.versionBase() != null)
                env.put("version_base", ctx.versionBase());
        }

        // Core fields (snake_case)
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("session_id", metadata.sessionId());
        core.put("model", metadata.model());
        core.put("user_type", metadata.userType());
        core.put("is_interactive", "true".equals(metadata.isInteractive()));
        core.put("client_type", metadata.clientType());
        if (metadata.betas() != null) core.put("betas", metadata.betas());
        if (metadata.entrypoint() != null) core.put("entrypoint", metadata.entrypoint());
        if (metadata.agentSdkVersion() != null) core.put("agent_sdk_version", metadata.agentSdkVersion());
        if (metadata.sweBenchRunId() != null && !metadata.sweBenchRunId().isEmpty())
            core.put("swe_bench_run_id", metadata.sweBenchRunId());
        if (metadata.sweBenchInstanceId() != null && !metadata.sweBenchInstanceId().isEmpty())
            core.put("swe_bench_instance_id", metadata.sweBenchInstanceId());
        if (metadata.sweBenchTaskId() != null && !metadata.sweBenchTaskId().isEmpty())
            core.put("swe_bench_task_id", metadata.sweBenchTaskId());
        if (metadata.agentId() != null) core.put("agent_id", metadata.agentId());
        if (metadata.parentSessionId() != null) core.put("parent_session_id", metadata.parentSessionId());
        if (metadata.agentType() != null) core.put("agent_type", metadata.agentType());
        if (metadata.teamName() != null) core.put("team_name", metadata.teamName());

        // Additional fields
        Map<String, Object> additional = new LinkedHashMap<>();
        if (metadata.rh() != null) additional.put("rh", metadata.rh());
        if (Boolean.TRUE.equals(metadata.kairosActive())) additional.put("is_assistant_mode", true);
        if (metadata.skillMode() != null) additional.put("skill_mode", metadata.skillMode());
        if (metadata.observerMode() != null) additional.put("observer_mode", metadata.observerMode());
        if (additionalMetadata != null) additional.putAll(additionalMetadata);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("env", env);

        // Process metrics as base64-encoded JSON (mirrors TS: Buffer.from(jsonStringify(...)).toString('base64'))
        if (metadata.processMetrics() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
                String json = mapper.writeValueAsString(metadata.processMetrics());
                result.put("process", Base64.getEncoder().encodeToString(json.getBytes()));
            } catch (Exception ignored) {}
        }

        result.put("core", core);
        result.put("additional", additional);

        return Collections.unmodifiableMap(result);
    }
}
