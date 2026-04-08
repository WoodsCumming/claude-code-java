package com.anthropic.claudecode.model;

/**
 * FileEdit tool types.
 * Translated from src/tools/FileEditTool/types.ts
 */
public class FileEditTypes {

    /**
     * Input for a file edit operation.
     */
    public record FileEditInput(
        String filePath,
        String oldString,
        String newString,
        boolean replaceAll
    ) {}

    /**
     * Edit input without file path.
     */
    public record EditInput(
        String oldString,
        String newString,
        boolean replaceAll
    ) {}

    /**
     * Runtime file edit type.
     */
    public record FileEdit(
        String oldString,
        String newString,
        boolean replaceAll
    ) {}

    private FileEditTypes() {}
}
