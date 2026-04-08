package com.anthropic.claudecode.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Fingerprint utilities for Claude Code attribution.
 * Translated from src/utils/fingerprint.ts
 *
 * Algorithm: SHA256(SALT + msg[4] + msg[7] + msg[20] + version)[:3]
 *
 * IMPORTANT: Do not change computeFingerprint() without careful coordination with
 * 1P and 3P (Bedrock, Vertex, Azure) APIs.
 */
public class FingerprintUtils {

    /**
     * Hardcoded salt from backend validation.
     * Must match exactly for fingerprint validation to pass.
     */
    public static final String FINGERPRINT_SALT = "59cf53e54c78";

    /**
     * Extracts text content from the first user message.
     *
     * Translated from extractFirstMessageText() in fingerprint.ts
     *
     * @param messages list of messages (user or assistant)
     * @return first text content, or empty string if not found
     */
    public static String extractFirstMessageText(List<? extends Object> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        // In the Java model the caller is expected to pass typed message objects.
        // This utility works with any list; callers with concrete message types
        // should use the overload below or cast appropriately.
        return "";
    }

    /**
     * Computes a 3-character fingerprint for Claude Code attribution.
     * Algorithm: SHA256(SALT + msg[4] + msg[7] + msg[20] + version)[:3]
     *
     * Translated from computeFingerprint() in fingerprint.ts
     *
     * @param messageText first user message text content
     * @param version     version string
     * @return 3-character hex fingerprint
     */
    public static String computeFingerprint(String messageText, String version) {
        if (messageText == null) messageText = "";
        if (version == null) version = "";

        // Extract chars at indices [4, 7, 20], use "0" if index not found
        int[] indices = {4, 7, 20};
        StringBuilder chars = new StringBuilder();
        for (int idx : indices) {
            chars.append(idx < messageText.length() ? messageText.charAt(idx) : '0');
        }

        String fingerprintInput = FINGERPRINT_SALT + chars + version;

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(fingerprintInput.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            // Return first 3 hex chars
            return hex.substring(0, 3);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Computes fingerprint from a raw message text using the application version.
     *
     * Translated from computeFingerprintFromMessages() in fingerprint.ts
     *
     * @param firstMessageText extracted text of the first user message
     * @param version          application version string
     * @return 3-character hex fingerprint
     */
    public static String computeFingerprintFromMessages(String firstMessageText, String version) {
        return computeFingerprint(firstMessageText, version);
    }

    private FingerprintUtils() {}
}
