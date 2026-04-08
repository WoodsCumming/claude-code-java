package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * IDE integration service.
 * Manages detection of and connections to IDEs (VS Code, JetBrains) via lockfiles/MCP.
 * Translated from src/utils/ide.ts
 */
@Slf4j
@Service
public class IdeService {



    // =========================================================================
    // IDE type enum — translated from IdeType union type in ide.ts
    // =========================================================================

    public enum IdeType {
        CURSOR("cursor", IdeKind.VSCODE, "Cursor",
                new String[]{"Cursor Helper", "Cursor.app"}, new String[]{"cursor.exe"}, new String[]{"cursor"}),
        WINDSURF("windsurf", IdeKind.VSCODE, "Windsurf",
                new String[]{"Windsurf Helper", "Windsurf.app"}, new String[]{"windsurf.exe"}, new String[]{"windsurf"}),
        VSCODE("vscode", IdeKind.VSCODE, "VS Code",
                new String[]{"Visual Studio Code", "Code Helper"}, new String[]{"code.exe"}, new String[]{"code"}),
        PYCHARM("pycharm", IdeKind.JETBRAINS, "PyCharm",
                new String[]{"PyCharm"}, new String[]{"pycharm64.exe"}, new String[]{"pycharm"}),
        INTELLIJ("intellij", IdeKind.JETBRAINS, "IntelliJ IDEA",
                new String[]{"IntelliJ IDEA"}, new String[]{"idea64.exe"}, new String[]{"idea", "intellij"}),
        WEBSTORM("webstorm", IdeKind.JETBRAINS, "WebStorm",
                new String[]{"WebStorm"}, new String[]{"webstorm64.exe"}, new String[]{"webstorm"}),
        PHPSTORM("phpstorm", IdeKind.JETBRAINS, "PhpStorm",
                new String[]{"PhpStorm"}, new String[]{"phpstorm64.exe"}, new String[]{"phpstorm"}),
        RUBYMINE("rubymine", IdeKind.JETBRAINS, "RubyMine",
                new String[]{"RubyMine"}, new String[]{"rubymine64.exe"}, new String[]{"rubymine"}),
        CLION("clion", IdeKind.JETBRAINS, "CLion",
                new String[]{"CLion"}, new String[]{"clion64.exe"}, new String[]{"clion"}),
        GOLAND("goland", IdeKind.JETBRAINS, "GoLand",
                new String[]{"GoLand"}, new String[]{"goland64.exe"}, new String[]{"goland"}),
        RIDER("rider", IdeKind.JETBRAINS, "Rider",
                new String[]{"Rider"}, new String[]{"rider64.exe"}, new String[]{"rider"}),
        DATAGRIP("datagrip", IdeKind.JETBRAINS, "DataGrip",
                new String[]{"DataGrip"}, new String[]{"datagrip64.exe"}, new String[]{"datagrip"}),
        APPCODE("appcode", IdeKind.JETBRAINS, "AppCode",
                new String[]{"AppCode"}, new String[]{"appcode.exe"}, new String[]{"appcode"}),
        DATASPELL("dataspell", IdeKind.JETBRAINS, "DataSpell",
                new String[]{"DataSpell"}, new String[]{"dataspell64.exe"}, new String[]{"dataspell"}),
        AQUA("aqua", IdeKind.JETBRAINS, "Aqua",
                new String[]{}, new String[]{"aqua64.exe"}, new String[]{}),
        GATEWAY("gateway", IdeKind.JETBRAINS, "Gateway",
                new String[]{}, new String[]{"gateway64.exe"}, new String[]{}),
        FLEET("fleet", IdeKind.JETBRAINS, "Fleet",
                new String[]{}, new String[]{"fleet.exe"}, new String[]{}),
        ANDROIDSTUDIO("androidstudio", IdeKind.JETBRAINS, "Android Studio",
                new String[]{"Android Studio"}, new String[]{"studio64.exe"}, new String[]{"android-studio"}),
        UNKNOWN("unknown", IdeKind.VSCODE, "Unknown",
                new String[]{}, new String[]{}, new String[]{});

        private final String key;
        private final IdeKind ideKind;
        private final String displayName;
        private final String[] processKeywordsMac;
        private final String[] processKeywordsWindows;
        private final String[] processKeywordsLinux;

        IdeType(String key, IdeKind ideKind, String displayName,
                String[] processKeywordsMac, String[] processKeywordsWindows,
                String[] processKeywordsLinux) {
            this.key = key;
            this.ideKind = ideKind;
            this.displayName = displayName;
            this.processKeywordsMac = processKeywordsMac;
            this.processKeywordsWindows = processKeywordsWindows;
            this.processKeywordsLinux = processKeywordsLinux;
        }

        public String getKey() { return key; }
        public IdeKind getIdeKind() { return ideKind; }
        public String getDisplayName() { return displayName; }
        public String[] getProcessKeywordsMac() { return processKeywordsMac; }
        public String[] getProcessKeywordsWindows() { return processKeywordsWindows; }
        public String[] getProcessKeywordsLinux() { return processKeywordsLinux; }

        public static Optional<IdeType> fromKey(String key) {
            for (IdeType t : values()) {
                if (t.key.equalsIgnoreCase(key)) return Optional.of(t);
            }
            return Optional.empty();
        }
    }

    public enum IdeKind {
        VSCODE, JETBRAINS
    }

    // =========================================================================
    // Detected IDE info record — translated from DetectedIDEInfo in ide.ts
    // =========================================================================

    public record DetectedIDEInfo(
            String name,
            int port,
            List<String> workspaceFolders,
            String url,
            boolean isValid,
            String authToken,
            boolean ideRunningInWindows
    ) {}

