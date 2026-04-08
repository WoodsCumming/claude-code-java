package com.anthropic.claudecode.util;

import java.security.*;
import java.util.Base64;

/**
 * OAuth PKCE cryptography utilities.
 * Translated from src/services/oauth/crypto.ts
 */
public class OAuthCrypto {

    /**
     * Generate a code verifier.
     * Translated from generateCodeVerifier() in crypto.ts
     */
    public static String generateCodeVerifier() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return base64URLEncode(bytes);
    }

    /**
     * Generate a code challenge from a verifier.
     * Translated from generateCodeChallenge() in crypto.ts
     */
    public static String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes("US-ASCII"));
        return base64URLEncode(hash);
    }

    /**
     * Generate a state parameter.
     * Translated from generateState() in crypto.ts
     */
    public static String generateState() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return base64URLEncode(bytes);
    }

    private static String base64URLEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private OAuthCrypto() {}
}
