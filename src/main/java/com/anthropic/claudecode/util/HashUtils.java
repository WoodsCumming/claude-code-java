package com.anthropic.claudecode.util;

import java.security.MessageDigest;

/**
 * Hash utilities.
 * Translated from src/utils/hash.ts
 */
public class HashUtils {

    /**
     * DJB2 string hash.
     * Translated from djb2Hash() in hash.ts
     *
     * Fast non-cryptographic hash returning a signed 32-bit int.
     */
    public static int djb2Hash(String str) {
        int hash = 0;
        for (int i = 0; i < str.length(); i++) {
            hash = ((hash << 5) - hash + str.charAt(i));
        }
        return hash;
    }

    /**
     * Hash content for change detection.
     * Translated from hashContent() in hash.ts
     */
    public static String hashContent(String content) {
        if (content == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * Hash two strings.
     * Translated from hashPair() in hash.ts
     */
    public static String hashPair(String a, String b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(a.getBytes("UTF-8"));
            md.update(new byte[]{0}); // separator
            md.update(b.getBytes("UTF-8"));
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte byt : hash) sb.append(String.format("%02x", byt));
            return sb.toString();
        } catch (Exception e) {
            return hashContent(a + "\0" + b);
        }
    }

    private HashUtils() {}
}
