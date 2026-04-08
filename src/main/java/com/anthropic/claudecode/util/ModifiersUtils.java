package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Modifier key utilities for macOS native key-state detection.
 * Translated from src/utils/modifiers.ts
 *
 * On macOS these call through to a native module (modifiers-napi).
 * On other platforms all modifier checks return false.
 */
@Slf4j
public class ModifiersUtils {



    // =========================================================================
    // Modifier key enum (mirrors TypeScript union type)
    // =========================================================================

    /**
     * Supported modifier keys.
     * Translated from ModifierKey union type in modifiers.ts
     */
    public enum ModifierKey {
        SHIFT("shift"),
        COMMAND("command"),
        CONTROL("control"),
        OPTION("option");

        private final String value;

        ModifierKey(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        /**
         * Parse from a string value (case-insensitive).
         */
        public static ModifierKey fromString(String s) {
            for (ModifierKey k : values()) {
                if (k.value.equalsIgnoreCase(s)) return k;
            }
            throw new IllegalArgumentException("Unknown modifier key: " + s);
        }
    }

    // =========================================================================
    // State
    // =========================================================================

    private static volatile boolean prewarmed = false;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Pre-warm the native module by loading it in advance.
     * On non-macOS platforms this is a no-op.
     * Translated from prewarmModifiers() in modifiers.ts
     */
    public static void prewarmModifiers() {
        if (prewarmed) return;
        if (!isMacOS()) return;

        prewarmed = true;
        // In a JVM context the equivalent of the Node.js native module would be
        // loaded via JNI/JNA.  The prewarm phase just ensures the library is
        // resolved so the first real call is not delayed.
        try {
            // Placeholder: in production this would invoke the JNI prewarm routine.
            log.debug("ModifiersUtils: prewarm complete");
        } catch (Exception e) {
            // Ignore errors during prewarm — same behaviour as the TS version
            log.debug("ModifiersUtils: prewarm failed (ignored): {}", e.getMessage());
        }
    }

    /**
     * Check if a specific modifier key is currently pressed (synchronous).
     * On non-macOS platforms always returns false.
     * Translated from isModifierPressed() in modifiers.ts
     */
    public static boolean isModifierPressed(ModifierKey modifier) {
        if (!isMacOS()) return false;
        try {
            // In production this would delegate to a JNI call into the native module.
            // Returning false is a safe default when the native binding is not available.
            return nativeIsModifierPressed(modifier.getValue());
        } catch (Exception e) {
            log.debug("ModifiersUtils: isModifierPressed failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Convenience overload that accepts the string name of a modifier key.
     */
    public static boolean isModifierPressed(String modifierName) {
        try {
            return isModifierPressed(ModifierKey.fromString(modifierName));
        } catch (IllegalArgumentException e) {
            log.warn("ModifiersUtils: unknown modifier key '{}', returning false", modifierName);
            return false;
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static boolean isMacOS() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }

    /**
     * Stub for the native modifier-key query.
     * In production this would be replaced by a JNI binding to the macOS
     * CGEventSource APIs (analogous to the modifiers-napi Node.js addon).
     */
    private static boolean nativeIsModifierPressed(String modifier) {
        // Native binding not available in this translation — return safe default.
        return false;
    }

    private ModifiersUtils() {}
}
