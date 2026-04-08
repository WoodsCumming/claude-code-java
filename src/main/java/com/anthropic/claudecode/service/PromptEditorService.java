package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.TempFileUtils;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Prompt editor service for editing content in external editors.
 * Translated from src/utils/promptEditor.ts
 *
 * Allows users to edit prompts in their preferred text editor, with support
 * for pasted-text reference expansion/re-collapse.
 */
@Slf4j
@Service
public class PromptEditorService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PromptEditorService.class);


    /** Editor command overrides — mirrors EDITOR_OVERRIDES in promptEditor.ts */
    private static final Map<String, String> EDITOR_OVERRIDES = Map.of(
            "code", "code -w",    // VS Code: wait for file to be closed
            "subl", "subl --wait" // Sublime Text: wait for file to be closed
    );

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Edit a file in the external editor.
     * Translated from editFileInEditor() in promptEditor.ts
     *
     * @param filePath Absolute path to the file to edit
     * @return EditorResult with the edited content, or null content on cancel/error
     */
    public EditorResult editFileInEditor(String filePath) {
        String editor = getExternalEditor();
        if (editor == null || editor.isBlank()) {
            return new EditorResult(null, null);
        }

        // Apply override command if available, otherwise use the editor as-is
        String baseEditor = editor.split("\\s+")[0];
        String editorCmd = EDITOR_OVERRIDES.getOrDefault(baseEditor, editor);

        try {
            // Verify the file exists before launching editor
            if (!new File(filePath).exists()) {
                return new EditorResult(null, null);
            }

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", editorCmd + " \"" + filePath + "\"");
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String editorName = toDisplayName(baseEditor);
                return new EditorResult(null, editorName + " exited with code " + exitCode);
            }

            String editedContent = Files.readString(Paths.get(filePath));
            return new EditorResult(editedContent, null);

        } catch (Exception e) {
            return new EditorResult(null, e.getMessage());
        }
    }

    /**
     * Edit the current prompt in the external editor, with optional pasted-content
     * expansion and re-collapse.
     * Translated from editPromptInEditor() in promptEditor.ts
     *
     * @param currentPrompt  The current prompt text (may contain pasted-text refs)
     * @param pastedContents Optional map of pasteId -> PastedContent for expansion
     * @return EditorResult with the (re-collapsed) edited prompt, or null on cancel
     */
    public EditorResult editPromptInEditor(String currentPrompt,
                                           Map<Integer, PastedContent> pastedContents) {
        String tempFile = TempFileUtils.generateTempFilePath("claude-edit", ".md");

        try {
            // Expand any pasted text references before editing
            String expandedPrompt = (pastedContents != null && !pastedContents.isEmpty())
                    ? expandPastedTextRefs(currentPrompt, pastedContents)
                    : currentPrompt;

            Files.writeString(Paths.get(tempFile), expandedPrompt != null ? expandedPrompt : "");

            // Delegate to editFileInEditor
            EditorResult result = editFileInEditor(tempFile);

            if (result.getContent() == null) {
                return result;
            }

            // Trim a single trailing newline if present (common editor behaviour)
            String finalContent = result.getContent();
            if (finalContent.endsWith("\n") && !finalContent.endsWith("\n\n")) {
                finalContent = finalContent.substring(0, finalContent.length() - 1);
            }

            // Re-collapse pasted content if it was not edited
            if (pastedContents != null && !pastedContents.isEmpty()) {
                finalContent = recollapsePastedContent(finalContent, currentPrompt, pastedContents);
            }

            return new EditorResult(finalContent, null);

        } catch (Exception e) {
            return new EditorResult(null, e.getMessage());
        } finally {
            // Clean up temp file — ignore cleanup errors
            try { new File(tempFile).delete(); } catch (Exception ignored) {}
        }
    }

    /** Convenience overload with no pasted contents. */
    public EditorResult editPromptInEditor(String currentPrompt) {
        return editPromptInEditor(currentPrompt, null);
    }

    // =========================================================================
    // Editor detection
    // =========================================================================

    /**
     * Get the external editor command from environment variables or platform default.
     * Mirrors getExternalEditor() in editor.ts: checks VISUAL then EDITOR, then defaults.
     */
    public String getExternalEditor() {
        String editor = System.getenv("VISUAL");
        if (editor != null && !editor.isBlank()) return editor;

        editor = System.getenv("EDITOR");
        if (editor != null && !editor.isBlank()) return editor;

        // Platform defaults
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return "vi";
        if (os.contains("win")) return "notepad";
        return "nano";
    }

    // =========================================================================
    // Pasted content helpers
    // =========================================================================

    /**
     * Re-collapse expanded pasted text by finding content that matches pastedContents
     * and replacing it with the corresponding reference placeholder.
     * Translated from recollapsePastedContent() in promptEditor.ts
     */
    private String recollapsePastedContent(String editedPrompt,
                                            String originalPrompt,
                                            Map<Integer, PastedContent> pastedContents) {
        String collapsed = editedPrompt;

        for (Map.Entry<Integer, PastedContent> entry : pastedContents.entrySet()) {
            int pasteId = entry.getKey();
            PastedContent content = entry.getValue();

            if (!"text".equals(content.type())) {
                continue;
            }

            String contentStr = content.content();
            int contentIndex = collapsed.indexOf(contentStr);
            if (contentIndex != -1) {
                int numLines = countLines(contentStr);
                String ref = formatPastedTextRef(pasteId, numLines);
                collapsed = collapsed.substring(0, contentIndex)
                        + ref
                        + collapsed.substring(contentIndex + contentStr.length());
            }
        }

        return collapsed;
    }

    /**
     * Expand pasted-text references (e.g. {@code <paste-1 lines=5 />}) into their full content.
     * Mirrors expandPastedTextRefs() from history.ts
     */
    private String expandPastedTextRefs(String prompt, Map<Integer, PastedContent> pastedContents) {
        if (prompt == null) return "";
        String expanded = prompt;
        for (Map.Entry<Integer, PastedContent> entry : pastedContents.entrySet()) {
            int pasteId = entry.getKey();
            PastedContent content = entry.getValue();
            if (!"text".equals(content.type())) continue;

            int numLines = countLines(content.content());
            String ref = formatPastedTextRef(pasteId, numLines);
            expanded = expanded.replace(ref, content.content());
        }
        return expanded;
    }

    /** Format a pasted-text reference placeholder. Mirrors formatPastedTextRef() in history.ts */
    private static String formatPastedTextRef(int pasteId, int numLines) {
        return "<paste-" + pasteId + " lines=" + numLines + " />";
    }

    /** Count lines in a string (number of newline characters). */
    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int count = 0;
        for (char c : s.toCharArray()) {
            if (c == '\n') count++;
        }
        return count;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Convert an editor binary name to a display name. Mirrors toIDEDisplayName() in ide.ts */
    private static String toDisplayName(String editor) {
        return switch (editor) {
            case "code" -> "VS Code";
            case "subl" -> "Sublime Text";
            case "vim", "vi" -> "Vim";
            case "nvim" -> "Neovim";
            case "emacs" -> "Emacs";
            case "nano" -> "nano";
            case "notepad" -> "Notepad";
            default -> editor;
        };
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Result of an editor invocation.
     * Translated from EditorResult in promptEditor.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EditorResult {
        /** The edited content, or {@code null} if the user cancelled or an error occurred. */
        private String content;
        /** Error message if the editor exited with a non-zero code; {@code null} on success. */
        private String error;

        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
        /** Returns true if an error occurred. */
        public boolean hasError() { return error != null; }
        /** Convenience accessor matching record-style syntax. */
        public String error() { return error; }
        /** Convenience accessor matching record-style syntax. */
        public String content() { return content; }
    }

    /**
     * A piece of pasted content held in the prompt's paste store.
     * Mirrors the PastedContent type from config.ts
     */
    public record PastedContent(String type, String content) {}
}
