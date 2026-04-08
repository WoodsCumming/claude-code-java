package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.ExecFileNoThrowUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Shell completion cache service.
 *
 * Translated from src/utils/completionCache.ts
 *
 * Generates and caches completion scripts for zsh, bash, and fish shells,
 * then installs a source line in the shell's rc file.
 */
@Slf4j
@Service
public class CompletionCacheService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompletionCacheService.class);


    private static final String EOL = "\n";

    // ------------------------------------------------------------------
    // Shell detection — mirrors detectShell() in completionCache.ts
    // ------------------------------------------------------------------

    /**
     * Detects the current shell and returns the shell configuration.
     * Returns empty if the shell is unsupported or SHELL is not set.
     */
    public Optional<ShellInfo> detectShell() {
        String shell = System.getenv("SHELL");
        if (shell == null) shell = "";

        String home = System.getProperty("user.home");
        String claudeDir = home + "/.claude";

        if (shell.endsWith("/zsh") || shell.endsWith("/zsh.exe")) {
            String cacheFile = claudeDir + "/completion.zsh";
            return Optional.of(new ShellInfo(
                    "zsh",
                    home + "/.zshrc",
                    cacheFile,
                    "[[ -f \"" + cacheFile + "\" ]] && source \"" + cacheFile + "\"",
                    "zsh"
            ));
        }

        if (shell.endsWith("/bash") || shell.endsWith("/bash.exe")) {
            String cacheFile = claudeDir + "/completion.bash";
            return Optional.of(new ShellInfo(
                    "bash",
                    home + "/.bashrc",
                    cacheFile,
                    "[ -f \"" + cacheFile + "\" ] && source \"" + cacheFile + "\"",
                    "bash"
            ));
        }

        if (shell.endsWith("/fish") || shell.endsWith("/fish.exe")) {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            if (xdg == null || xdg.isBlank()) xdg = home + "/.config";
            String cacheFile = claudeDir + "/completion.fish";
            return Optional.of(new ShellInfo(
                    "fish",
                    xdg + "/fish/config.fish",
                    cacheFile,
                    "[ -f \"" + cacheFile + "\" ] && source \"" + cacheFile + "\"",
                    "fish"
            ));
        }

        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Setup — mirrors setupShellCompletion() in completionCache.ts
    // ------------------------------------------------------------------

    /**
     * Generates and caches the completion script, then adds a source line to
     * the shell's rc file. Returns a user-facing status message.
     *
     * Mirrors setupShellCompletion(theme) in completionCache.ts
     */
    public CompletableFuture<String> setupShellCompletion() {
        return CompletableFuture.supplyAsync(() -> {
            Optional<ShellInfo> shellOpt = detectShell();
            if (shellOpt.isEmpty()) return "";

            ShellInfo shell = shellOpt.get();

            // Ensure the cache directory exists
            try {
                Files.createDirectories(Paths.get(shell.getCacheFile()).getParent());
            } catch (IOException e) {
                log.warn("Could not create completion cache dir: {}", e.getMessage());
                return EOL + "Could not write " + shell.getName() + " completion cache" + EOL +
                        "Run manually: claude completion " + shell.getShellFlag() + " > " + shell.getCacheFile() + EOL;
            }

            // Generate the completion script by invoking the claude binary
            String claudeBin = ProcessHandle.current().info().command().orElse("claude");
            ExecFileNoThrowUtils.ExecResult result;
            try {
                result = ExecFileNoThrowUtils.execFileNoThrow(
                        claudeBin,
                        List.of("completion", shell.getShellFlag(), "--output", shell.getCacheFile())
                ).get();
            } catch (Exception e) {
                return EOL + "Could not generate " + shell.getName() + " shell completions" + EOL +
                        "Run manually: claude completion " + shell.getShellFlag() + " > " + shell.getCacheFile() + EOL;
            }

            if (result.code() != 0) {
                return EOL + "Could not generate " + shell.getName() + " shell completions" + EOL +
                        "Run manually: claude completion " + shell.getShellFlag() + " > " + shell.getCacheFile() + EOL;
            }

            // Check if rc file already sources completions
            String existing = "";
            Path rcPath = Paths.get(shell.getRcFile());
            try {
                existing = Files.readString(rcPath);
                if (existing.contains("claude completion") || existing.contains(shell.getCacheFile())) {
                    return EOL + "Shell completions updated for " + shell.getName() + EOL +
                            "See " + shell.getRcFile() + EOL;
                }
            } catch (NoSuchFileException ignored) {
                // rc file doesn't exist yet — we'll create it below
            } catch (IOException e) {
                log.warn("Could not read rc file {}: {}", shell.getRcFile(), e.getMessage());
                return EOL + "Could not install " + shell.getName() + " shell completions" + EOL +
                        "Add this to " + shell.getRcFile() + ":" + EOL + shell.getCompletionLine() + EOL;
            }

            // Append source line to rc file
            try {
                Files.createDirectories(rcPath.getParent());
                String separator = (!existing.isEmpty() && !existing.endsWith("\n")) ? "\n" : "";
                String content = existing + separator + "\n# Claude Code shell completions\n" +
                        shell.getCompletionLine() + "\n";
                Files.writeString(rcPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                return EOL + "Installed " + shell.getName() + " shell completions" + EOL +
                        "Added to " + shell.getRcFile() + EOL +
                        "Run: source " + shell.getRcFile() + EOL;
            } catch (IOException e) {
                log.warn("Could not write rc file {}: {}", shell.getRcFile(), e.getMessage());
                return EOL + "Could not install " + shell.getName() + " shell completions" + EOL +
                        "Add this to " + shell.getRcFile() + ":" + EOL + shell.getCompletionLine() + EOL;
            }
        });
    }

    // ------------------------------------------------------------------
    // Regenerate — mirrors regenerateCompletionCache() in completionCache.ts
    // ------------------------------------------------------------------

    /**
     * Regenerates cached shell completion scripts in ~/.claude/.
     * Called after {@code claude update} so completions stay in sync.
     *
     * Mirrors regenerateCompletionCache() in completionCache.ts
     */
    public CompletableFuture<Void> regenerateCompletionCache() {
        return CompletableFuture.runAsync(() -> {
            Optional<ShellInfo> shellOpt = detectShell();
            if (shellOpt.isEmpty()) return;

            ShellInfo shell = shellOpt.get();
            log.debug("update: Regenerating {} completion cache", shell.getName());

            String claudeBin = ProcessHandle.current().info().command().orElse("claude");
            try {
                ExecFileNoThrowUtils.ExecResult result = ExecFileNoThrowUtils.execFileNoThrow(
                        claudeBin,
                        List.of("completion", shell.getShellFlag(), "--output", shell.getCacheFile())
                ).get();

                if (result.code() != 0) {
                    log.debug("update: Failed to regenerate {} completion cache", shell.getName());
                    return;
                }
            } catch (Exception e) {
                log.debug("update: Failed to regenerate {} completion cache: {}", shell.getName(), e.getMessage());
                return;
            }

            log.debug("update: Regenerated {} completion cache at {}", shell.getName(), shell.getCacheFile());
        });
    }

    // ------------------------------------------------------------------
    // Nested types
    // ------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ShellInfo {
        private String name;
        private String rcFile;
        private String cacheFile;
        private String completionLine;
        private String shellFlag;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getRcFile() { return rcFile; }
        public void setRcFile(String v) { rcFile = v; }
        public String getCacheFile() { return cacheFile; }
        public void setCacheFile(String v) { cacheFile = v; }
        public String getCompletionLine() { return completionLine; }
        public void setCompletionLine(String v) { completionLine = v; }
        public String getShellFlag() { return shellFlag; }
        public void setShellFlag(String v) { shellFlag = v; }
    }
}
