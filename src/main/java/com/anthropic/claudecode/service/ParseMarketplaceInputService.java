package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Parse marketplace input service.
 * Translated from src/utils/plugins/parseMarketplaceInput.ts
 *
 * Parses marketplace input strings into source configurations.
 */
@Slf4j
@Service
public class ParseMarketplaceInputService {



    private static final Pattern GIT_SSH_PATTERN = Pattern.compile(
        "^[\\w.+-]+@[\\w.-]+:[\\w./-]+(?:\\.git)?$"
    );
    private static final Pattern HTTP_PATTERN = Pattern.compile(
        "^https?://.+"
    );
    private static final Pattern GITHUB_SHORTHAND = Pattern.compile(
        "^[\\w.-]+/[\\w.-]+$"
    );

    /**
     * Parse a marketplace input string.
     * Translated from parseMarketplaceInput() in parseMarketplaceInput.ts
     */
    public Optional<MarketplaceSource> parseMarketplaceInput(String input) {
        if (input == null || input.isBlank()) return Optional.empty();

        // Git SSH URL
        if (GIT_SSH_PATTERN.matcher(input).matches()) {
            return Optional.of(new MarketplaceSource("git", input, null, null, null));
        }

        // HTTP/HTTPS URL
        if (HTTP_PATTERN.matcher(input).matches()) {
            return Optional.of(new MarketplaceSource("url", null, input, null, null));
        }

        // GitHub shorthand (owner/repo)
        if (GITHUB_SHORTHAND.matcher(input).matches()) {
            return Optional.of(new MarketplaceSource("github", null, null, input, null));
        }

        // Local file path
        if (input.startsWith("/") || input.startsWith("~") || input.startsWith(".")) {
            return Optional.of(new MarketplaceSource("local", null, null, null, input));
        }

        return Optional.empty();
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MarketplaceSource {
        private String source; // "git" | "github" | "url" | "local"
        private String gitUrl;
        private String url;
        private String repo;
        private String path;


        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
        public String getGitUrl() { return gitUrl; }
        public void setGitUrl(String v) { gitUrl = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getRepo() { return repo; }
        public void setRepo(String v) { repo = v; }
        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
    }
}
