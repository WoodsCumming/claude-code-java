package com.anthropic.claudecode.util;

import java.math.BigInteger;

/**
 * Tagged ID encoding utilities.
 * Translated from src/utils/taggedId.ts
 *
 * Produces IDs like "user_01PaGUP2rbg1XDh7Z9W1CEpd" from UUIDs.
 */
public class TaggedId {

    private static final String BASE_58_CHARS =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String VERSION = "01";
    private static final int ENCODED_LENGTH = 22;

    /**
     * Convert a UUID to a tagged ID.
     * Translated from toTaggedId() in taggedId.ts
     */
    public static String toTaggedId(String tag, String uuid) {
        BigInteger n = uuidToBigInt(uuid);
        return tag + "_" + VERSION + base58Encode(n);
    }

    private static String base58Encode(BigInteger n) {
        BigInteger base = BigInteger.valueOf(BASE_58_CHARS.length());
        char[] result = new char[ENCODED_LENGTH];
        java.util.Arrays.fill(result, BASE_58_CHARS.charAt(0));

        int i = ENCODED_LENGTH - 1;
        BigInteger value = n;
        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = value.divideAndRemainder(base);
            result[i] = BASE_58_CHARS.charAt(divRem[1].intValue());
            value = divRem[0];
            i--;
        }

        return new String(result);
    }

    private static BigInteger uuidToBigInt(String uuid) {
        String hex = uuid.replace("-", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException("Invalid UUID hex length: " + hex.length());
        }
        return new BigInteger(hex, 16);
    }

    private TaggedId() {}
}
