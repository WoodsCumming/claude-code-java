package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.PermissionMode;

/**
 * Permission mode utility functions.
 * Translated from src/utils/permissions/PermissionMode.ts
 */
public class PermissionModeUtils {

    /**
     * Get the display title for a permission mode.
     * Translated from permissionModeTitle() in PermissionMode.ts
     */
    public static String permissionModeTitle(PermissionMode mode) {
        if (mode == null) return "Default";
        return switch (mode) {
            case DEFAULT -> "Default";
            case PLAN -> "Plan Mode";
            case ACCEPT_EDITS -> "Accept edits";
            case BYPASS_PERMISSIONS -> "Bypass Permissions";
            case DONT_ASK -> "Don't Ask";
            case AUTO -> "Auto mode";
            case BUBBLE -> "Bubble";
        };
    }

    /**
     * Get the short title for a permission mode.
     * Translated from permissionModeShortTitle() in PermissionMode.ts
     */
    public static String permissionModeShortTitle(PermissionMode mode) {
        if (mode == null) return "Default";
        return switch (mode) {
            case DEFAULT -> "Default";
            case PLAN -> "Plan";
            case ACCEPT_EDITS -> "Accept";
            case BYPASS_PERMISSIONS -> "Bypass";
            case DONT_ASK -> "DontAsk";
            case AUTO -> "Auto";
            case BUBBLE -> "Bubble";
        };
    }

    /**
     * Get the symbol for a permission mode.
     * Translated from permissionModeSymbol() in PermissionMode.ts
     */
    public static String permissionModeSymbol(PermissionMode mode) {
        if (mode == null) return "";
        return switch (mode) {
            case DEFAULT -> "";
            case PLAN -> "⏸";
            case ACCEPT_EDITS, BYPASS_PERMISSIONS, DONT_ASK, AUTO -> "⏵⏵";
            case BUBBLE -> "";
        };
    }

    /**
     * Parse a permission mode from a string.
     * Translated from permissionModeFromString() in PermissionMode.ts
     */
    public static PermissionMode permissionModeFromString(String str) {
        if (str == null) return PermissionMode.DEFAULT;
        try {
            return PermissionMode.fromValue(str);
        } catch (IllegalArgumentException e) {
            return PermissionMode.DEFAULT;
        }
    }

    /**
     * Check if a mode is the default mode.
     * Translated from isDefaultMode() in PermissionMode.ts
     */
    public static boolean isDefaultMode(PermissionMode mode) {
        return mode == null || mode == PermissionMode.DEFAULT;
    }

    private PermissionModeUtils() {}
}
