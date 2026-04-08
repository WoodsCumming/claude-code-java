package com.anthropic.claudecode.util;

/**
 * Bundled/native mode detection.
 * Translated from src/utils/bundledMode.ts
 */
public class BundledMode {

    /**
     * Check if running in bundled/native mode.
     * Translated from isInBundledMode() in bundledMode.ts
     *
     * In Java, this would be true when running as a native image (GraalVM).
     */
    public static boolean isInBundledMode() {
        // Check for GraalVM native image
        return System.getProperty("org.graalvm.nativeimage.kind") != null;
    }

    private BundledMode() {}
}
