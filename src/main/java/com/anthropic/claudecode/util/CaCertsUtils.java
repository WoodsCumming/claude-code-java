package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * CA certificate utilities.
 * Translated from src/utils/caCerts.ts
 *
 * Loads CA certificates for TLS connections.
 */
@Slf4j
public class CaCertsUtils {



    private static volatile List<String> cachedCerts;

    /**
     * Get CA certificates.
     * Translated from getCACertificates() in caCerts.ts
     */
    public static Optional<List<String>> getCACertificates() {
        if (cachedCerts != null) {
            return Optional.of(cachedCerts);
        }

        String extraCertsPath = System.getenv("NODE_EXTRA_CA_CERTS");

        if (extraCertsPath == null) {
            return Optional.empty(); // Use runtime defaults
        }

        List<String> certs = new ArrayList<>();

        // Load extra CA certs file
        try {
            String content = Files.readString(Paths.get(extraCertsPath));
            certs.add(content);
        } catch (Exception e) {
            log.warn("Could not read extra CA certs from {}: {}", extraCertsPath, e.getMessage());
        }

        if (certs.isEmpty()) {
            return Optional.empty();
        }

        cachedCerts = certs;
        return Optional.of(certs);
    }

    /**
     * Clear the CA certs cache.
     * Translated from clearCACertsCache() in caCerts.ts
     */
    public static void clearCache() {
        cachedCerts = null;
    }

    private CaCertsUtils() {}
}
