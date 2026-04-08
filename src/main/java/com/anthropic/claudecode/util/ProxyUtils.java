package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Proxy configuration utilities.
 * Translated from src/utils/proxy.ts
 *
 * Handles HTTP/HTTPS proxy URL resolution, NO_PROXY bypass logic,
 * and keep-alive management for API requests.
 */
@Slf4j
public class ProxyUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProxyUtils.class);


    /**
     * Disable fetch keep-alive after a stale-pool ECONNRESET so retries open a
     * fresh TCP connection instead of reusing the dead pooled socket. Sticky for
     * the process lifetime — once the pool is known-bad, don't trust it again.
     * Translated from keepAliveDisabled / disableKeepAlive() in proxy.ts
     */
    private static volatile boolean keepAliveDisabled = false;

    /**
     * Disable keep-alive. Process-wide and irreversible in production.
     * Translated from disableKeepAlive() in proxy.ts
     */
    public static void disableKeepAlive() {
        keepAliveDisabled = true;
    }

    /** Reset keep-alive flag — for testing only. Translated from _resetKeepAliveForTesting(). */
    public static void resetKeepAliveForTesting() {
        keepAliveDisabled = false;
    }

    public static boolean isKeepAliveDisabled() {
        return keepAliveDisabled;
    }

    // =========================================================================
    // Proxy URL resolution
    // =========================================================================

    /**
     * Get the active proxy URL if one is configured.
     * Prefers lowercase variants over uppercase:
     * {@code https_proxy > HTTPS_PROXY > http_proxy > HTTP_PROXY}
     *
     * @param env Environment variables to check (pass {@code null} to use {@link System#getenv()})
     * @return The proxy URL, or empty if none is configured
     * Translated from getProxyUrl() in proxy.ts
     */
    public static Optional<String> getProxyUrl(Map<String, String> env) {
        Map<String, String> e = (env != null) ? env : System.getenv();
        for (String key : List.of("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY")) {
            String val = e.get(key);
            if (val != null && !val.isBlank()) return Optional.of(val);
        }
        return Optional.empty();
    }

    /** Convenience overload that uses {@link System#getenv()}. */
    public static Optional<String> getProxyUrl() {
        return getProxyUrl(null);
    }

    /**
     * Get the NO_PROXY environment variable value.
     * Prefers lowercase over uppercase: {@code no_proxy > NO_PROXY}
     *
     * @param env Environment variables to check (pass {@code null} to use {@link System#getenv()})
     * @return The NO_PROXY value, or empty if not set
     * Translated from getNoProxy() in proxy.ts
     */
    public static Optional<String> getNoProxy(Map<String, String> env) {
        Map<String, String> e = (env != null) ? env : System.getenv();
        for (String key : List.of("no_proxy", "NO_PROXY")) {
            String val = e.get(key);
            if (val != null && !val.isBlank()) return Optional.of(val);
        }
        return Optional.empty();
    }

    /** Convenience overload that uses {@link System#getenv()}. */
    public static Optional<String> getNoProxy() {
        return getNoProxy(null);
    }

    // =========================================================================
    // NO_PROXY bypass logic
    // =========================================================================

    /**
     * Check if a URL should bypass the proxy based on the NO_PROXY environment variable.
     *
     * Supports:
     * <ul>
     *   <li>Wildcard {@code "*"} — bypass all</li>
     *   <li>Exact hostname match (e.g., {@code localhost})</li>
     *   <li>Domain-suffix match with leading dot (e.g., {@code .example.com})</li>
     *   <li>Port-specific match (e.g., {@code example.com:8080})</li>
     *   <li>IP address match (e.g., {@code 127.0.0.1})</li>
     * </ul>
     *
     * @param urlString The URL to check
     * @param noProxy   The NO_PROXY value; pass {@code null} to read from the environment
     * @return {@code true} if the URL should bypass the proxy
     * Translated from shouldBypassProxy() in proxy.ts
     */
    public static boolean shouldBypassProxy(String urlString, String noProxy) {
        if (noProxy == null || noProxy.isBlank()) return false;

        // Wildcard: bypass everything
        if ("*".equals(noProxy)) return true;

        try {
            URI uri = new URI(urlString);
            String hostname = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            int uriPort = uri.getPort();
            String defaultPort = "https".equals(uri.getScheme()) ? "443" : "80";
            String port = (uriPort > 0) ? String.valueOf(uriPort) : defaultPort;
            String hostWithPort = hostname + ":" + port;

            // Split by comma or whitespace, trim each entry
            String[] entries = noProxy.split("[,\\s]+");

            for (String rawPattern : entries) {
                String pattern = rawPattern.toLowerCase().trim();
                if (pattern.isEmpty()) continue;

                // Port-specific match
                if (pattern.contains(":")) {
                    if (hostWithPort.equals(pattern)) return true;
                    continue;
                }

                // Domain-suffix match (with or without leading dot)
                if (pattern.startsWith(".")) {
                    // ".example.com" matches "sub.example.com" AND "example.com" but NOT "notexample.com"
                    String suffix = pattern;                   // e.g. ".example.com"
                    String base   = pattern.substring(1);      // e.g.  "example.com"
                    if (hostname.equals(base) || hostname.endsWith(suffix)) return true;
                    continue;
                }

                // Exact hostname or IP match
                if (hostname.equals(pattern)) return true;
            }

            return false;

        } catch (URISyntaxException e) {
            // If URL parsing fails, don't bypass proxy
            return false;
        }
    }

    /**
     * Convenience overload that reads NO_PROXY from the environment.
     */
    public static boolean shouldBypassProxy(String urlString) {
        return shouldBypassProxy(urlString, getNoProxy().orElse(null));
    }

    // =========================================================================
    // WebSocket proxy helpers
    // =========================================================================

    /**
     * Get the proxy URL for a WebSocket connection, respecting NO_PROXY.
     * Returns empty if no proxy is configured or if the URL should bypass the proxy.
     * Translated from getWebSocketProxyUrl() in proxy.ts
     *
     * @param url The WebSocket URL to connect to
     */
    public static Optional<String> getWebSocketProxyUrl(String url) {
        Optional<String> proxyUrl = getProxyUrl();
        if (proxyUrl.isEmpty()) return Optional.empty();
        if (shouldBypassProxy(url)) return Optional.empty();
        return proxyUrl;
    }

    // =========================================================================
    // System proxy configuration
    // =========================================================================

    /**
     * Configure Java system properties for the detected proxy.
     * Should be called once at application startup.
     */
    public static void configureSystemProxy() {
        Optional<String> proxyUrl = getProxyUrl();
        if (proxyUrl.isEmpty()) return;

        try {
            URI uri = new URI(proxyUrl.get());
            String host = uri.getHost();
            int port = uri.getPort();

            if (host != null) {
                System.setProperty("https.proxyHost", host);
                System.setProperty("http.proxyHost", host);
                if (port > 0) {
                    System.setProperty("https.proxyPort", String.valueOf(port));
                    System.setProperty("http.proxyPort", String.valueOf(port));
                }
                // Propagate NO_PROXY to the JDK http.nonProxyHosts property
                getNoProxy().ifPresent(noProxy -> {
                    // JDK uses | separator and * prefix wildcards, not leading dots
                    String jdkNoProxy = convertNoProxyToJdkFormat(noProxy);
                    System.setProperty("http.nonProxyHosts", jdkNoProxy);
                });
                log.info("Configured proxy: {}:{}", host, port);
            }
        } catch (URISyntaxException e) {
            log.warn("Invalid proxy URL: {}", proxyUrl.get());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Convert a comma/space-separated NO_PROXY string to the JDK
     * {@code http.nonProxyHosts} pipe-separated format.
     */
    private static String convertNoProxyToJdkFormat(String noProxy) {
        String[] parts = noProxy.split("[,\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append("|");
            // Leading dot means domain suffix — JDK uses * prefix instead
            if (p.startsWith(".")) {
                sb.append("*").append(p);
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private ProxyUtils() {}
}
