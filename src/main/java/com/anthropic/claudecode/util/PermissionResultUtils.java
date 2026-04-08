package com.anthropic.claudecode.util;

import com.anthropic.claudecode.model.PermissionResult;

/**
 * Permission result utilities.
 * Translated from src/utils/permissions/PermissionResult.ts
 */
public class PermissionResultUtils {

    /**
     * Get a prose description for a permission behavior.
     * Translated from getRuleBehaviorDescription() in PermissionResult.ts
     */
    public static String getRuleBehaviorDescription(String behavior) {
        if (behavior == null) return "asked for confirmation for";
        return switch (behavior) {
            case "allow" -> "allowed";
            case "deny" -> "denied";
            default -> "asked for confirmation for";
        };
    }

    /**
     * Check if a permission result allows execution.
     */
    public static boolean isAllowed(PermissionResult result) {
        return result != null && "allow".equals(result.getBehavior());
    }

    /**
     * Check if a permission result denies execution.
     */
    public static boolean isDenied(PermissionResult result) {
        return result != null && "deny".equals(result.getBehavior());
    }

    /**
     * Check if a permission result asks for confirmation.
     */
    public static boolean requiresConfirmation(PermissionResult result) {
        return result != null && "ask".equals(result.getBehavior());
    }

    private PermissionResultUtils() {}
}
