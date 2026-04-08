package com.anthropic.claudecode.util;

import java.util.List;

/**
 * Primitive tools hidden from direct model use when REPL mode is on,
 * but still accessible inside the REPL VM context.
 * Translated from src/tools/REPLTool/primitiveTools.ts
 *
 * <p>In the TypeScript codebase these are actual Tool instances. Here we
 * represent them as string tool-name constants so that display-side code
 * (collapseReadSearch, renderers) can classify / render virtual messages for
 * these tools even when they are absent from the filtered execution tools list.</p>
 */
public class ReplPrimitiveTools {

    // Individual tool name constants matching the TypeScript tool classes
    public static final String FILE_READ_TOOL_NAME   = "Read";
    public static final String FILE_WRITE_TOOL_NAME  = "Write";
    public static final String FILE_EDIT_TOOL_NAME   = "Edit";
    public static final String GLOB_TOOL_NAME        = "Glob";
    public static final String GREP_TOOL_NAME        = "Grep";
    public static final String BASH_TOOL_NAME        = "Bash";
    public static final String NOTEBOOK_EDIT_TOOL_NAME = "NotebookEdit";
    public static final String AGENT_TOOL_NAME       = "Agent";

    private static volatile List<String> primitiveToolNames;

    /**
     * Returns the ordered list of primitive tool names available in the REPL VM context.
     * Lazy singleton — mirrors the lazy getter pattern in primitiveTools.ts to
     * avoid initialization-order issues.
     * Translated from getReplPrimitiveTools() in primitiveTools.ts
     */
    public static List<String> getReplPrimitiveToolNames() {
        if (primitiveToolNames == null) {
            synchronized (ReplPrimitiveTools.class) {
                if (primitiveToolNames == null) {
                    primitiveToolNames = List.of(
                            FILE_READ_TOOL_NAME,
                            FILE_WRITE_TOOL_NAME,
                            FILE_EDIT_TOOL_NAME,
                            GLOB_TOOL_NAME,
                            GREP_TOOL_NAME,
                            BASH_TOOL_NAME,
                            NOTEBOOK_EDIT_TOOL_NAME,
                            AGENT_TOOL_NAME
                    );
                }
            }
        }
        return primitiveToolNames;
    }

    /**
     * Returns true if the given tool name is a REPL primitive tool.
     */
    public static boolean isReplPrimitiveTool(String toolName) {
        return getReplPrimitiveToolNames().contains(toolName);
    }

    private ReplPrimitiveTools() {}
}
