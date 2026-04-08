package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.PathUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Jupyter notebook editing tool.
 * Translated from src/tools/NotebookEditTool/NotebookEditTool.ts
 *
 * Edits Jupyter notebook (.ipynb) cells.
 */
@Slf4j
@Component
public class NotebookEditTool extends AbstractTool<NotebookEditTool.Input, NotebookEditTool.Output> {



    public static final String TOOL_NAME = "NotebookEdit";

    private final ObjectMapper objectMapper;

    @Autowired
    public NotebookEditTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String getSearchHint() { return "edit jupyter notebook cells"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "notebook_path", Map.of("type", "string", "description", "Absolute path to the Jupyter notebook file"),
                "new_source", Map.of("type", "string", "description", "The new source for the cell"),
                "cell_number", Map.of("type", "integer", "description", "The 0-indexed cell number to edit"),
                "cell_id", Map.of("type", "string", "description", "The ID of the cell to edit"),
                "cell_type", Map.of("type", "string", "enum", List.of("code", "markdown")),
                "edit_mode", Map.of("type", "string", "enum", List.of("replace", "insert", "delete"))
            ),
            "required", List.of("notebook_path", "new_source")
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
                String notebookPath = PathUtils.expandPath(args.getNotebookPath());
                File notebookFile = new File(notebookPath);

                if (!notebookFile.exists()) {
                    throw new FileNotFoundException("Notebook not found: " + notebookPath);
                }

                // Read notebook JSON
                String content = Files.readString(notebookFile.toPath());
                Map<String, Object> notebook = objectMapper.readValue(content, Map.class);

                List<Map<String, Object>> cells = (List<Map<String, Object>>) notebook.get("cells");
                if (cells == null) {
                    cells = new ArrayList<>();
                    notebook.put("cells", cells);
                }

                String editMode = args.getEditMode() != null ? args.getEditMode() : "replace";

                switch (editMode) {
                    case "replace" -> {
                        int cellIdx = findCellIndex(cells, args.getCellId(), args.getCellNumber());
                        if (cellIdx >= 0) {
                            Map<String, Object> cell = cells.get(cellIdx);
                            cell.put("source", args.getNewSource());
                            if (args.getCellType() != null) {
                                cell.put("cell_type", args.getCellType());
                            }
                        }
                    }
                    case "insert" -> {
                        String cellType = args.getCellType() != null ? args.getCellType() : "code";
                        Map<String, Object> newCell = new LinkedHashMap<>();
                        newCell.put("cell_type", cellType);
                        newCell.put("source", args.getNewSource());
                        newCell.put("metadata", Map.of());
                        newCell.put("outputs", List.of());
                        newCell.put("execution_count", null);

                        int insertIdx = args.getCellNumber() != null
                            ? Math.min(args.getCellNumber() + 1, cells.size())
                            : cells.size();
                        cells.add(insertIdx, newCell);
                    }
                    case "delete" -> {
                        int cellIdx = findCellIndex(cells, args.getCellId(), args.getCellNumber());
                        if (cellIdx >= 0) {
                            cells.remove(cellIdx);
                        }
                    }
                }

                // Write back
                String updatedContent = objectMapper.writeValueAsString(notebook);
                Files.writeString(notebookFile.toPath(), updatedContent);

                return result(Output.builder()
                    .notebookPath(notebookPath)
                    .editMode(editMode)
                    .build());

            } catch (Exception e) {
                throw new RuntimeException("Notebook edit failed: " + e.getMessage(), e);
            }
        });
    }

    private int findCellIndex(List<Map<String, Object>> cells, String cellId, Integer cellNumber) {
        if (cellId != null) {
            for (int i = 0; i < cells.size(); i++) {
                Object id = cells.get(i).get("id");
                if (cellId.equals(id)) return i;
            }
        }
        if (cellNumber != null && cellNumber >= 0 && cellNumber < cells.size()) {
            return cellNumber;
        }
        return -1;
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Editing notebook: " + input.getNotebookPath());
    }

    @Override
    public boolean isReadOnly(Input input) { return false; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of("type", "tool_result", "tool_use_id", toolUseId,
            "content", "Notebook edited: " + content.getNotebookPath());
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Input {
        private String notebookPath;
        private String newSource;
        private Integer cellNumber;
        private String cellId;
        private String cellType;
        private String editMode;
    
        public String getCellId() { return cellId; }
    
        public Integer getCellNumber() { return cellNumber; }
    
        public String getCellType() { return cellType; }
    
        public String getEditMode() { return editMode; }
    
        public String getNewSource() { return newSource; }
    
        public String getNotebookPath() { return notebookPath; }
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor 
    public static class Output {
        private String notebookPath;
        private String editMode;
    
        public String getNotebookPath() { return notebookPath; }
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private String notebookPath;
            private String editMode;
            public OutputBuilder notebookPath(String v) { this.notebookPath = v; return this; }
            public OutputBuilder editMode(String v) { this.editMode = v; return this; }
            public Output build() {
                Output o = new Output();
                o.notebookPath = notebookPath;
                o.editMode = editMode;
                return o;
            }
        }
    }
}
