package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.MemoryFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Memory command for managing Claude memory files (CLAUDE.md).
 *
 * Translated from src/commands/memory/index.ts and src/commands/memory/memory.tsx
 *
 * TypeScript original behaviour:
 *   - Opens a MemoryFileSelector dialog (local-jsx) listing all discovered CLAUDE.md files
 *   - On selection: creates the config directory + file if absent, then opens it in $VISUAL/$EDITOR
 *   - Reports the editor used and returns a "system" display message
 *
 * Java translation:
 *   - Without argument: lists all memory files and prints their paths
 *   - With --open / -o: opens the selected (or first) memory file in the system editor ($VISUAL/$EDITOR)
 *   - With --create: ensures ~/.claude/CLAUDE.md exists then opens it
 */
@Slf4j
@Component
@Command(
    name = "memory",
    description = "Edit Claude memory files"
)
public class MemoryCommand implements Callable<Integer> {



    /** Optional path override – open a specific memory file directly. */
    @Parameters(index = "0", description = "Memory file path to open", arity = "0..1")
    private String memoryPath;

    /** Open the selected file in the system editor ($VISUAL / $EDITOR). */
    @Option(names = {"--open", "-o"}, description = "Open the memory file in the configured editor")
    private boolean openInEditor;

    /** Create the user-level memory file if it does not exist. */
    @Option(names = {"--create", "-c"}, description = "Create a new memory file in the Claude config directory")
    private boolean createIfAbsent;

    private final MemoryFileService memoryFileService;

    @Autowired
    public MemoryCommand(MemoryFileService memoryFileService) {
        this.memoryFileService = memoryFileService;
    }

    @Override
    public Integer call() {
        try {
            List<Path> memoryFiles = memoryFileService.getMemoryFiles();

            // --create: ensure the user-level CLAUDE.md exists
            if (createIfAbsent) {
                Path userMemory = memoryFileService.getUserMemoryFilePath();
                Files.createDirectories(userMemory.getParent());
                if (!Files.exists(userMemory)) {
                    Files.createFile(userMemory);
                    System.out.println("Created memory file: " + userMemory);
                }
                memoryFiles = memoryFileService.getMemoryFiles();
            }

            if (memoryFiles.isEmpty()) {
                System.out.println("No memory files found.");
                System.out.println("Create a CLAUDE.md file in your project directory to add persistent memory.");
                System.out.println("Use --create to create the user-level memory file.");
                return 0;
            }

            // Determine target file
            Path target = resolveTarget(memoryFiles);

            // --open or explicit path: launch editor
            if (openInEditor || memoryPath != null) {
                return openFileInEditor(target);
            }

            // Default: list all memory files
            System.out.println("Memory files:");
            for (Path file : memoryFiles) {
                System.out.println("  " + memoryFileService.getRelativePath(file));
            }
            System.out.println();
            System.out.println("Use /memory --open to edit a file, or set $EDITOR / $VISUAL to configure your editor.");
            System.out.println("Learn more: https://code.claude.com/docs/en/memory");
            return 0;

        } catch (IOException e) {
            log.error("Failed to access memory files", e);
            System.out.println("Error accessing memory files: " + e.getMessage());
            return 1;
        }
    }

    private Path resolveTarget(List<Path> memoryFiles) {
        if (memoryPath != null) {
            return Path.of(memoryPath);
        }
        // Default to first discovered file
        return memoryFiles.get(0);
    }

    private int openFileInEditor(Path target) {
        String editor = System.getenv("VISUAL");
        String editorSource = "$VISUAL";
        if (editor == null || editor.isBlank()) {
            editor = System.getenv("EDITOR");
            editorSource = "$EDITOR";
        }
        if (editor == null || editor.isBlank()) {
            editor = "vi";
            editorSource = "default";
        }

        try {
            Process process = new ProcessBuilder(editor, target.toString())
                .inheritIO()
                .start();
            int exitCode = process.waitFor();
            // Invalidate the memory file cache after editing
            memoryFileService.clearCaches();

            String editorInfo = "default".equals(editorSource)
                ? "> To use a different editor, set the $EDITOR or $VISUAL environment variable."
                : String.format("> Using %s=\"%s\". To change editor, set $EDITOR or $VISUAL environment variable.",
                    editorSource, editor);

            System.out.println("Opened memory file at " + memoryFileService.getRelativePath(target));
            System.out.println();
            System.out.println(editorInfo);
            return exitCode;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to open memory file in editor", e);
            System.out.println("Error opening memory file: " + e.getMessage());
            return 1;
        }
    }
}
