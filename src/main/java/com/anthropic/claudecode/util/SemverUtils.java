package com.anthropic.claudecode.util;

/**
 * Semantic versioning utilities.
 * Translated from src/utils/semver.ts
 */
public class SemverUtils {

    /**
     * Compare two version strings.
     * Returns -1 if a < b, 0 if a == b, 1 if a > b.
     * Translated from order() in semver.ts
     */
    public static int order(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        String[] aParts = a.split("\\.", -1);
        String[] bParts = b.split("\\.", -1);

        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            int aNum = i < aParts.length ? parseVersionPart(aParts[i]) : 0;
            int bNum = i < bParts.length ? parseVersionPart(bParts[i]) : 0;
            if (aNum < bNum) return -1;
            if (aNum > bNum) return 1;
        }
        return 0;
    }

    public static boolean gt(String a, String b) { return order(a, b) > 0; }
    public static boolean gte(String a, String b) { return order(a, b) >= 0; }
    public static boolean lt(String a, String b) { return order(a, b) < 0; }
    public static boolean lte(String a, String b) { return order(a, b) <= 0; }
    public static boolean eq(String a, String b) { return order(a, b) == 0; }

    private static int parseVersionPart(String part) {
        // Strip pre-release identifiers (e.g., "1.0.0-beta.1" -> 0 for pre-release)
        String numericPart = part.split("-")[0];
        try {
            return Integer.parseInt(numericPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private SemverUtils() {}
}
