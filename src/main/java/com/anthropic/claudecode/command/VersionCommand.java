package com.anthropic.claudecode.command;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Version command for printing the current version.
 * Translated from src/commands/version.ts
 */
@Slf4j
@Component
@Command(
    name = "version",
    description = "Print the version this session is running"
)
public class VersionCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VersionCommand.class);


    @Value("${app.version:unknown}")
    private String version;

    @Value("${app.build-time:}")
    private String buildTime;

    @Override
    public Integer call() {
        if (buildTime != null && !buildTime.isBlank()) {
            System.out.println(version + " (built " + buildTime + ")");
        } else {
            System.out.println(version);
        }
        return 0;
    }
}
