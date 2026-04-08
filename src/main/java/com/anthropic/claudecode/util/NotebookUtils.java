package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jupyter notebook utilities.
 * Translated from src/utils/notebook.ts
 *
 * Reads and processes Jupyter notebook (.ipynb) files into cell data structures
 * suitable for inclusion in tool results.
 */
@Slf4j
public class NotebookUtils {



    private static final int LARGE_OUTPUT_THRESHOLD = 10_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern CELL_ID_PATTERN = Pattern.compile("^cell-(\\d+)$");

    // =========================================================================
    // Domain types
    // =========================================================================

    /**
     * A processed notebook cell source — ready for tool-result inclusion.
     * Translated from NotebookCellSource in types/notebook.ts
     */
    public record NotebookCellSource(
            String cellType,
            String source,
            String language,
            String cellId,
            Integer executionCount,
            List<NotebookCellSourceOutput> outputs
    ) {
        /** Builder-style with-method for outputs. */
        public NotebookCellSource withOutputs(List<NotebookCellSourceOutput> newOutputs) {
            return new NotebookCellSource(cellType, source, language, cellId, executionCount, newOutputs);
        }
    }

    /**
     * Processed output of a notebook cell.
     * Translated from NotebookCellSourceOutput in types/notebook.ts
     */
    public record NotebookCellSourceOutput(
            String outputType,
            String text,
            NotebookOutputImage image
    ) {}

    /**
     * Image data extracted from a cell output.
     */
    public record NotebookOutputImage(String imageData, String mediaType) {}

    /**
     * A tool-result content block: either text or an image.
     * Sealed to mirror Anthropic SDK ContentBlockParam subset.
     */
    public sealed interface ToolBlock permits ToolBlock.TextBlock, ToolBlock.ImageBlock {
        record TextBlock(String text) implements ToolBlock {}
        record ImageBlock(String mediaType, String data) implements ToolBlock {}
    }

