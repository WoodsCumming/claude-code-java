package com.anthropic.claudecode.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Deep link URI parser.
 * Translated from src/utils/deepLink/parseDeepLink.ts
 *
 * Parses `claude-cli://open` URIs with optional parameters:
 *   q    — pre-fill the prompt input
 *   cwd  — working directory (absolute path)
 *   repo — owner/name slug
 */
@Slf4j
public class DeepLinkParser {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeepLinkParser.class);


    public static final String DEEP_LINK_PROTOCOL = "claude-cli";
    private static final int MAX_QUERY_LENGTH = 5000;
    private static final Pattern REPO_SLUG_PATTERN = Pattern.compile("^[\\w.-]+/[\\w.-]+$");

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DeepLinkAction {
        private String query;
        private String cwd;
        private String repo;

        public String getQuery() { return query; }
        public void setQuery(String v) { query = v; }
        public String getCwd() { return cwd; }
        public void setCwd(String v) { cwd = v; }
        public String getRepo() { return repo; }
        public void setRepo(String v) { repo = v; }
    }

    /**
     * Parse a deep link URI.
     * Translated from parseDeepLink() in parseDeepLink.ts
     *
     * @return parsed action, or null if invalid
     */
    public static DeepLinkAction parseDeepLink(String uri) {
        if (uri == null || !uri.startsWith(DEEP_LINK_PROTOCOL + "://")) {
            return null;
        }

        try {
            URI parsed = new URI(uri);
            if (!"open".equals(parsed.getHost())) {
                return null;
            }

            String queryString = parsed.getRawQuery();
            if (queryString == null || queryString.isBlank()) {
                return new DeepLinkAction();
            }

            Map<String, String> params = parseQueryString(queryString);
            DeepLinkAction action = new DeepLinkAction();

            // Parse query (q)
            String q = params.get("q");
            if (q != null) {
                if (containsControlChars(q)) {
                    log.warn("[deep-link] Query contains control characters, rejecting");
                    return null;
                }
                if (q.length() > MAX_QUERY_LENGTH) {
                    log.warn("[deep-link] Query too long: {} chars", q.length());
                    return null;
                }
                action.setQuery(q);
            }

            // Parse cwd
            String cwd = params.get("cwd");
            if (cwd != null) {
                if (containsControlChars(cwd)) {
                    log.warn("[deep-link] CWD contains control characters, rejecting");
                    return null;
                }
                action.setCwd(cwd);
            }

            // Parse repo
            String repo = params.get("repo");
            if (repo != null) {
                if (!REPO_SLUG_PATTERN.matcher(repo).matches()) {
                    log.warn("[deep-link] Invalid repo slug: {}", repo);
                    return null;
                }
                action.setRepo(repo);
            }

            return action;

        } catch (Exception e) {
            log.debug("Failed to parse deep link: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if a string contains ASCII control characters.
     */
    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            int code = s.charAt(i);
            if (code <= 0x1F || code == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String param : queryString.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(param.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private DeepLinkParser() {}
}
