package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.LspService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LSP (Language Server Protocol) tool.
 * Translated from src/tools/LSPTool/LSPTool.ts
 *
 * Provides IDE-like features through language servers.
 */
@Slf4j
@Component
public class LspTool extends AbstractTool<LspTool.Input, LspTool.Output> {



    public static final String TOOL_NAME = "LSP";

    private final LspService lspService;

    @Autowired
    public LspTool(LspService lspService) {
        this.lspService = lspService;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of(
                    "type", "string",
                    "enum", List.of("hover", "definition", "references", "symbols", "diagnostics"),
                    "description", "The LSP action to perform"
                ),
                "file_path", Map.of("type", "string", "description", "The file path"),
                "line", Map.of("type", "integer", "description", "Line number (1-indexed)"),
                "column", Map.of("type", "integer", "description", "Column number (1-indexed)"),
                "query", Map.of("type", "string", "description", "Query for workspace symbols")
            ),
            "required", List.of("action", "file_path")
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
                String result = switch (args.getAction()) {
                    case "diagnostics" -> getDiagnostics(args.getFilePath());
                    default -> "LSP action '" + args.getAction() + "' not implemented";
                };

                return this.result(Output.builder()
                    .result(result)
                    .action(args.getAction())
                    .build());

            } catch (Exception e) {
                throw new RuntimeException("LSP error: " + e.getMessage(), e);
            }
        });
    }

    private String getDiagnostics(String filePath) {
        List<LspService.Diagnostic> diagnostics = lspService.getDiagnostics("default", filePath);
        if (diagnostics.isEmpty()) return "No diagnostics found";

        StringBuilder sb = new StringBuilder();
        for (LspService.Diagnostic d : diagnostics) {
            sb.append(String.format("[%s] Line %d: %s\n",
                d.getSeverity(), d.getLine(), d.getMessage()));
        }
        return sb.toString();
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("LSP " + input.getAction() + ": " + input.getFilePath());
    }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public boolean isConcurrencySafe(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId,
            "content", content.getResult() != null ? content.getResult() : "");
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String action;
        private String filePath;
        private Integer line;
        private Integer column;
        private String query;
    
        public String getAction() { return action; }
    
        public String getFilePath() { return filePath; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String action;
        private String result;
    
        public String getResult() { return result; }
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String action;
            private String result;
            public OutputBuilder action(String v) { this.action = v; return this; }
            public OutputBuilder result(String v) { this.result = v; return this; }
            public Output build() {
                Output o = new Output();
                o.action = action;
                o.result = result;
                return o;
            }
        }
    }
}
