package com.anthropic.claudecode.util;

/**
 * MDM settings constants.
 * Translated from src/utils/settings/mdm/constants.ts
 *
 * Constants for MDM (Mobile Device Management) settings.
 */
public class MdmSettingsConstants {

    /** macOS preference domain for Claude Code MDM profiles. */
    public static final String MACOS_PREFERENCE_DOMAIN = "com.anthropic.claudecode";

    /** Windows registry key paths for Claude Code MDM policies. */
    public static final String WINDOWS_REG_KEY_MACHINE = "SOFTWARE\\Policies\\Anthropic\\ClaudeCode";
    public static final String WINDOWS_REG_KEY_USER = "SOFTWARE\\Policies\\Anthropic\\ClaudeCode\\User";

    /** Linux/macOS system-level managed settings path. */
    public static final String LINUX_MANAGED_SETTINGS_PATH = "/etc/claude-code/settings.json";

    /** macOS managed settings plist path. */
    public static final String MACOS_PLIST_PATH = "/Library/Managed Preferences/com.anthropic.claudecode.plist";

    private MdmSettingsConstants() {}
}
