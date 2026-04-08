package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.RipgrepUtils;
import com.anthropic.claudecode.util.PlatformUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Collects diagnostic information about the Claude Code installation for the
 * {@code doctor} command.
 * Translated from src/utils/doctorDiagnostic.ts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorDiagnosticService {



    // -----------------------------------------------------------------------
    // Domain types
    // -----------------------------------------------------------------------

    /**
     * Broad category of how the CLI was installed.
     * Translated from the TypeScript union type {@code InstallationType}.
     */
    public enum InstallationType {
        NPM_GLOBAL,
        NPM_LOCAL,
        NATIVE,
        PACKAGE_MANAGER,
        DEVELOPMENT,
        UNKNOWN
    }

    /**
     * A single warning / remediation pair surfaced by the diagnostic.
     */
    public record Warning(String issue, String fix) {}

    /**
     * Summary of the ripgrep helper status.
     */
    public record RipgrepStatus(boolean working, String mode, String systemPath) {}

    /**
     * A detected parallel installation of the CLI.
     */
    public record Installation(String type, String path) {}

    /**
     * Full diagnostic report returned by {@link #getDoctorDiagnostic()}.
     * Translated from the TypeScript type {@code DiagnosticInfo}.
     */
    public record DiagnosticInfo(
            InstallationType installationType,
            String version,
            String installationPath,
            String invokedBinary,
            String configInstallMethod,
            String autoUpdates,
            Boolean hasUpdatePermissions,
            List<Installation> multipleInstallations,
            List<Warning> warnings,
            String recommendation,
            String packageManager,
            RipgrepStatus ripgrepStatus
    ) {}

    // -----------------------------------------------------------------------
    // Dependencies (inject real implementations as needed)
    // -----------------------------------------------------------------------

    private final GlobalConfigService globalConfigService;
    private final RipgrepUtils ripgrepUtils;
    private final PlatformUtils platformUtils;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Gather and return a {@link DiagnosticInfo} snapshot.
     * Translated from {@code getDoctorDiagnostic()} in doctorDiagnostic.ts
     */
    public CompletableFuture<DiagnosticInfo> getDoctorDiagnostic() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                InstallationType installationType = getCurrentInstallationType();
                String version = getVersion();
                String installationPath = getInstallationPath(installationType);
                String invokedBinary = getInvokedBinary(installationType);
                List<Installation> multipleInstallations = detectMultipleInstallations();
                List<Warning> warnings = new ArrayList<>(
                        detectConfigurationIssues(installationType));

                // Warn about leftover npm installs when running native
                if (installationType == InstallationType.NATIVE) {
                    for (Installation install : multipleInstallations) {
                        switch (install.type()) {
                            case "npm-global" -> warnings.add(new Warning(
                                    "Leftover npm global installation at " + install.path(),
                                    "Run: npm -g uninstall @anthropic-ai/claude-code"));
                            case "npm-global-orphan" -> warnings.add(new Warning(
                                    "Orphaned npm global package at " + install.path(),
                                    "Run: rm -rf " + install.path()));
                            case "npm-local" -> warnings.add(new Warning(
                                    "Leftover npm local installation at " + install.path(),
                                    "Run: rm -rf " + install.path()));
                        }
                    }
                }

                String configInstallMethod = globalConfigService.getInstallMethod()
                        .orElse("not set");

                Boolean hasUpdatePermissions = null;
                if (installationType == InstallationType.NPM_GLOBAL) {
                    hasUpdatePermissions = checkGlobalInstallPermissions();
                    if (!hasUpdatePermissions) {
                        warnings.add(new Warning(
                                "Insufficient permissions for auto-updates",
                                "Do one of: (1) Re-install node without sudo, or "
                                + "(2) Use `claude install` for native installation"));
                    }
                }

                RipgrepStatus ripgrepStatus = buildRipgrepStatus();
                String packageManager = installationType == InstallationType.PACKAGE_MANAGER
                        ? getPackageManager() : null;

                String autoUpdates = buildAutoUpdatesString();

                return new DiagnosticInfo(
                        installationType,
                        version,
                        installationPath,
                        invokedBinary,
                        configInstallMethod,
                        autoUpdates,
                        hasUpdatePermissions,
                        multipleInstallations,
                        warnings,
                        null,
                        packageManager,
                        ripgrepStatus);
            } catch (Exception e) {
                log.error("Error collecting doctor diagnostic", e);
                throw new RuntimeException("Failed to collect diagnostic info", e);
            }
        });
    }

    /**
     * Determine the installation type.
     * Translated from {@code getCurrentInstallationType()} in doctorDiagnostic.ts
     */
    public InstallationType getCurrentInstallationType() {
        String nodeEnv = System.getenv("NODE_ENV");
        if ("development".equals(nodeEnv)) {
            return InstallationType.DEVELOPMENT;
        }

        // Check common npm global paths
        String invokedPath = ProcessHandle.current().info().command().orElse("");
        List<String> npmGlobalPaths = List.of(
                "/usr/local/lib/node_modules",
                "/usr/lib/node_modules",
                "/opt/homebrew/lib/node_modules",
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "/.nvm/versions/node/");

        if (npmGlobalPaths.stream().anyMatch(invokedPath::contains)) {
            return InstallationType.NPM_GLOBAL;
        }

        if (invokedPath.contains("/npm/") || invokedPath.contains("/nvm/")) {
            return InstallationType.NPM_GLOBAL;
        }

        // Check for native local binary
        Path nativeBin = Path.of(System.getProperty("user.home"), ".local", "bin", "claude");
        if (Files.exists(nativeBin)) {
            return InstallationType.NATIVE;
        }

        return InstallationType.UNKNOWN;
    }

    /**
     * Detect all Claude Code installations present on the system.
     * Translated from {@code detectMultipleInstallations()} in doctorDiagnostic.ts
     */
    public List<Installation> detectMultipleInstallations() {
        List<Installation> installations = new ArrayList<>();
        String home = System.getProperty("user.home");

        // Check for local (~/.claude/local) installation
        Path localPath = Path.of(home, ".claude", "local");
        if (Files.isDirectory(localPath)) {
            installations.add(new Installation("npm-local", localPath.toString()));
        }

        // Check for native binary
        Path nativeBin = Path.of(home, ".local", "bin", "claude");
        if (Files.exists(nativeBin)) {
            installations.add(new Installation("native", nativeBin.toString()));
        }

        return installations;
    }

    /**
     * Detect configuration issues for the given installation type.
     * Translated from {@code detectConfigurationIssues()} in doctorDiagnostic.ts
     */
    public List<Warning> detectConfigurationIssues(InstallationType type) {
        List<Warning> warnings = new ArrayList<>();

        if (type == InstallationType.DEVELOPMENT) {
            return warnings;
        }

        if (type == InstallationType.NATIVE) {
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                String home = System.getProperty("user.home");
                String localBin = Path.of(home, ".local", "bin").toString();
                boolean inPath = List.of(pathEnv.split(":"))
                        .stream()
                        .map(p -> p.replaceAll("/+$", ""))
                        .anyMatch(p -> p.equals(localBin)
                                || p.equals("~/.local/bin")
                                || p.equals("$HOME/.local/bin"));
                if (!inPath) {
                    warnings.add(new Warning(
                            "Native installation exists but ~/.local/bin is not in your PATH",
                            "Run: echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> ~/.bashrc "
                            + "then open a new terminal or run: source ~/.bashrc"));
                }
            }
        }

        return warnings;
    }

    /**
     * Check for Linux-specific glob-pattern sandbox limitations.
     * Translated from {@code detectLinuxGlobPatternWarnings()} in doctorDiagnostic.ts
     */
    public List<Warning> detectLinuxGlobPatternWarnings() {
        List<Warning> warnings = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            return warnings;
        }

        // Delegate to SandboxService if available; placeholder here
        List<String> globPatterns = getLinuxSandboxGlobPatterns();
        if (!globPatterns.isEmpty()) {
            String displayPatterns = String.join(", ",
                    globPatterns.subList(0, Math.min(3, globPatterns.size())));
            int remaining = globPatterns.size() - 3;
            String patternList = remaining > 0
                    ? displayPatterns + " (" + remaining + " more)"
                    : displayPatterns;

            warnings.add(new Warning(
                    "Glob patterns in sandbox permission rules are not fully supported on Linux",
                    "Found " + globPatterns.size() + " pattern(s): " + patternList
                    + ". On Linux, glob patterns in Edit/Read rules will be ignored."));
        }

        return warnings;
    }

    // -----------------------------------------------------------------------
    // Private helpers (thin wrappers — override / inject for full impl)
    // -----------------------------------------------------------------------

    private String getVersion() {
        String v = getClass().getPackage().getImplementationVersion();
        return v != null ? v : "unknown";
    }

    private String getInstallationPath(InstallationType type) {
        if (type == InstallationType.NATIVE) {
            Path nativeBin = Path.of(System.getProperty("user.home"), ".local", "bin", "claude");
            if (Files.exists(nativeBin)) return nativeBin.toString();
        }
        return ProcessHandle.current().info().command().orElse("unknown");
    }

    private String getInvokedBinary(InstallationType type) {
        return ProcessHandle.current().info().command().orElse("unknown");
    }

    private boolean checkGlobalInstallPermissions() {
        // Stub — full implementation would check npm prefix write access
        Path npmGlobal = Path.of("/usr/local/lib/node_modules");
        return Files.isWritable(npmGlobal);
    }

    private RipgrepStatus buildRipgrepStatus() {
        // Stub — delegate to RipgrepUtils if wired in
        return new RipgrepStatus(true, "builtin", null);
    }

    private String getPackageManager() {
        return "unknown";
    }

    private String buildAutoUpdatesString() {
        return "enabled";
    }

    private List<String> getLinuxSandboxGlobPatterns() {
        return List.of();
    }
}
