package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.GitUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Enter worktree tool.
 * Translated from src/tools/EnterWorktreeTool/EnterWorktreeTool.ts
 *
 * Creates an isolated git worktree and switches the session into it.
 */
@Slf4j
@Component
public class EnterWorktreeTool extends AbstractTool<EnterWorktreeTool.Input, EnterWorktreeTool.Output> {



    public static final String TOOL_NAME = "EnterWorktree";

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String getSearchHint() { return "create an isolated git worktree and switch into it"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "name", Map.of(
                    "type", "string",
                    "description", "Optional name for the worktree. A random name is generated if not provided."
                )
            ),
            "additionalProperties", false
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String cwd = System.getProperty("user.dir");
                Optional<String> gitRoot = GitUtils.findGitRoot(cwd);

                if (gitRoot.isEmpty()) {
                    throw new IllegalStateException("Not in a git repository");
                }

                String worktreeName = args.getName() != null
                    ? args.getName()
                    : "worktree-" + UUID.randomUUID().toString().substring(0, 8);

                // Create worktree
                String worktreePath = createWorktree(gitRoot.get(), worktreeName);

                return result(Output.builder()
                    .worktreePath(worktreePath)
                    .message("Created worktree at: " + worktreePath)
                    .build());

            } catch (Exception e) {
                throw new RuntimeException("Failed to create worktree: " + e.getMessage(), e);
            }
        });
    }

    private String createWorktree(String repoPath, String name) throws Exception {
        String worktreePath = repoPath + "/.claude/worktrees/" + name;
        new File(repoPath + "/.claude/worktrees").mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
            "git", "worktree", "add", "-b", "worktree/" + name, worktreePath
        );
        pb.directory(new File(repoPath));
        Process p = pb.start();
        int exitCode = p.waitFor();

        if (exitCode != 0) {
            String error = new String(p.getErrorStream().readAllBytes());
            throw new RuntimeException("git worktree add failed: " + error);
        }

        return worktreePath;
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Creating worktree" +
            (input.getName() != null ? ": " + input.getName() : ""));
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", content.getMessage());
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String name;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String worktreePath;
        private String worktreeBranch;
        private String message;
    }
}
