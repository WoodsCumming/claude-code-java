package com.anthropic.claudecode.util;

import java.util.List;

/**
 * Configuration types and factory helpers for IDE diff views.
 * Translated from src/components/permissions/FilePermissionDialog/ideDiffConfig.ts
 *
 * Provides immutable records representing diff configurations and a factory
 * method for the common single-edit case.
 */
public final class IdeDiffConfig {

    private IdeDiffConfig() {}

    // -------------------------------------------------------------------------
    // Data records
    // -------------------------------------------------------------------------

    /**
     * A single string substitution within a file edit.
     * Translated from the FileEdit interface in ideDiffConfig.ts
     */
    public record FileEdit(
            String oldString,
            String newString,
            boolean replaceAll) {

        /** Convenience constructor – replaceAll defaults to false. */
        public FileEdit(String oldString, String newString) {
            this(oldString, newString, false);
        }
    }

    /**
     * Specifies how a single file should be shown in the IDE diff view.
     * Translated from the IDEDiffConfig interface in ideDiffConfig.ts
     */
    public record IDEDiffConfig(
            String filePath,
            List<FileEdit> edits,
            EditMode editMode) {

        /** Create a config with no edits (read-only view). */
        public IDEDiffConfig(String filePath) {
            this(filePath, List.of(), null);
        }
    }

    /**
     * Maps the 'single' | 'multiple' union type from TypeScript.
     */
    public enum EditMode {
        SINGLE,
        MULTIPLE
    }

    /**
     * Input shape for a multi-edit change, e.g. from the FileEditTool.
     * Translated from IDEDiffChangeInput in ideDiffConfig.ts
     */
    public record IDEDiffChangeInput(
            String filePath,
            List<FileEdit> edits) {}

    // -------------------------------------------------------------------------
    // Generic diff support interface
    // -------------------------------------------------------------------------

    /**
     * Contract for tools that can produce an IDE diff config from their input
     * and re-apply modified edits back onto that input.
     * Translated from IDEDiffSupport<TInput extends ToolInput> in ideDiffConfig.ts
     *
     * @param <T> the specific tool-input type
     */
    public interface IDEDiffSupport<T> {

        /** Build the diff configuration from the tool's current input. */
        IDEDiffConfig getConfig(T input);

        /** Return a new input with the provided modified edits applied. */
        T applyChanges(T input, List<FileEdit> modifiedEdits);
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /**
     * Creates an {@link IDEDiffConfig} for the common single-edit scenario.
     * Translated from createSingleEditDiffConfig() in ideDiffConfig.ts
     *
     * @param filePath   the file being edited
     * @param oldString  the text to replace
     * @param newString  the replacement text
     * @param replaceAll whether to replace every occurrence
     * @return an immutable {@link IDEDiffConfig} with {@link EditMode#SINGLE}
     */
    public static IDEDiffConfig createSingleEditDiffConfig(
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {

        return new IDEDiffConfig(
                filePath,
                List.of(new FileEdit(oldString, newString, replaceAll)),
                EditMode.SINGLE);
    }

    /**
     * Convenience overload – replaceAll defaults to {@code false}.
     */
    public static IDEDiffConfig createSingleEditDiffConfig(
            String filePath,
            String oldString,
            String newString) {
        return createSingleEditDiffConfig(filePath, oldString, newString, false);
    }
}
