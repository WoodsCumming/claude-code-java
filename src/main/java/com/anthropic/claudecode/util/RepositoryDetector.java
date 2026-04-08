package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Repository detection utilities.
 *
 * Translated from src/utils/detectRepository.ts
 *
 * Detects the current git repository by inspecting the remote URL, then
 * parses it into host / owner / name components. Results are cached per
 * working directory so repeated calls are cheap.
 */
@Slf4j
public class RepositoryDetector {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RepositoryDetector.class);


    private static final Map<String, Optional<ParsedRepository>> repositoryWithHostCache =
            new ConcurrentHashMap<>();

    /** Mirrors ParsedRepository in detectRepository.ts */
    public record ParsedRepository(String host, String owner, String name) {}

    private RepositoryDetector() {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Clears all cached repository lookups.
     * Mirrors clearRepositoryCaches() in detectRepository.ts
     */
    public static void clearRepositoryCaches() {
        repositoryWithHostCache.clear();
    }

    /**
     * Returns "owner/repo" for the current working directory, or empty if:
     *   - not a git repo,
     *   - no remote URL, or
     *   - the host is not github.com.
     *
     * Only returns github.com results to avoid breaking downstream consumers.
     * Use {@link #detectCurrentRepositoryWithHost()} for GHE support.
     *
     * Mirrors detectCurrentRepository() in detectRepository.ts
     */
    public static CompletableFuture<Optional<String>> detectCurrentRepository() {
        return detectCurrentRepositoryWithHost()
                .thenApply(result -> result
                        .filter(r -> "github.com".equals(r.host()))
                        .map(r -> r.owner() + "/" + r.name())
                );
    }

    /**
     * Like {@link #detectCurrentRepository()} but also returns the host,
     * allowing callers to construct URLs for GitHub Enterprise instances.
     *
     * Mirrors detectCurrentRepositoryWithHost() in detectRepository.ts
     */
    public static CompletableFuture<Optional<ParsedRepository>> detectCurrentRepositoryWithHost() {
        String cwd = System.getProperty("user.dir");

        if (repositoryWithHostCache.containsKey(cwd)) {
            return CompletableFuture.completedFuture(repositoryWithHostCache.get(cwd));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String remoteUrl = getGitRemoteUrl(cwd);
                log.debug("Git remote URL: {}", remoteUrl);

                if (remoteUrl == null || remoteUrl.isBlank()) {
                    log.debug("No git remote URL found");
                    repositoryWithHostCache.put(cwd, Optional.empty());
                    return Optional.<ParsedRepository>empty();
                }

                Optional<ParsedRepository> parsed = parseGitRemote(remoteUrl);
                log.debug("Parsed repository: {} from URL: {}",
                        parsed.map(p -> p.host() + "/" + p.owner() + "/" + p.name()).orElse(null),
                        remoteUrl);
                repositoryWithHostCache.put(cwd, parsed);
                return parsed;

            } catch (Exception e) {
                log.debug("Error detecting repository: {}", e.getMessage());
                repositoryWithHostCache.put(cwd, Optional.empty());
                return Optional.<ParsedRepository>empty();
            }
        });
    }

    /**
     * Synchronously returns the cached github.com repository for the current cwd
     * as "owner/name", or null if it hasn't been resolved yet or the host is not
     * github.com. Call {@link #detectCurrentRepository()} first to populate the cache.
     *
     * Mirrors getCachedRepository() in detectRepository.ts
     */
    public static String getCachedRepository() {
        String cwd = System.getProperty("user.dir");
        Optional<ParsedRepository> parsed = repositoryWithHostCache.get(cwd);
        if (parsed == null || parsed.isEmpty()) return null;
        if (!"github.com".equals(parsed.get().host())) return null;
        return parsed.get().owner() + "/" + parsed.get().name();
    }

    // ------------------------------------------------------------------
    // parseGitRemote — full port of detectRepository.ts parseGitRemote()
    // ------------------------------------------------------------------

    /**
     * Parses a git remote URL into host, owner, and name components.
     * Accepts any host (github.com, GHE instances, etc.).
     *
     * Supports:
     *   https://host/owner/repo.git
     *   git@host:owner/repo.git
     *   ssh://git@host/owner/repo.git
     *   git://host/owner/repo.git
     *   https://host/owner/repo  (no .git)
     *
     * Note: repo names can contain dots (e.g., cc.kurs.web).
     * Mirrors parseGitRemote() in detectRepository.ts
     */
    public static Optional<ParsedRepository> parseGitRemote(String input) {
        if (input == null) return Optional.empty();
        String trimmed = input.trim();

        // SSH format: git@host:owner/repo.git
        Pattern sshPattern = Pattern.compile("^git@([^:]+):([^/]+)/([^/]+?)(?:\\.git)?$");
        Matcher sshMatcher = sshPattern.matcher(trimmed);
        if (sshMatcher.matches()) {
            String host  = sshMatcher.group(1);
            String owner = sshMatcher.group(2);
            String name  = sshMatcher.group(3);
            if (!looksLikeRealHostname(host)) return Optional.empty();
            return Optional.of(new ParsedRepository(host, owner, name));
        }

        // URL format: https://host/owner/repo.git, ssh://git@host/owner/repo, git://host/owner/repo
        Pattern urlPattern = Pattern.compile(
                "^(https?|ssh|git)://(?:[^@]+@)?([^/:]+(?::\\d+)?)/([^/]+)/([^/]+?)(?:\\.git)?$");
        Matcher urlMatcher = urlPattern.matcher(trimmed);
        if (urlMatcher.matches()) {
            String protocol    = urlMatcher.group(1);
            String hostWithPort = urlMatcher.group(2);
            String owner       = urlMatcher.group(3);
            String name        = urlMatcher.group(4);

            String hostWithoutPort = hostWithPort.contains(":")
                    ? hostWithPort.substring(0, hostWithPort.indexOf(':'))
                    : hostWithPort;

            if (!looksLikeRealHostname(hostWithoutPort)) return Optional.empty();

            // Only preserve the port for HTTPS — SSH/git ports are not usable for web URLs
            String host = (protocol.equals("https") || protocol.equals("http"))
                    ? hostWithPort
                    : hostWithoutPort;

            return Optional.of(new ParsedRepository(host, owner, name));
        }

        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // parseGitHubRepository
    // ------------------------------------------------------------------

    /**
     * Parses a git remote URL or "owner/repo" string and returns "owner/repo".
     * Only returns results for github.com hosts — GHE URLs return null.
     * Also accepts plain "owner/repo" strings for backward compatibility.
     *
     * Mirrors parseGitHubRepository() in detectRepository.ts
     */
    public static String parseGitHubRepository(String input) {
        if (input == null) return null;
        String trimmed = input.trim();

        // Try full remote URL first
        Optional<ParsedRepository> parsed = parseGitRemote(trimmed);
        if (parsed.isPresent()) {
            if (!"github.com".equals(parsed.get().host())) return null;
            return parsed.get().owner() + "/" + parsed.get().name();
        }

        // Check if it's already in owner/repo format
        if (!trimmed.contains("://") && !trimmed.contains("@") && trimmed.contains("/")) {
            String[] parts = trimmed.split("/", -1);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                String repo = parts[1].replaceAll("\\.git$", "");
                return parts[0] + "/" + repo;
            }
        }

        log.debug("Could not parse repository from: {}", trimmed);
        return null;
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Checks whether a hostname looks like a real domain name rather than an
     * SSH config alias. Requires a dot AND a purely-alphabetic TLD.
     *
     * Mirrors looksLikeRealHostname() in detectRepository.ts
     */
    private static boolean looksLikeRealHostname(String host) {
        if (!host.contains(".")) return false;
        String[] segments = host.split("\\.");
        String lastSegment = segments[segments.length - 1];
        if (lastSegment.isEmpty()) return false;
        // Real TLDs are purely alphabetic (e.g., "com", "org", "io").
        // SSH aliases like "github.com-work" have a last segment "com-work" which contains a hyphen.
        return lastSegment.matches("^[a-zA-Z]+$");
    }

    /**
     * Runs {@code git remote get-url origin} in the given directory and returns
     * the trimmed output, or null on failure.
     */
    private static String getGitRemoteUrl(String cwd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "remote", "get-url", "origin");
        pb.directory(new java.io.File(cwd));
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(5, TimeUnit.SECONDS);
        return output.isBlank() ? null : output;
    }
}
