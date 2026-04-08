package com.anthropic.claudecode.util;

import java.util.UUID;

/**
 * Cryptographic utility functions.
 * Translated from src/utils/crypto.ts
 *
 * In TypeScript this is a thin re-export of Node's built-in crypto module.
 * Here we wrap Java's standard UUID generation.
 */
public class CryptoUtils {

    /**
     * Generate a random UUID (v4).
     * Translated from randomUUID export in crypto.ts
     */
    public static String randomUUID() {
        return UUID.randomUUID().toString();
    }

    private CryptoUtils() {}
}
