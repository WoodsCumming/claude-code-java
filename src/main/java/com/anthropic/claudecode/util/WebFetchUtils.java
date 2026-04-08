package com.anthropic.claudecode.util;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Utility functions for the WebFetch tool: URL validation, domain blocklist checks,
 * redirect handling, and content fetching with Markdown conversion.
 * Translated from src/tools/WebFetchTool/utils.ts
 */
@Slf4j
@Component
public class WebFetchUtils {



    // PSR-mandated URL length cap
    private static final int MAX_URL_LENGTH = 2000;

    // Per-PSR resource-consumption cap: 10 MB
    private static final long MAX_HTTP_CONTENT_LENGTH = 10L * 1024 * 1024;

    // Main HTTP fetch timeout: 60 s
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(60);

    // Preflight domain check timeout: 10 s
    private static final Duration DOMAIN_CHECK_TIMEOUT = Duration.ofSeconds(10);

    // Maximum redirect hops before giving up
    private static final int MAX_REDIRECTS = 10;

    /** Maximum length of Markdown content returned to the model. */
    public static final int MAX_MARKDOWN_LENGTH = 100_000;

    // Simple TTL cache for URL content (hostname -> allowed) and URL -> CacheEntry.
    // In production, these would be backed by Caffeine or a proper LRU cache.
    private final Map<String, CacheEntry> urlCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > 512;
        }
    };
    private final Map<String, Boolean> domainCheckCache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 128;
        }
    };

    private final HttpClient httpClient;

    public WebFetchUtils() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER) // manual redirect handling per PSR
                .build();
    }

    // -------------------------------------------------------------------------
    // Cache types
    // -------------------------------------------------------------------------

    @Builder
    public record CacheEntry(
            long bytes,
            int code,
            String codeText,
            String content,
            String contentType,
            String persistedPath,  // nullable
            Long persistedSize     // nullable
    ) {}

    /** Result of fetching a URL – either content or a redirect notice. */
    public sealed interface FetchResult permits FetchResult.Content, FetchResult.Redirect {
        record Content(
                String content,
                long bytes,
                int code,
                String codeText,
                String contentType,
                String persistedPath,
                Long persistedSize
        ) implements FetchResult {}

        record Redirect(
                String originalUrl,
                String redirectUrl,
                int statusCode
        ) implements FetchResult {}
    }

    /** Result of the domain blocklist preflight check. */
    public sealed interface DomainCheckResult
            permits DomainCheckResult.Allowed, DomainCheckResult.Blocked, DomainCheckResult.CheckFailed {
        record Allowed() implements DomainCheckResult {}
        record Blocked() implements DomainCheckResult {}
        record CheckFailed(Exception error) implements DomainCheckResult {}
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns true if the URL belongs to a preapproved host.
     * Translated from isPreapprovedUrl() in utils.ts
     */
    public static boolean isPreapprovedUrl(String url) {
        try {
            URI parsed = URI.create(url);
            return WebFetchPreapproved.isPreapprovedHost(parsed.getHost(), parsed.getPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates a URL for length, parsability, credentials, and minimum hostname depth.
     * Translated from validateURL() in utils.ts
     */
    public static boolean validateUrl(String url) {
        if (url == null || url.length() > MAX_URL_LENGTH) {
            return false;
        }
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (Exception e) {
            return false;
        }
        // Block credentials
        String userInfo = parsed.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            return false;
        }
        // Require at least two hostname labels
        String host = parsed.getHost();
        if (host == null || host.split("\\.").length < 2) {
            return false;
        }
        return true;
    }

    /**
     * Checks whether the given domain is in the Anthropic blocklist.
     * Results are cached per-hostname for 5 minutes (approximated here as session cache).
     * Translated from checkDomainBlocklist() in utils.ts
     */
    public CompletableFuture<DomainCheckResult> checkDomainBlocklist(String domain) {
        if (domainCheckCache.containsKey(domain)) {
            return CompletableFuture.completedFuture(new DomainCheckResult.Allowed());
        }
        return CompletableFuture.supplyAsync(() -> {
            String endpoint = "https://api.anthropic.com/api/web/domain_info?domain="
                    + java.net.URLEncoder.encode(domain, java.nio.charset.StandardCharsets.UTF_8);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(DOMAIN_CHECK_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(response.body());
                    if (json.path("can_fetch").asBoolean(false)) {
                        domainCheckCache.put(domain, true);
                        return new DomainCheckResult.Allowed();
                    }
                    return new DomainCheckResult.Blocked();
                }
                return new DomainCheckResult.CheckFailed(
                        new RuntimeException("Domain check returned status " + response.statusCode()));
            } catch (Exception e) {
                log.error("Domain blocklist check failed for {}", domain, e);
                return new DomainCheckResult.CheckFailed(e);
            }
        });
    }

    /**
     * Returns true if the redirect from originalUrl to redirectUrl is permitted.
     * Allows adding/removing "www." prefix, and same-origin path changes.
     * Translated from isPermittedRedirect() in utils.ts
     */
    public static boolean isPermittedRedirect(String originalUrl, String redirectUrl) {
        try {
            URI orig = URI.create(originalUrl);
            URI redir = URI.create(redirectUrl);

            if (!orig.getScheme().equals(redir.getScheme())) return false;

            String origPort = orig.getPort() == -1 ? "" : String.valueOf(orig.getPort());
            String redirPort = redir.getPort() == -1 ? "" : String.valueOf(redir.getPort());
            if (!origPort.equals(redirPort)) return false;

            if (redir.getUserInfo() != null && !redir.getUserInfo().isBlank()) return false;

            // stripWww is defined as a local static helper below
            // Use a local lambda helper
            return stripWwwHost(orig.getHost()).equals(stripWwwHost(redir.getHost()));
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripWwwHost(String hostname) {
        if (hostname == null) return "";
        return hostname.replaceFirst("^www\\.", "");
    }

    /**
     * Performs an HTTP GET with manual redirect handling up to MAX_REDIRECTS hops.
     * Translated from getWithPermittedRedirects() in utils.ts
     */
    public CompletableFuture<FetchResult> getWithPermittedRedirects(String url, int depth) {
        if (depth > MAX_REDIRECTS) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("Too many redirects (exceeded " + MAX_REDIRECTS + ")"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Upgrade http → https
                String fetchUrl = url.startsWith("http:") ? "https" + url.substring(4) : url;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(fetchUrl))
                        .timeout(FETCH_TIMEOUT)
                        .header("Accept", "text/markdown, text/html, */*")
                        .header("User-Agent", "ClaudeCode/1.0")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                // Manual redirect handling (301, 302, 307, 308)
                if (status == 301 || status == 302 || status == 307 || status == 308) {
                    String location = response.headers().firstValue("location").orElse(null);
                    if (location == null) {
                        throw new RuntimeException("Redirect missing Location header");
                    }
                    // Resolve relative URLs
                    String redirectUrl = URI.create(fetchUrl).resolve(location).toString();
                    if (isPermittedRedirect(url, redirectUrl)) {
                        return getWithPermittedRedirects(redirectUrl, depth + 1).join();
                    } else {
                        return new FetchResult.Redirect(url, redirectUrl, status);
                    }
                }

                // Check for egress-proxy block
                if (status == 403) {
                    String proxyError = response.headers().firstValue("x-proxy-error").orElse("");
                    if ("blocked-by-allowlist".equals(proxyError)) {
                        String hostname = URI.create(fetchUrl).getHost();
                        throw new EgressBlockedError(hostname);
                    }
                }

                byte[] bodyBytes = response.body();
                String contentType = response.headers().firstValue("content-type").orElse("");
                String content = convertToMarkdown(bodyBytes, contentType);

                // Clamp to MAX_MARKDOWN_LENGTH
                if (content.length() > MAX_MARKDOWN_LENGTH) {
                    content = content.substring(0, MAX_MARKDOWN_LENGTH) + "\n\n[Content truncated due to length...]";
                }

                return new FetchResult.Content(
                        content,
                        bodyBytes.length,
                        status,
                        statusText(status),
                        contentType,
                        null,
                        null
                );
            } catch (EgressBlockedError e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("HTTP fetch failed for " + url, e);
            }
        });
    }

    /**
     * High-level: validate, blocklist-check, fetch, cache, and return content.
     * Translated from getURLMarkdownContent() in utils.ts
     */
    public CompletableFuture<FetchResult> getUrlMarkdownContent(String url) {
        if (!validateUrl(url)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid URL"));
        }
        // Check cache
        CacheEntry cached = urlCache.get(url);
        if (cached != null) {
            return CompletableFuture.completedFuture(new FetchResult.Content(
                    cached.content(), cached.bytes(), cached.code(), cached.codeText(),
                    cached.contentType(), cached.persistedPath(), cached.persistedSize()));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                URI parsed = URI.create(url);
                String hostname = parsed.getHost();
                DomainCheckResult checkResult = checkDomainBlocklist(hostname).join();
                return switch (checkResult) {
                    case DomainCheckResult.Allowed a -> null; // continue
                    case DomainCheckResult.Blocked b ->
                            throw new DomainBlockedError(hostname);
                    case DomainCheckResult.CheckFailed f ->
                            throw new DomainCheckFailedError(hostname);
                };
            } catch (DomainBlockedError | DomainCheckFailedError e) {
                throw e;
            } catch (Exception e) {
                log.error("Error during domain check for {}", url, e);
                return null;
            }
        }).thenCompose(ignored -> getWithPermittedRedirects(url, 0))
                .thenApply(result -> {
                    if (result instanceof FetchResult.Content content) {
                        urlCache.put(url, CacheEntry.builder()
                                .bytes(content.bytes())
                                .code(content.code())
                                .codeText(content.codeText())
                                .content(content.content())
                                .contentType(content.contentType())
                                .persistedPath(content.persistedPath())
                                .persistedSize(content.persistedSize())
                                .build());
                    }
                    return result;
                });
    }

    /** Clear all caches. Translated from clearWebFetchCache() in utils.ts */
    public void clearWebFetchCache() {
        urlCache.clear();
        domainCheckCache.clear();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Convert raw bytes to a Markdown string.
     * HTML is converted via a simple tag-stripping pass (full Turndown not available in Java stdlib).
     * For production use, consider integrating jsoup or a proper HTML-to-Markdown library.
     */
    private static String convertToMarkdown(byte[] bytes, String contentType) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (contentType != null && contentType.contains("text/html")) {
            // Strip HTML tags for a basic Markdown approximation
            text = text.replaceAll("<[^>]+>", "")
                       .replaceAll("&amp;", "&")
                       .replaceAll("&lt;", "<")
                       .replaceAll("&gt;", ">")
                       .replaceAll("&nbsp;", " ")
                       .replaceAll("&#39;", "'")
                       .replaceAll("&quot;", "\"")
                       .replaceAll("\\n{3,}", "\n\n")
                       .strip();
        }
        return text;
    }

    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default  -> "Unknown";
        };
    }

    // -------------------------------------------------------------------------
    // Domain error types
    // -------------------------------------------------------------------------

    /** Thrown when the domain blocklist marks a domain as blocked. */
    public static class DomainBlockedError extends RuntimeException {
        public DomainBlockedError(String domain) {
            super("Claude Code is unable to fetch from " + domain);
        }
    }

    /** Thrown when the domain blocklist check itself fails (network error, etc.). */
    public static class DomainCheckFailedError extends RuntimeException {
        public DomainCheckFailedError(String domain) {
            super("Unable to verify if domain " + domain + " is safe to fetch. "
                    + "This may be due to network restrictions or enterprise security policies blocking claude.ai.");
        }
    }

    /** Thrown when an egress proxy blocks the outbound request. */
    public static class EgressBlockedError extends RuntimeException {
        private final String domain;

        public EgressBlockedError(String domain) {
            super("{\"error_type\":\"EGRESS_BLOCKED\",\"domain\":\"" + domain
                    + "\",\"message\":\"Access to " + domain + " is blocked by the network egress proxy.\"}");
            this.domain = domain;
        }

        public String getDomain() {
            return domain;
        }
    }
}