    // =========================================================================
    // Extension installation status — translated from IDEExtensionInstallationStatus
    // =========================================================================

    public record IDEExtensionInstallationStatus(
            boolean installed,
            String error,
            String installedVersion,
            IdeType ideType
    ) {}

    // =========================================================================
    // Core detection methods
    // =========================================================================

    /**
     * Check whether an IDE is a VS Code-family IDE.
     * Translated from isVSCodeIde() in ide.ts
     */
    public boolean isVSCodeIde(IdeType ide) {
        return ide != null && ide.getIdeKind() == IdeKind.VSCODE;
    }

    /**
     * Check whether an IDE is a JetBrains-family IDE.
     * Translated from isJetBrainsIde() in ide.ts
     */
    public boolean isJetBrainsIde(IdeType ide) {
        return ide != null && ide.getIdeKind() == IdeKind.JETBRAINS;
    }

    /**
     * Detect whether the current terminal is a supported VS Code terminal.
     * Translated from isSupportedVSCodeTerminal() in ide.ts
     */
    public boolean isSupportedVSCodeTerminal() {
        String terminal = System.getenv("TERMINAL_IDE");
        if (terminal == null) terminal = System.getenv("TERM_PROGRAM");
        return IdeType.fromKey(terminal).map(this::isVSCodeIde).orElse(false);
    }

    /**
     * Detect whether the current terminal is a supported JetBrains terminal.
     * Translated from isSupportedJetBrainsTerminal() in ide.ts
     */
    public boolean isSupportedJetBrainsTerminal() {
        String terminal = System.getenv("JETBRAINS_IDE");
        return IdeType.fromKey(terminal).map(this::isJetBrainsIde).orElse(false);
    }

    /**
     * Detect whether the current terminal is a supported IDE terminal.
     * Translated from isSupportedTerminal() in ide.ts
     */
    public boolean isSupportedTerminal() {
        return isSupportedVSCodeTerminal()
                || isSupportedJetBrainsTerminal()
                || "true".equalsIgnoreCase(System.getenv("FORCE_CODE_TERMINAL"));
    }

    /**
     * Get the IDE type for the current terminal, or {@code null} if not supported.
     * Translated from getTerminalIdeType() in ide.ts
     */
    public Optional<IdeType> getTerminalIdeType() {
        if (!isSupportedTerminal()) return Optional.empty();
        String terminal = System.getenv("TERM_PROGRAM");
        return IdeType.fromKey(terminal);
    }

    /**
     * Check whether running inside any supported IDE terminal.
     */
    public boolean isRunningInIde() {
        return isSupportedTerminal();
    }

    // =========================================================================
    // Connection check
    // =========================================================================

    /**
     * Checks if the IDE connection is responding by testing if the port is open.
     * Translated from checkIdeConnection() in ide.ts
     *
     * @param host    Host to connect to
     * @param port    Port to connect to
     * @param timeout Timeout in milliseconds (defaults to 500)
     * @return {@code true} if the port is open
     */
    public CompletableFuture<Boolean> checkIdeConnection(String host, int port, int timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeout);
                return true;
            } catch (IOException e) {
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> checkIdeConnection(String host, int port) {
        return checkIdeConnection(host, port, 500);
    }

    // =========================================================================
    // Lockfile support types — translated from IdeLockfileInfo in ide.ts
    // =========================================================================

    public record LockfileContent(
            List<String> workspaceFolders,
            int port,
            Long pid,
            String ideName,
            boolean useWebSocket,
            boolean runningInWindows,
            String authToken
    ) {}

    // =========================================================================
    // IDE extension installed check
    // =========================================================================

    /**
     * Check if the Claude Code IDE extension is installed.
     * Translated from isIDEExtensionInstalled() in ide.ts
     */
    public CompletableFuture<Boolean> isIDEExtensionInstalled(IdeType ideType) {
        return CompletableFuture.supplyAsync(() -> {
            if (isVSCodeIde(ideType)) {
                String command = getVSCodeCommand(ideType).orElse(null);
                if (command == null) return false;
                try {
                    ProcessBuilder pb = new ProcessBuilder(command, "--list-extensions");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String out = new String(p.getInputStream().readAllBytes());
                    p.waitFor();
                    String extensionId = "ant".equals(System.getenv("USER_TYPE"))
                            ? "anthropic.claude-code-internal"
                            : "anthropic.claude-code";
                    return out.contains(extensionId);
                } catch (Exception e) {
                    log.debug("Could not check VS Code extension: {}", e.getMessage());
                }
            }
            return false;
        });
    }

    /**
     * Get the VS Code CLI command name for a given IDE type.
     * Translated from getVSCodeIDECommand() in ide.ts
     */
    public Optional<String> getVSCodeCommand(IdeType ideType) {
        if (ideType == null || !isVSCodeIde(ideType)) return Optional.empty();
        String ext = isWindows() ? ".cmd" : "";
        return switch (ideType) {
            case VSCODE -> Optional.of("code" + ext);
            case CURSOR -> Optional.of("cursor" + ext);
            case WINDSURF -> Optional.of("windsurf" + ext);
            default -> Optional.empty();
        };
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    /**
     * Notify the IDE that a file was updated.
     */
    public void notifyFileUpdated(String filePath) {
        log.debug("File updated notification: {}", filePath);
    }

    /**
     * Detect which IDE (if any) is running in the current environment.
     * Returns {@link IdeType#UNKNOWN} if none is detected.
     */
    public IdeType detectIde() {
        return getTerminalIdeType().orElse(IdeType.UNKNOWN);
    }

    /**
     * Get the connected IDE name if available.
     */
    public java.util.Optional<String> getConnectedIdeName() {
        return getTerminalIdeType().map(IdeType::getDisplayName);
    }
}
