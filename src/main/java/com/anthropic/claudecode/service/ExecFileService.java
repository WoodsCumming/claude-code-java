package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes external processes, mirroring the execFile / execFileNoThrow utilities
 * from the TypeScript source (src/utils/execFileNoThrow.ts).
 */
@Slf4j
@Service
public class ExecFileService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExecFileService.class);


    /**
     * Result of running an external command.
     * Translated from ExecResult in execFileNoThrow.ts
     */
    public record ExecResult(int code, String stdout, String stderr) {}

    /**
     * Execute a command, returning a result without throwing even on non-zero exit.
     * Translated from execFileNoThrow() in execFileNoThrow.ts
     */
    public ExecResult execNoThrow(String command, List<String> args) {
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        fullCommand.addAll(args);

        try {
            ProcessBuilder pb = new ProcessBuilder(fullCommand);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();

            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = out.readLine()) != null) stdoutBuf.append(line).append("\n");
                while ((line = err.readLine()) != null) stderrBuf.append(line).append("\n");
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            int code = finished ? process.exitValue() : -1;
            if (!finished) {
                process.destroyForcibly();
                log.warn("Process timed out: {}", fullCommand);
            }

            return new ExecResult(code, stdoutBuf.toString(), stderrBuf.toString());

        } catch (Exception e) {
            log.debug("execNoThrow failed for {}: {}", command, e.getMessage());
            return new ExecResult(-1, "", e.getMessage() != null ? e.getMessage() : "unknown error");
        }
    }
}
