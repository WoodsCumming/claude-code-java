package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.Optional;

/**
 * Terminal setup service for configuring terminal key bindings.
 * Translated from src/commands/terminalSetup/index.ts and
 * src/utils/appleTerminalBackup.ts
 *
 * <ul>
 *   <li>For Apple Terminal: enables Option+Enter for newlines and visual bell.</li>
 *   <li>For all other terminals: installs Shift+Enter key binding for newlines.</li>
 *   <li>Hidden for terminals that natively support the CSI u / Kitty keyboard protocol.</li>
 * </ul>
 */
@Slf4j
@Service
public class TerminalSetupService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TerminalSetupService.class);


    // -------------------------------------------------------------------------
    // Command metadata  (mirrors the Command object in src/commands/terminalSetup/index.ts)
    // -------------------------------------------------------------------------

    public static final String COMMAND_NAME = "terminal-setup";
    public static final String COMMAND_TYPE = "local-jsx";

    /**
     * Terminals that natively support CSI u / Kitty keyboard protocol.
     * Translated from NATIVE_CSIU_TERMINALS in terminalSetup/index.ts
     */
    public static final Map<String, String> NATIVE_CSIU_TERMINALS = Map.of(
            "ghostty",     "Ghostty",
            "kitty",       "Kitty",
            "iTerm.app",   "iTerm2",
            "WezTerm",     "WezTerm"
    );

    /**
     * Description shown in the command palette; depends on the active terminal.
     * Translated from the description property in terminalSetup/index.ts
     */
    public String getDescription(String terminal) {
        if ("Apple_Terminal".equals(terminal)) {
            return "Enable Option+Enter key binding for newlines and visual bell";
        }
        return "Install Shift+Enter key binding for newlines";
    }

    /**
     * Whether the command should be hidden from the command palette.
     * Hidden for terminals that natively support the CSI u / Kitty protocol.
     * Translated from the isHidden property in terminalSetup/index.ts
     */
    public boolean isHidden(String terminal) {
        return terminal != null && NATIVE_CSIU_TERMINALS.containsKey(terminal);
    }

    // -------------------------------------------------------------------------
    // Terminal preferences backup  (src/utils/appleTerminalBackup.ts)
    // -------------------------------------------------------------------------


    /**
     * Get the Terminal plist path.
     * Translated from getTerminalPlistPath() in appleTerminalBackup.ts
     */
    public String getTerminalPlistPath() {
        return System.getProperty("user.home")
            + "/Library/Preferences/com.apple.Terminal.plist";
    }

    /**
     * Backup terminal preferences.
     * Translated from backupTerminalPreferences() in appleTerminalBackup.ts
     */
    public Optional<String> backupTerminalPreferences() {
        String plistPath = getTerminalPlistPath();
        String backupPath = plistPath + ".bak";

        try {
            File plist = new File(plistPath);
            if (!plist.exists()) return Optional.empty();

            Files.copy(plist.toPath(), Paths.get(backupPath),
                StandardCopyOption.REPLACE_EXISTING);

            log.info("Backed up Terminal preferences to: {}", backupPath);
            return Optional.of(backupPath);
        } catch (Exception e) {
            log.warn("Could not backup Terminal preferences: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Restore terminal preferences from backup.
     */
    public boolean restoreTerminalPreferences(String backupPath) {
        try {
            String plistPath = getTerminalPlistPath();
            Files.copy(Paths.get(backupPath), Paths.get(plistPath),
                StandardCopyOption.REPLACE_EXISTING);
            log.info("Restored Terminal preferences from: {}", backupPath);
            return true;
        } catch (Exception e) {
            log.warn("Could not restore Terminal preferences: {}", e.getMessage());
            return false;
        }
    }
}
