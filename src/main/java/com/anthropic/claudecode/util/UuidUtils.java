package com.anthropic.claudecode.util;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * UUID utilities.
 * Translated from src/utils/uuid.ts
 */
public class UuidUtils {

    private static final Pattern UUID_PATTERN =
        Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Validate a UUID string.
     * Translated from validateUuid() in uuid.ts
     */
    public static String validateUuid(Object maybeUuid) {
        if (!(maybeUuid instanceof String s)) return null;
        return UUID_PATTERN.matcher(s).matches() ? s : null;
    }

    /**
     * Create an agent ID.
     * Translated from createAgentId() in uuid.ts
     *
     * Format: a{label-}{16 hex chars}
     */
    public static String createAgentId(String label) {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("a");
        if (label != null && !label.isEmpty()) {
            sb.append(label).append("-");
        }
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String createAgentId() {
        return createAgentId(null);
    }

    /**
     * Generate a random UUID.
     */
    public static String randomUuid() {
        return java.util.UUID.randomUUID().toString();
    }

    private UuidUtils() {}
}
