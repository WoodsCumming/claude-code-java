package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.PrivacyUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Release notes service for fetching and caching changelog information.
 * Translated from src/utils/releaseNotes.ts
 *
 * Flow:
 *   1. User updates to a new version.
 *   2. We fetch the changelog in the background and cache it on disk.
 *   3. Next startup the cached changelog is available immediately for display.
 */
@Slf4j
@Service
public class ReleaseNotesService {



    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Public URL of the changelog — shown to users. */
    public static final String CHANGELOG_URL =
            "https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md";

    private static final String RAW_CHANGELOG_URL =
            "https://raw.githubusercontent.com/anthropics/claude-code/refs/heads/main/CHANGELOG.md";

    private static final int MAX_RELEASE_NOTES_SHOWN = 5;

    // -------------------------------------------------------------------------
    // In-memory cache
    // -------------------------------------------------------------------------

    /** Populated by {@link #getStoredChangelog()} / {@link #fetchAndStoreChangelog()}. */
    private final AtomicReference<String> changelogMemoryCache = new AtomicReference<>(null);

    @Value("${app.version:unknown}")
    private String appVersion;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    @PostConstruct
    public void initialize() {
        // Load the on-disk cache into memory asynchronously at startup
        CompletableFuture.runAsync(() -> {
            try {
                getStoredChangelog();
            } catch (Exception e) {
                log.debug("Could not pre-load changelog cache: {}", e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the path to the cached changelog file.
     * Translated from getChangelogCachePath() in releaseNotes.ts
     */
    public Path getChangelogCachePath() {
        return Paths.get(EnvUtils.getClaudeConfigHomeDir(), "cache", "changelog.md");
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    /**
     * Reset the in-memory cache. For tests only.
     * Translated from _resetChangelogCacheForTesting() in releaseNotes.ts
     */
    public void resetChangelogCacheForTesting() {
        changelogMemoryCache.set(null);
    }

    /**
     * Fetch the changelog from GitHub and persist it to the cache file.
     * No-op in non-interactive / essential-traffic-only mode.
     * Translated from fetchAndStoreChangelog() in releaseNotes.ts
     */
    public CompletableFuture<Void> fetchAndStoreChangelog() {
        if (PrivacyUtils.isEssentialTrafficOnly()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(RAW_CHANGELOG_URL))
                        .GET()
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) return;

                String content = response.body();
                String cached = changelogMemoryCache.get();
                if (content.equals(cached)) return; // unchanged — skip write

                Path cachePath = getChangelogCachePath();
                Files.createDirectories(cachePath.getParent());
                Files.writeString(cachePath, content,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                changelogMemoryCache.set(content);
                log.debug("Changelog cached to {}", cachePath);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Could not fetch changelog: {}", e.getMessage());
            }
        });
    }

    /**
     * Read the cached changelog from disk (or in-memory cache).
     * Populates the in-memory cache for subsequent sync reads.
     * Translated from getStoredChangelog() in releaseNotes.ts
     */
    public String getStoredChangelog() {
        String cached = changelogMemoryCache.get();
        if (cached != null) return cached;

        Path cachePath = getChangelogCachePath();
        try {
            String content = Files.readString(cachePath);
            changelogMemoryCache.set(content);
            return content;
        } catch (IOException e) {
            changelogMemoryCache.set("");
            return "";
        }
    }

    /**
     * Synchronous accessor reading only from the in-memory cache.
     * Returns empty string if the async {@link #getStoredChangelog()} has not run yet.
     * Translated from getStoredChangelogFromMemory() in releaseNotes.ts
     */
    public String getStoredChangelogFromMemory() {
        String cached = changelogMemoryCache.get();
        return cached != null ? cached : "";
    }

    // -------------------------------------------------------------------------
    // Changelog parsing
    // -------------------------------------------------------------------------

    /**
     * Parse a changelog string in Markdown format into a map of version → notes.
     * Translated from parseChangelog() in releaseNotes.ts
     *
     * @param content raw Markdown changelog
     * @return insertion-ordered map: version string → list of bullet-point notes
     */
    public Map<String, List<String>> parseChangelog(String content) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (content == null || content.isEmpty()) return result;

        try {
            // Split on "## " headings; skip the first part (document header)
            String[] sections = content.split("(?m)^## ");
            for (int i = 1; i < sections.length; i++) {
                String section = sections[i].trim();
                String[] lines = section.split("\n");
                if (lines.length == 0) continue;

                // First line is the version line; supports "1.2.3" and "1.2.3 - YYYY-MM-DD"
                String versionLine = lines[0].trim();
                if (versionLine.isEmpty()) continue;
                String version = versionLine.split(" - ")[0].trim();
                if (version.isEmpty()) continue;

                List<String> notes = Arrays.stream(lines)
                        .skip(1)
                        .map(String::trim)
                        .filter(l -> l.startsWith("- "))
                        .map(l -> l.substring(2).trim())
                        .filter(l -> !l.isEmpty())
                        .collect(Collectors.toList());

                if (!notes.isEmpty()) {
                    result.put(version, notes);
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing changelog: {}", e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Release note queries
    // -------------------------------------------------------------------------

    /**
     * Returns up to {@value #MAX_RELEASE_NOTES_SHOWN} release note lines for
     * versions newer than {@code previousVersion}, most recent first.
     * Translated from getRecentReleaseNotes() in releaseNotes.ts
     */
    public List<String> getRecentReleaseNotes(
            String currentVersion,
            String previousVersion,
            String changelogContent
    ) {
        try {
            Map<String, List<String>> releaseNotes = parseChangelog(changelogContent);

            String baseCurrent = coerceVersion(currentVersion);
            String basePrevious = previousVersion != null ? coerceVersion(previousVersion) : null;

            if (basePrevious == null
                    || (baseCurrent != null && isGreaterThan(baseCurrent, basePrevious))) {

                return releaseNotes.entrySet().stream()
                        .filter(e -> basePrevious == null
                                || isGreaterThan(coerceVersion(e.getKey()), basePrevious))
                        .sorted((a, b) -> isGreaterThan(
                                coerceVersion(a.getKey()), coerceVersion(b.getKey())) ? -1 : 1)
                        .flatMap(e -> e.getValue().stream())
                        .filter(s -> s != null && !s.isEmpty())
                        .limit(MAX_RELEASE_NOTES_SHOWN)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Error getting recent release notes: {}", e.getMessage());
        }
        return List.of();
    }

    /** Overload that reads from the in-memory cache. */
    public List<String> getRecentReleaseNotes(String currentVersion, String previousVersion) {
        return getRecentReleaseNotes(currentVersion, previousVersion, getStoredChangelogFromMemory());
    }

    /**
     * Returns all release note entries as a list of (version, notes) pairs,
     * sorted with oldest first.
     * Translated from getAllReleaseNotes() in releaseNotes.ts
     */
    public List<Map.Entry<String, List<String>>> getAllReleaseNotes(String changelogContent) {
        try {
            Map<String, List<String>> releaseNotes = parseChangelog(changelogContent);

            return releaseNotes.entrySet().stream()
                    .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                    .sorted(Comparator.comparing(e -> coerceVersion(e.getKey()),
                            (a, b) -> isGreaterThan(a, b) ? 1 : -1))
                    .map(e -> (Map.Entry<String, List<String>>)
                            new AbstractMap.SimpleImmutableEntry<>(
                                    e.getKey(),
                                    e.getValue().stream().filter(s -> s != null && !s.isEmpty())
                                            .collect(Collectors.toList())))
                    .filter(e -> !e.getValue().isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Error getting all release notes: {}", e.getMessage());
            return List.of();
        }
    }

    /** Overload that reads from the in-memory cache. */
    public List<Map.Entry<String, List<String>>> getAllReleaseNotes() {
        return getAllReleaseNotes(getStoredChangelogFromMemory());
    }

    // -------------------------------------------------------------------------
    // High-level check
    // -------------------------------------------------------------------------

    /**
     * Checks whether there are release notes to show and (asynchronously)
     * refreshes the changelog when the version has changed.
     * Translated from checkForReleaseNotes() in releaseNotes.ts
     *
     * @param lastSeenVersion last version for which notes were shown (may be null)
     * @param currentVersion  the running application version
     */
    public CompletableFuture<ReleaseNotesResult> checkForReleaseNotes(
            String lastSeenVersion,
            String currentVersion
    ) {
        return CompletableFuture.supplyAsync(() -> {
            String cachedChangelog = getStoredChangelog();

            // Trigger background refresh when version changes or cache is empty
            if (!currentVersion.equals(lastSeenVersion) || cachedChangelog.isEmpty()) {
                fetchAndStoreChangelog().exceptionally(e -> {
                    log.debug("Background changelog fetch failed: {}", e.getMessage());
                    return null;
                });
            }

            List<String> notes = getRecentReleaseNotes(currentVersion, lastSeenVersion,
                    cachedChangelog);
            return new ReleaseNotesResult(!notes.isEmpty(), notes);
        });
    }

    /**
     * Synchronous variant of {@link #checkForReleaseNotes} for render paths.
     * Reads only from the in-memory cache.
     * Translated from checkForReleaseNotesSync() in releaseNotes.ts
     */
    public ReleaseNotesResult checkForReleaseNotesSync(
            String lastSeenVersion,
            String currentVersion
    ) {
        List<String> notes = getRecentReleaseNotes(currentVersion, lastSeenVersion);
        return new ReleaseNotesResult(!notes.isEmpty(), notes);
    }

    // -------------------------------------------------------------------------
    // Result record
    // -------------------------------------------------------------------------

    /**
     * Wraps the result of a release-notes check.
     * Corresponds to the anonymous object returned by checkForReleaseNotes() in releaseNotes.ts
     */
    public record ReleaseNotesResult(boolean hasReleaseNotes, List<String> releaseNotes) {}

    // -------------------------------------------------------------------------
    // Semver helpers (lightweight — no external dependency)
    // -------------------------------------------------------------------------

    /**
     * Strip non-numeric build metadata and return a normalised "MAJOR.MINOR.PATCH" string.
     * Returns the input unchanged if it cannot be parsed.
     */
    private static String coerceVersion(String version) {
        if (version == null) return "0.0.0";
        // Keep only the leading digits.digits.digits portion
        String stripped = version.replaceAll("[^0-9.].*$", "").replaceAll("\\.$", "");
        String[] parts = stripped.split("\\.", 3);
        try {
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2].replaceAll("[^0-9].*$", "")) : 0;
            return major + "." + minor + "." + patch;
        } catch (NumberFormatException e) {
            return version;
        }
    }

    /** Returns {@code true} when {@code a} is semantically greater than {@code b}. */
    private static boolean isGreaterThan(String a, String b) {
        if (a == null || b == null) return false;
        int[] pa = parseParts(a);
        int[] pb = parseParts(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) return pa[i] > pb[i];
        }
        return false;
    }

    private static int[] parseParts(String version) {
        String[] parts = version.split("\\.", 3);
        int[] result = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", "")); }
            catch (NumberFormatException ignored) {}
        }
        return result;
    }
}
