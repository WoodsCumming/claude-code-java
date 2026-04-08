package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Editor utilities.
 * Translated from src/utils/editor.ts
 *
 * Provides utilities for detecting and launching external editors.
 * Supports both GUI editors (VS Code, Sublime Text, etc.) and terminal
 * editors (vim, nano, etc.).
 */
@Slf4j
public class EditorUtils {



    // GUI editors that open in a separate window and can be spawned detached
    // without fighting the TUI for stdin. VS Code forks (cursor, windsurf, codium)
    // are listed explicitly since none contain 'code' as a substring.
    private static final List<String> GUI_EDITORS = List.of(
        "code", "cursor", "windsurf", "codium",
        "subl", "atom", "gedit", "notepad++", "notepad"
    );

    // Editors that accept +N as a goto-line argument.
    private static final Pattern PLUS_N_EDITORS = Pattern.compile("\\b(vi|vim|nvim|nano|emacs|pico|micro|helix|hx)\\b");

    // VS Code and forks use -g file:line
    private static final Set<String> VSCODE_FAMILY = Set.of("code", "cursor", "windsurf", "codium");

    /**
     * Classify the editor as GUI or not.
     * Returns the matched GUI family name for goto-line argv selection,
     * or null for terminal editors.
     *
     * Uses basename so /home/alice/code/bin/nvim doesn't match 'code' via the
     * directory component.
     *
     * Translated from classifyGuiEditor() in editor.ts
     */
    public static String classifyGuiEditor(String editor) {
        if (editor == null || editor.isBlank()) return null;
        String firstToken = editor.split("\\s+")[0];
        String base = Path.of(firstToken).getFileName().toString().toLowerCase();
        return GUI_EDITORS.stream()
                .filter(base::contains)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the external editor command.
     * Respects $VISUAL and $EDITOR environment variables, then falls back to
     * platform defaults.
     *
     * Translated from getExternalEditor() in editor.ts
     */
    public static String getExternalEditor() {
        String visual = System.getenv("VISUAL");
        if (visual != null && !visual.isBlank()) return visual.trim();

        String editor = System.getenv("EDITOR");
        if (editor != null && !editor.isBlank()) return editor.trim();

        // On Windows, default to notepad (no PATH search needed)
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "start /wait notepad";
        }

        // Search for available editors in order of preference
        String[] candidates = {"code", "vi", "nano"};
        for (String candidate : candidates) {
            if (isCommandAvailable(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Launch a file in the user's external editor.
     *
     * For GUI editors (code, subl, etc.): spawns detached — the editor opens
     * in a separate window and Claude Code stays interactive.
     *
     * For terminal editors (vim, nvim, nano, etc.): blocks until the editor exits.
     *
     * Returns true if the editor was launched, false if no editor is available.
     *
     * Translated from openFileInExternalEditor() in editor.ts
     */
    public static boolean openFileInExternalEditor(String filePath, Integer line) {
        String editor = getExternalEditor();
        if (editor == null) return false;

        String[] parts = editor.split("\\s+");
        String binary = parts[0];
        List<String> extraArgs = Arrays.asList(parts).subList(1, parts.length);
        String guiFamily = classifyGuiEditor(editor);

        try {
            if (guiFamily != null) {
                // GUI editor: spawn detached
                List<String> gotoArgv = buildGotoArgv(guiFamily, filePath, line);
                List<String> cmd = new ArrayList<>();

                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    // On Windows, use shell to resolve .cmd/.bat wrappers
                    StringBuilder sb = new StringBuilder(editor);
                    for (String arg : gotoArgv) {
                        sb.append(" \"").append(arg.replace("\"", "\\\"")).append("\"");
                    }
                    cmd.addAll(List.of("cmd.exe", "/c", sb.toString()));
                } else {
                    cmd.add(binary);
                    cmd.addAll(extraArgs);
                    cmd.addAll(gotoArgv);
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();
                Process process = pb.start();
                process.toHandle().onExit().exceptionally(e -> {
                    log.debug("editor spawn failed: {}", e.getMessage());
                    return null;
                });
                // Detach (don't wait)
                return true;

            } else {
                // Terminal editor: block until it exits (alt-screen handoff handled
                // at the TUI layer; here we simply inherit stdio)
                String base = Path.of(binary).getFileName().toString();
                boolean useGotoLine = line != null && PLUS_N_EDITORS.matcher(base).find();

                List<String> cmd = new ArrayList<>();
                String os = System.getProperty("os.name", "").toLowerCase();

                if (os.contains("win")) {
                    String lineArg = useGotoLine ? "+" + line + " " : "";
                    cmd.addAll(List.of("cmd.exe", "/c",
                            editor + " " + lineArg + "\"" + filePath + "\""));
                } else {
                    cmd.add(binary);
                    cmd.addAll(extraArgs);
                    if (useGotoLine) cmd.add("+" + line);
                    cmd.add(filePath);
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();
                int exitCode = pb.start().waitFor();
                return exitCode == 0;
            }
        } catch (Exception e) {
            log.debug("editor spawn failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Convenience overload without a line number.
     */
    public static boolean openFileInExternalEditor(String filePath) {
        return openFileInExternalEditor(filePath, null);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Build goto-line argv for a GUI editor.
     * VS Code family uses -g file:line; subl uses bare file:line; others don't
     * support goto-line.
     */
    private static List<String> buildGotoArgv(String guiFamily, String filePath, Integer line) {
        if (line == null) return List.of(filePath);
        if (VSCODE_FAMILY.contains(guiFamily)) return List.of("-g", filePath + ":" + line);
        if ("subl".equals(guiFamily)) return List.of(filePath + ":" + line);
        return List.of(filePath);
    }

    /**
     * Check whether a command is available on PATH.
     */
    private static boolean isCommandAvailable(String command) {
        try {
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null) return false;
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File f = new File(dir, command);
                if (f.isFile() && f.canExecute()) return true;
                // macOS / Linux won't have .exe, but Windows might
                File fExe = new File(dir, command + ".exe");
                if (fExe.isFile() && fExe.canExecute()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private EditorUtils() {}
}
