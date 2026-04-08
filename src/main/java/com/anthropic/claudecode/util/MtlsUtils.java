package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * mTLS (mutual TLS) utilities.
 * Translated from src/utils/mtls.ts
 *
 * <p>Loads client certificate, key, and optional passphrase from environment
 * variables ({@code CLAUDE_CODE_CLIENT_CERT}, {@code CLAUDE_CODE_CLIENT_KEY},
 * {@code CLAUDE_CODE_CLIENT_KEY_PASSPHRASE}) and provides helpers for
 * configuring mTLS/TLS on HTTPS and WebSocket connections.
 */
@Slf4j
public class MtlsUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MtlsUtils.class);


    // ── Domain types ──────────────────────────────────────────────────────────

    /**
     * mTLS client-certificate configuration.
     * Translated from {@code MTLSConfig} in mtls.ts.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MTLSConfig {
        /** PEM-encoded client certificate (contents, not a file path). */
        private String cert;
        /** PEM-encoded private key (contents, not a file path). */
        private String key;
        /** Optional passphrase protecting the private key. */
        private String passphrase;

        public String getCert() { return cert; }
        public void setCert(String v) { cert = v; }
        public String getKey() { return key; }
        public void setKey(String v) { key = v; }
        public String getPassphrase() { return passphrase; }
        public void setPassphrase(String v) { passphrase = v; }
    }

    /**
     * TLS configuration that extends mTLS with optional CA certificate(s).
     * Translated from {@code TLSConfig} in mtls.ts.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TLSConfig {
        private String cert;
        private String key;
        private String passphrase;
        /** PEM-encoded CA certificate(s). Null means use system trust store. */
        private List<String> ca;

        public List<String> getCa() { return ca; }
        public void setCa(List<String> v) { ca = v; }
    }

    // ── Memoized singletons ───────────────────────────────────────────────────

    private static final AtomicReference<Optional<MTLSConfig>> cachedMtlsConfig =
            new AtomicReference<>(null);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Get mTLS configuration from environment variables.
     * Result is memoized after the first call.
     *
     * <p>Reads file contents pointed to by:
     * <ul>
     *   <li>{@code CLAUDE_CODE_CLIENT_CERT} — path to PEM client certificate</li>
     *   <li>{@code CLAUDE_CODE_CLIENT_KEY}  — path to PEM private key</li>
     *   <li>{@code CLAUDE_CODE_CLIENT_KEY_PASSPHRASE} — raw passphrase string</li>
     * </ul>
     *
     * Translated from {@code getMTLSConfig()} in mtls.ts.
     *
     * @return populated config if at least one option is set; empty otherwise
     */
    public static Optional<MTLSConfig> getMTLSConfig() {
        Optional<MTLSConfig> cached = cachedMtlsConfig.get();
        if (cached != null) {
            return cached;
        }

        MTLSConfig config = new MTLSConfig();

        // Client certificate
        String certPath = System.getenv("CLAUDE_CODE_CLIENT_CERT");
        if (certPath != null) {
            try {
                config.setCert(Files.readString(Paths.get(certPath)));
                log.debug("mTLS: Loaded client certificate from CLAUDE_CODE_CLIENT_CERT");
            } catch (IOException e) {
                log.error("mTLS: Failed to load client certificate: {}", e.getMessage());
            }
        }

        // Client key
        String keyPath = System.getenv("CLAUDE_CODE_CLIENT_KEY");
        if (keyPath != null) {
            try {
                config.setKey(Files.readString(Paths.get(keyPath)));
                log.debug("mTLS: Loaded client key from CLAUDE_CODE_CLIENT_KEY");
            } catch (IOException e) {
                log.error("mTLS: Failed to load client key: {}", e.getMessage());
            }
        }

        // Key passphrase
        String passphrase = System.getenv("CLAUDE_CODE_CLIENT_KEY_PASSPHRASE");
        if (passphrase != null) {
            config.setPassphrase(passphrase);
            log.debug("mTLS: Using client key passphrase");
        }

        Optional<MTLSConfig> result = (config.getCert() == null
                && config.getKey() == null
                && config.getPassphrase() == null)
                ? Optional.empty()
                : Optional.of(config);

        cachedMtlsConfig.compareAndSet(null, result);
        return cachedMtlsConfig.get();
    }

    /**
     * Build a {@link TLSConfig} that merges mTLS client credentials with the
     * provided CA certificates.  Returns empty if neither is configured.
     *
     * Translated from {@code getTLSFetchOptions()} / {@code getWebSocketTLSOptions()} in mtls.ts.
     *
     * @param caCerts optional list of PEM-encoded CA certificates
     * @return populated TLSConfig, or empty if nothing is configured
     */
    public static Optional<TLSConfig> buildTLSConfig(List<String> caCerts) {
        Optional<MTLSConfig> mtls = getMTLSConfig();
        if (mtls.isEmpty() && (caCerts == null || caCerts.isEmpty())) {
            return Optional.empty();
        }

        TLSConfig tlsConfig = new TLSConfig();
        mtls.ifPresent(m -> {
            tlsConfig.setCert(m.getCert());
            tlsConfig.setKey(m.getKey());
            tlsConfig.setPassphrase(m.getPassphrase());
        });
        if (caCerts != null && !caCerts.isEmpty()) {
            tlsConfig.setCa(caCerts);
        }
        return Optional.of(tlsConfig);
    }

    /**
     * Clear the mTLS configuration cache.
     * Translated from {@code clearMTLSCache()} in mtls.ts.
     */
    public static void clearMTLSCache() {
        cachedMtlsConfig.set(null);
        log.debug("Cleared mTLS configuration cache");
    }

    /**
     * Configure global Node.js-style mTLS hints (logs NODE_EXTRA_CA_CERTS presence).
     * Translated from {@code configureGlobalMTLS()} in mtls.ts.
     */
    public static void configureGlobalMTLS() {
        if (getMTLSConfig().isEmpty()) {
            return;
        }
        String extraCaCerts = System.getenv("NODE_EXTRA_CA_CERTS");
        if (extraCaCerts != null) {
            log.debug("NODE_EXTRA_CA_CERTS detected - additional CA certs will be used for TLS");
        }
    }

    private MtlsUtils() {}
}
