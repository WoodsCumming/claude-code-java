package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.ShellService;
import com.anthropic.claudecode.util.PlatformUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * PowerShell tool for Windows.
 * Translated from src/tools/PowerShellTool/PowerShellTool.tsx
 *
 * Executes PowerShell commands on Windows.
 * Only enabled on Windows platforms.
 */
@Slf4j
@Component
public class PowerShellTool extends AbstractTool<PowerShellTool.Input, PowerShellTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PowerShellTool.class);


    public static final String TOOL_NAME = "PowerShell";
    private static final long DEFAULT_TIMEOUT_MS = 120_000;
    private static final long MAX_TIMEOUT_MS = 600_000;

    private final ShellService shellService;

    @Autowired
    public PowerShellTool(ShellService shellService) {
        this.shellService = shellService;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public boolean isEnabled() {
        return PlatformUtils.isWindows()
            && com.anthropic.claudecode.util.EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_POWERSHELL_TOOL"));
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description", "The PowerShell command to execute"),
                "timeout", Map.of("type", "integer", "description", "Optional timeout in milliseconds"),
                "description", Map.of("type", "string", "description", "Description of the command")
            ),
            "required", List.of("command")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        if (!PlatformUtils.isWindows()) {
            return futureResult(Output.builder()
                .stdout("")
                .stderr("PowerShell is only available on Windows")
                .exitCode(1)
                .interrupted(false)
                .build());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long timeoutMs = args.getTimeout() != null
                    ? Math.min(args.getTimeout(), MAX_TIMEOUT_MS)
                    : DEFAULT_TIMEOUT_MS;

                // Use PowerShell on Windows
                ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", args.getCommand()
                );
                pb.redirectErrorStream(false);

                Process process = pb.start();
                String stdout = new String(process.getInputStream().readAllBytes());
                String stderr = new String(process.getErrorStream().readAllBytes());
                boolean completed = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

                if (!completed) {
                    process.destroyForcibly();
                    return this.result(Output.builder()
                        .stdout(stdout)
                        .stderr(stderr)
                        .exitCode(-1)
                        .interrupted(true)
                        .build());
                }

                return this.result(Output.builder()
                    .stdout(stdout)
                    .stderr(stderr)
                    .exitCode(process.exitValue())
                    .interrupted(false)
                    .build());

            } catch (Exception e) {
                throw new RuntimeException("PowerShell execution failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Running PowerShell: " + input.getCommand());
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public String userFacingName(Input input) {
        return input != null && input.getCommand() != null
            ? input.getCommand().substring(0, Math.min(60, input.getCommand().length()))
            : TOOL_NAME;
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        StringBuilder sb = new StringBuilder();
        if (content.getStdout() != null && !content.getStdout().isEmpty()) sb.append(content.getStdout());
        if (content.getStderr() != null && !content.getStderr().isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(content.getStderr());
        }
        if (content.getExitCode() != 0) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Exit code: ").append(content.getExitCode());
        }
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", sb.toString());
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String command;
        private Long timeout;
        private String description;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String stdout;
        private String stderr;
        private int exitCode;
        private boolean interrupted;
    }
}
