package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.WorkingDirectoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Add-dir command for adding a new working directory.
 * Translated from src/commands/add-dir/
 */
@Slf4j
@Component
@Command(
    name = "add-dir",
    description = "Add a new working directory"
)
public class AddDirCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AddDirCommand.class);


    @Parameters(index = "0", description = "Path to add as working directory")
    private String path;

    private final WorkingDirectoryService workingDirectoryService;

    @Autowired
    public AddDirCommand(WorkingDirectoryService workingDirectoryService) {
        this.workingDirectoryService = workingDirectoryService;
    }

    @Override
    public Integer call() {
        if (path == null || path.isBlank()) {
            System.out.println("Please provide a directory path.");
            return 1;
        }

        // Resolve to absolute path
        String absolutePath = Paths.get(path).toAbsolutePath().normalize().toString();
        File dir = new File(absolutePath);

        if (!dir.exists()) {
            System.out.println("Path " + absolutePath + " was not found.");
            return 1;
        }

        if (!dir.isDirectory()) {
            String parentDir = dir.getParent();
            System.out.println(path + " is not a directory. Did you mean to add the parent directory " + parentDir + "?");
            return 1;
        }

        // Check if already in working directory
        if (workingDirectoryService.isPathInWorkingDirectory(absolutePath)) {
            String workingDir = workingDirectoryService.getContainingWorkingDirectory(absolutePath);
            System.out.println(path + " is already accessible within the existing working directory " + workingDir + ".");
            return 0;
        }

        workingDirectoryService.addWorkingDirectory(absolutePath);
        System.out.println("Added " + absolutePath + " as a working directory.");
        return 0;
    }
}
