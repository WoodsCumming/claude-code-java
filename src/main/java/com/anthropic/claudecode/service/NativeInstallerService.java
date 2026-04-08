package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Manages a managed local installation of the Claude CLI package.
 * Translated from src/utils/localInstaller.ts
 *
 * <p>The TS source places a node_modules-based local install under
 * {@code ~/.claude/local/} and exposes helpers to create, probe, and update it.
 * In Java the concept maps to a service that delegates npm calls through
 * {@link ShellService} and stores paths relative to the configured config home.</p>
 */
@Slf4j
@Service
public class NativeInstallerService {



    /**
     * Result of an install/update attempt.
     * Mirrors the {@code 'in_progress' | 'success' | 'install_failed'} union from the TS source.
     */
    public enum InstallResult {
        /** npm returned exit code 190 — another install is already running. */
        IN_PROGRESS,
        /** Package installed or updated successfully. */
        SUCCESS,
        /** Installation failed for any other reason. */
        INSTALL_FAILED
    }

    /** Shell type detected from the SHELL environment variable. */
    public enum ShellType {
        ZSH, BASH, FISH, UNKNOWN
    }

    private final GlobalConfigService globalConfigService;
    private final ShellService shellService;

    @Autowired
    public NativeInstallerService(GlobalConfigService globalConfigService,
                                   ShellService shellService) {
        this.globalConfigService = globalConfigService;
        this.shellService = shellService;
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the local installation directory ({@code <configHome>/local}).
     * Translated from {@code getLocalInstallDir()} in localInstaller.ts.
     */
    public String getLocalInstallDir() {
        String configHome = globalConfigService.getClaudeConfigHomeDir().toString();
        return configHome + "/local";
    }

    /**
     * Returns the path to the local {@code claude} wrapper script.
     * Translated from {@code getLocalClaudePath()} in localInstaller.ts.
     */
    public String getLocalClaudePath() {
        return getLocalInstallDir() + "/claude";
    }

    // -------------------------------------------------------------------------
    // isRunningFromLocalInstallation
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the JVM was launched from inside the managed local
     * installation directory (path contains {@code /.claude/local/}).
     * Translated from {@code isRunningFromLocalInstallation()} in localInstaller.ts.
     */
    public boolean isRunningFromLocalInstallation() {
        // In Java we probe the class-path / jar location instead of process.argv[1].
        String classPath = System.getProperty("java.class.path", "");
        return classPath.contains("/.claude/local/");
    }

    // -------------------------------------------------------------------------
    // ensureLocalPackageEnvironment
    // -------------------------------------------------------------------------

    /**
     * Creates the {@code <configHome>/local} directory tree, a {@code package.json},
     * and an executable wrapper script if they do not already exist.
     * Translated from {@code ensureLocalPackageEnvironment()} in localInstaller.ts.
     *
     * @return {@code true} on success, {@code false} if setup failed.
     */
    public CompletableFuture<Boolean> ensureLocalPackageEnvironment() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path localDir = Path.of(getLocalInstallDir());

                // Create installation directory (idempotent)
                Files.createDirectories(localDir);

                // Create package.json if missing (O_EXCL equivalent: CREATE_NEW)
                Path packageJson = localDir.resolve("package.json");
                writeIfMissing(packageJson,
                        "{\n  \"name\": \"claude-local\",\n  \"version\": \"0.0.1\",\n  \"private\": true\n}\n",
                        null);

                // Create wrapper script if missing
                Path wrapperPath = localDir.resolve("claude");
                String wrapperContent = "#!/bin/sh\nexec \"" + localDir + "/node_modules/.bin/claude\" \"$@\"\n";
                boolean created = writeIfMissing(wrapperPath, wrapperContent, null);
                if (created) {
                    // Ensure executable bit (mode masked by umask during write)
                    try {
                        Set<PosixFilePermission> perms = EnumSet.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.GROUP_EXECUTE,
                                PosixFilePermission.OTHERS_READ,
                                PosixFilePermission.OTHERS_EXECUTE);
                        Files.setPosixFilePermissions(wrapperPath, perms);
                    } catch (UnsupportedOperationException ignored) {
                        // Non-POSIX filesystem (Windows); skip chmod
                    }
                }

                return true;
            } catch (Exception e) {
                log.error("Failed to set up local package environment", e);
                return false;
            }
        });
    }

    // -------------------------------------------------------------------------
    // installOrUpdateClaudePackage
    // -------------------------------------------------------------------------

    /**
     * Installs or updates the Claude CLI npm package in the local directory.
     * Translated from {@code installOrUpdateClaudePackage()} in localInstaller.ts.
     *
     * @param channel         release channel ({@code "latest"} or {@code "stable"})
     * @param specificVersion optional explicit version string; overrides channel when non-null
     * @return install result
     */
    public CompletableFuture<InstallResult> installOrUpdateClaudePackage(
            String channel, String specificVersion) {

        return ensureLocalPackageEnvironment().thenCompose(envOk -> {
            if (!envOk) {
                return CompletableFuture.completedFuture(InstallResult.INSTALL_FAILED);
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String versionSpec = (specificVersion != null && !specificVersion.isBlank())
                            ? specificVersion
                            : "stable".equals(channel) ? "stable" : "latest";

                    String packageSpec = "@anthropic-ai/claude-code@" + versionSpec;
                    ShellService.ExecResult result = shellService.executeInDir(
                            "npm install " + packageSpec, getLocalInstallDir(), 120_000);

                    if (result.getExitCode() != 0) {
                        log.error("npm install failed: {}", result.getStderr());
                        return result.getExitCode() == 190
                                ? InstallResult.IN_PROGRESS
                                : InstallResult.INSTALL_FAILED;
                    }

                    // Record installMethod = 'local' in global config
                    globalConfigService.updateGlobalConfig(cfg -> {
                        cfg.setInstallMethod("local");
                        return cfg;
                    });

                    return InstallResult.SUCCESS;
                } catch (Exception e) {
                    log.error("Unexpected error during package install", e);
                    return InstallResult.INSTALL_FAILED;
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // localInstallationExists
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the managed local installation binary exists.
     * Translated from {@code localInstallationExists()} in localInstaller.ts.
     */
    public CompletableFuture<Boolean> localInstallationExists() {
        return CompletableFuture.supplyAsync(() -> {
            Path bin = Path.of(getLocalInstallDir(), "node_modules", ".bin", "claude");
            return Files.exists(bin);
        });
    }

    // -------------------------------------------------------------------------
    // getShellType
    // -------------------------------------------------------------------------

    /**
     * Detects the active shell from the {@code SHELL} environment variable.
     * Translated from {@code getShellType()} in localInstaller.ts.
     */
    public ShellType getShellType() {
        String shellPath = System.getenv("SHELL");
        if (shellPath == null) return ShellType.UNKNOWN;
        if (shellPath.contains("zsh"))  return ShellType.ZSH;
        if (shellPath.contains("bash")) return ShellType.BASH;
        if (shellPath.contains("fish")) return ShellType.FISH;
        return ShellType.UNKNOWN;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Writes {@code content} to {@code path} only when the file does not already exist.
     * Uses {@link StandardOpenOption#CREATE_NEW} (equivalent to POSIX {@code O_EXCL}).
     *
     * @return {@code true} if the file was created, {@code false} if it already existed.
     */
    private boolean writeIfMissing(Path path, String content, Set<PosixFilePermission> mode)
            throws IOException {
        try {
            if (mode != null) {
                Files.writeString(path, content, StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(path, mode);
            } else {
                Files.writeString(path, content, StandardOpenOption.CREATE_NEW);
            }
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }
}
