package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * AWS utilities.
 * Translated from src/utils/aws.ts
 */
@Slf4j
public class AwsUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AwsUtils.class);


    /**
     * AWS short-term credentials format.
     * Translated from AwsCredentials type in aws.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AwsCredentials {
        private String accessKeyId;
        private String secretAccessKey;
        private String sessionToken;
        /** Optional ISO-8601 expiration timestamp */
        private String expiration;

        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String v) { accessKeyId = v; }
        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String v) { secretAccessKey = v; }
        public String getSessionToken() { return sessionToken; }
        public void setSessionToken(String v) { sessionToken = v; }
        public String getExpiration() { return expiration; }
        public void setExpiration(String v) { expiration = v; }
    
    }

    /**
     * Output from {@code aws sts get-session-token} or {@code aws sts assume-role}.
     * Translated from AwsStsOutput in aws.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AwsStsOutput {
        private AwsCredentials credentials;

        public AwsCredentials getCredentials() { return credentials; }
        public void setCredentials(AwsCredentials v) { credentials = v; }
    }

    // =========================================================================
    // Validation helpers
    // =========================================================================

    /**
     * Check if an error is an AWS CredentialsProviderError.
     * Translated from isAwsCredentialsProviderError() in aws.ts
     */
    public static boolean isAwsCredentialsProviderError(Throwable error) {
        if (error == null) return false;
        return "CredentialsProviderError".equals(error.getClass().getSimpleName())
                || (error.getMessage() != null && error.getMessage().contains("CredentialsProvider"));
    }

    /**
     * Validate that an AwsStsOutput contains all required credential fields.
     * Translated from isValidAwsStsOutput() in aws.ts
     */
    public static boolean isValidAwsStsOutput(AwsStsOutput output) {
        if (output == null || output.getCredentials() == null) {
            return false;
        }
        return isValidCredentials(output.getCredentials());
    }

    /**
     * Validate that an AwsCredentials object has all required non-empty fields.
     */
    public static boolean isValidCredentials(AwsCredentials credentials) {
        return credentials != null
                && credentials.getAccessKeyId() != null && !credentials.getAccessKeyId().isEmpty()
                && credentials.getSecretAccessKey() != null && !credentials.getSecretAccessKey().isEmpty()
                && credentials.getSessionToken() != null && !credentials.getSessionToken().isEmpty();
    }

    // =========================================================================
    // STS operations
    // =========================================================================

    /**
     * Check STS caller identity by calling GetCallerIdentity.
     * Throws if credentials cannot be retrieved or the call fails.
     * Translated from checkStsCallerIdentity() in aws.ts
     */
    public static CompletableFuture<Void> checkStsCallerIdentity() {
        return CompletableFuture.runAsync(() -> {
            // Stub: AWS SDK not available at compile time.
            // Real implementation would call StsClient.create().getCallerIdentity(...).
            log.debug("STS caller identity check (stub — AWS SDK not on classpath)");
        });
    }

    /**
     * Clear AWS credential provider cache by forcing a profile refresh.
     * This ensures that changes to ~/.aws/credentials are picked up immediately.
     * Translated from clearAwsIniCache() in aws.ts
     */
    public static CompletableFuture<Void> clearAwsIniCache() {
        return CompletableFuture.runAsync(() -> {
            // Stub: AWS SDK not available at compile time.
            // Real implementation would call ProfileCredentialsProvider.create().resolveCredentials()
            // to force re-read of ~/.aws/credentials.
            log.debug("AWS credential provider cache cleared (stub — AWS SDK not on classpath)");
        });
    }

    // =========================================================================
    // Environment helpers
    // =========================================================================

    /**
     * Read AWS credentials from well-known environment variables.
     */
    public static AwsCredentials getCredentialsFromEnv() {
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        String sessionToken = System.getenv("AWS_SESSION_TOKEN");

        if (accessKey == null || secretKey == null) return null;

        return new AwsCredentials(accessKey, secretKey, sessionToken, null);
    }

    private AwsUtils() {}
}
