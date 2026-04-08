package com.anthropic.claudecode.util;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Temporary file path generation utilities.
 * Translated from src/utils/tempfile.ts
 */
public final class TempFileUtils {

    /**
     * Generate a temporary file path.
     *
     * When {@code contentHash} is provided the identifier is derived from a
     * SHA-256 hash of that string (first 16 hex chars). This produces a path
     * that is stable across process boundaries — any process with the same
     * content will get the same path. Use this when the path ends up in content
     * sent to the Anthropic API (e.g. sandbox deny lists in tool descriptions),
     * because a random UUID would change on every subprocess spawn and
     * invalidate the prompt-cache prefix.
     *
     * Translated from generateTempFilePath() in tempfile.ts
     *
     * @param prefix      optional prefix for the temp file name (default: "claude-prompt")
     * @param extension   optional file extension (default: ".md")
     * @param contentHash when non-null, derive a stable 16-char hex ID from this string
     * @return absolute temp file path string
     */
    public static String generateTempFilePath(String prefix,
                                               String extension,
                                               String contentHash) {
        String resolvedPrefix    = (prefix    != null) ? prefix    : "claude-prompt";
        String resolvedExtension = (extension != null) ? extension : ".md";

        String id;
        if (contentHash != null) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = digest.digest(
                    contentHash.getBytes(StandardCharsets.UTF_8));
                // Use Java 17+ HexFormat for clean hex encoding
                id = HexFormat.of().formatHex(hashBytes).substring(0, 16);
            } catch (Exception e) {
                // Should not happen for SHA-256
                id = UUID.randomUUID().toString();
            }
        } else {
            id = UUID.randomUUID().toString();
        }

        return Path.of(System.getProperty("java.io.tmpdir"),
            resolvedPrefix + "-" + id + resolvedExtension).toString();
    }

    /** Overload: random UUID identifier, default prefix and extension. */
    public static String generateTempFilePath() {
        return generateTempFilePath(null, null, null);
    }

    /** Overload: random UUID identifier with explicit prefix and extension. */
    public static String generateTempFilePath(String prefix, String extension) {
        return generateTempFilePath(prefix, extension, null);
    }

    private TempFileUtils() {}
}