    /**
     * Notebook tool result (analogous to ToolResultBlockParam).
     */
    public record ToolResultBlock(String toolUseId, List<ToolBlock> content) {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Read a Jupyter notebook file and return processed cell data.
     * Translated from readNotebook() in notebook.ts
     *
     * @param notebookPath path to the .ipynb file
     * @param cellId       optional cell ID — if given, only that cell is returned
     */
    public static CompletableFuture<List<NotebookCellSource>> readNotebook(
            String notebookPath, String cellId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String content = Files.readString(Paths.get(notebookPath));
                Map<String, Object> notebook = OBJECT_MAPPER.readValue(content, Map.class);

                // Resolve language
                String language = extractLanguage(notebook);

                List<Map<String, Object>> rawCells =
                        (List<Map<String, Object>>) notebook.get("cells");
                if (rawCells == null) rawCells = List.of();

                if (cellId != null && !cellId.isBlank()) {
                    for (int i = 0; i < rawCells.size(); i++) {
                        Map<String, Object> raw = rawCells.get(i);
                        String id = getCellId(raw, i);
                        if (cellId.equals(id)) {
                            return List.of(processCell(raw, i, language, true));
                        }
                    }
                    throw new IllegalArgumentException(
                            "Cell with ID \"" + cellId + "\" not found in notebook");
                }

                List<NotebookCellSource> result = new ArrayList<>();
                for (int i = 0; i < rawCells.size(); i++) {
                    result.add(processCell(rawCells.get(i), i, language, false));
                }
                return result;

            } catch (Exception e) {
                throw new RuntimeException("Failed to read notebook: " + notebookPath, e);
            }
        });
    }

    /** Overload without cellId. */
    public static CompletableFuture<List<NotebookCellSource>> readNotebook(String notebookPath) {
        return readNotebook(notebookPath, null);
    }

    /**
     * Map notebook cell data to a ToolResultBlock with sophisticated text merging.
     * Translated from mapNotebookCellsToToolResult() in notebook.ts
     */
    public static ToolResultBlock mapNotebookCellsToToolResult(
            List<NotebookCellSource> data, String toolUseId) {

        List<ToolBlock> allBlocks = new ArrayList<>();
        for (NotebookCellSource cell : data) {
            allBlocks.addAll(getToolResultFromCell(cell));
        }

        // Merge adjacent TextBlocks
        List<ToolBlock> merged = new ArrayList<>();
        for (ToolBlock block : allBlocks) {
            if (block instanceof ToolBlock.TextBlock tb && !merged.isEmpty()
                    && merged.getLast() instanceof ToolBlock.TextBlock prev) {
                merged.set(merged.size() - 1, new ToolBlock.TextBlock(prev.text() + "\n" + tb.text()));
            } else {
                merged.add(block);
            }
        }

        return new ToolResultBlock(toolUseId, merged);
    }

    /**
     * Parse a cell-N style cell ID string to its zero-based index.
     * Returns empty if the string does not match the cell-N pattern.
     * Translated from parseCellId() in notebook.ts
     */
    public static OptionalInt parseCellId(String cellId) {
        if (cellId == null) return OptionalInt.empty();
        Matcher m = CELL_ID_PATTERN.matcher(cellId);
        if (!m.matches()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    // =========================================================================
    // Cell processing
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static NotebookCellSource processCell(Map<String, Object> raw, int index,
                                                   String codeLanguage, boolean includeLargeOutputs) {
        String cellType = (String) raw.get("cell_type");
        String cellId = getCellId(raw, index);

        Object sourceObj = raw.get("source");
        String source;
        if (sourceObj instanceof List<?> list) {
            source = String.join("", (List<String>) list);
        } else if (sourceObj instanceof String s) {
            source = s;
        } else {
            source = "";
        }

        String language = "code".equals(cellType) ? codeLanguage : null;

        Integer executionCount = null;
        if ("code".equals(cellType)) {
            Object ec = raw.get("execution_count");
            if (ec instanceof Number n) executionCount = n.intValue();
        }

        List<NotebookCellSourceOutput> outputs = null;
        List<Map<String, Object>> rawOutputs = (List<Map<String, Object>>) raw.get("outputs");
        if ("code".equals(cellType) && rawOutputs != null && !rawOutputs.isEmpty()) {
            List<NotebookCellSourceOutput> processed = rawOutputs.stream()
                    .map(NotebookUtils::processOutput)
                    .filter(Objects::nonNull)
                    .toList();

            if (!includeLargeOutputs && isLargeOutputs(processed)) {
                outputs = List.of(new NotebookCellSourceOutput(
                        "stream",
                        "Outputs are too large to include. Use bash tool with: " +
                        "cat <notebook_path> | jq '.cells[" + index + "].outputs'",
                        null));
            } else {
                outputs = processed;
            }
        }

        return new NotebookCellSource(cellType, source, language, cellId, executionCount, outputs);
    }

    @SuppressWarnings("unchecked")
    private static NotebookCellSourceOutput processOutput(Map<String, Object> raw) {
        String outputType = (String) raw.get("output_type");
        return switch (outputType) {
            case "stream" -> new NotebookCellSourceOutput(
                    outputType, processOutputText(raw.get("text")), null);
            case "execute_result", "display_data" -> {
                Map<String, Object> data = (Map<String, Object>) raw.get("data");
                String text = data != null ? processOutputText(data.get("text/plain")) : null;
                NotebookOutputImage image = data != null ? extractImage(data) : null;
                yield new NotebookCellSourceOutput(outputType, text, image);
            }
            case "error" -> {
                String ename = (String) raw.get("ename");
                String evalue = (String) raw.get("evalue");
                List<String> traceback = (List<String>) raw.get("traceback");
                String text = ename + ": " + evalue + "\n" +
                        (traceback != null ? String.join("\n", traceback) : "");
                yield new NotebookCellSourceOutput(outputType, processOutputText(text), null);
            }
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static String processOutputText(Object textObj) {
        if (textObj == null) return null;
        String raw;
        if (textObj instanceof List<?> list) {
            raw = String.join("", (List<String>) list);
        } else if (textObj instanceof String s) {
            raw = s;
        } else {
            raw = textObj.toString();
        }
        // Truncate very long outputs
        if (raw.length() > LARGE_OUTPUT_THRESHOLD * 2) {
            return raw.substring(0, LARGE_OUTPUT_THRESHOLD * 2) + "\n[truncated]";
        }
        return raw;
    }

    private static NotebookOutputImage extractImage(Map<String, Object> data) {
        if (data.get("image/png") instanceof String png) {
            return new NotebookOutputImage(png.replaceAll("\\s", ""), "image/png");
        }
        if (data.get("image/jpeg") instanceof String jpeg) {
            return new NotebookOutputImage(jpeg.replaceAll("\\s", ""), "image/jpeg");
        }
        return null;
    }

    private static boolean isLargeOutputs(List<NotebookCellSourceOutput> outputs) {
        int size = 0;
        for (NotebookCellSourceOutput o : outputs) {
            if (o == null) continue;
            if (o.text() != null) size += o.text().length();
            if (o.image() != null) size += o.image().imageData().length();
            if (size > LARGE_OUTPUT_THRESHOLD) return true;
        }
        return false;
    }

    // =========================================================================
    // Tool result conversion
    // =========================================================================

    private static List<ToolBlock> getToolResultFromCell(NotebookCellSource cell) {
        List<ToolBlock> result = new ArrayList<>();
        result.add(cellContentToToolBlock(cell));
        if (cell.outputs() != null) {
            for (NotebookCellSourceOutput output : cell.outputs()) {
                result.addAll(cellOutputToToolBlocks(output));
            }
        }
        return result;
    }

    private static ToolBlock.TextBlock cellContentToToolBlock(NotebookCellSource cell) {
        List<String> metadata = new ArrayList<>();
        if (!"code".equals(cell.cellType())) {
            metadata.add("<cell_type>" + cell.cellType() + "</cell_type>");
        }
        if (cell.language() != null && !"python".equals(cell.language())
                && "code".equals(cell.cellType())) {
            metadata.add("<language>" + cell.language() + "</language>");
        }
        String cellContent = "<cell id=\"" + cell.cellId() + "\">" +
                String.join("", metadata) + cell.source() +
                "</cell id=\"" + cell.cellId() + "\">";
        return new ToolBlock.TextBlock(cellContent);
    }

    private static List<ToolBlock> cellOutputToToolBlocks(NotebookCellSourceOutput output) {
        List<ToolBlock> blocks = new ArrayList<>();
        if (output.text() != null && !output.text().isBlank()) {
            blocks.add(new ToolBlock.TextBlock("\n" + output.text()));
        }
        if (output.image() != null) {
            blocks.add(new ToolBlock.ImageBlock(output.image().mediaType(), output.image().imageData()));
        }
        return blocks;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static String extractLanguage(Map<String, Object> notebook) {
        try {
            Map<String, Object> meta = (Map<String, Object>) notebook.get("metadata");
            if (meta == null) return "python";
            Map<String, Object> langInfo = (Map<String, Object>) meta.get("language_info");
            if (langInfo == null) return "python";
            String name = (String) langInfo.get("name");
            return name != null ? name : "python";
        } catch (Exception e) {
            return "python";
        }
    }

    private static String getCellId(Map<String, Object> raw, int index) {
        Object id = raw.get("id");
        if (id instanceof String s && !s.isBlank()) return s;
        return "cell-" + index;
    }

    private NotebookUtils() {}
}
