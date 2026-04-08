package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * /files slash-command service.
 * Translated from src/commands/files/files.ts
 *
 * Returns a formatted list of all files currently loaded into the context
 * window (i.e. files whose content has been read and cached in the current
 * session).  Paths are rendered relative to the current working directory so
 * they are compact and human-readable.
 */
@Slf4j
@Service
public class FilesCommandService {



    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final WorkingDirectoryService workingDirectoryService;

    @Autowired
    public FilesCommandService(WorkingDirectoryService workingDirectoryService) {
        this.workingDirectoryService = workingDirectoryService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Execute the /files command.
     * Mirrors {@code call()} in files.ts.
     *
     * @param readFileStatePaths Absolute paths of all files currently cached in
     *                           the file-state cache (equivalent to
     *                           {@code cacheKeys(context.readFileState)}).
     * @return A {@link FilesCommandResult} containing the formatted output.
     */
    public CompletableFuture<FilesCommandResult> call(Set<String> readFileStatePaths) {
        return CompletableFuture.supplyAsync(() -> {

            if (readFileStatePaths == null || readFileStatePaths.isEmpty()) {
                return new FilesCommandResult("No files in context");
            }

            String cwd = workingDirectoryService.getCwd();
            Path cwdPath = Paths.get(cwd);

            String fileList = readFileStatePaths.stream()
                .sorted()
                .map(absPath -> {
                    try {
                        return cwdPath.relativize(Paths.get(absPath)).toString();
                    } catch (IllegalArgumentException e) {
                        // Fall back to the absolute path if relativization fails
                        // (e.g. paths on different Windows drive letters).
                        return absPath;
                    }
                })
                .collect(Collectors.joining("\n"));

            return new FilesCommandResult("Files in context:\n" + fileList);
        });
    }

    // ---------------------------------------------------------------------------
    // Result type
    // ---------------------------------------------------------------------------

    /**
     * Holds the text output of a /files invocation.
     *
     * @param value The formatted string listing all context files.
     */
    public record FilesCommandResult(String value) {
        /** Convenience accessor matching the TypeScript {@code { type, value }} shape. */
        public String type() {
            return "text";
        }
    }
}
