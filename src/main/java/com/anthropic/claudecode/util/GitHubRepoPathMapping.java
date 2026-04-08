package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * GitHub repository ↔ local path mapping utilities.
 * Translated from src/utils/githubRepoPathMapping.ts
 *
 * Manages a mapping between GitHub "owner/repo" identifiers and known local
 * clone paths so Claude Code can suggest/find the correct working directory
 * when a user references a remote repository.
 *
 * The mapping is persisted in global config. The most-recently-used path is
 * always promoted to the front of the list.
 */
@Slf4j
public class GitHubRepoPathMapping {



    /**
     * Updates the GitHub repository path mapping in global config.
     * Called at startup (fire-and-forget) to track known local paths for repos.
     * Non-blocking — errors are logged silently.
     *
     * Stores the git root (not cwd) so the mapping always points to the
     * repository root regardless of which subdirectory the user launched from.
     * If the path is already tracked it is promoted to the front of the list
     * so the most recently used clone appears first.
     *
     * Translated from updateGithubRepoPathMapping() in githubRepoPathMapping.ts
     */
    public static CompletableFuture<Void> updateGithubRepoPathMapping(
            String repo,
            String basePath,
            Map<String, List<String>> existingMapping,
            java.util.function.Consumer<Map<String, List<String>>> saveMapping) {

        return CompletableFuture.runAsync(() -> {
            try {
                if (repo == null || repo.isBlank()) {
                    log.debug("Not in a GitHub repository, skipping path mapping update");
                    return;
                }

                // Resolve symlinks for canonical storage
                String currentPath;
                try {
                    currentPath = Paths.get(basePath).toRealPath().toString();
                    // Normalize to NFC unicode form on macOS (Java's toRealPath handles symlinks
                    // but not unicode normalization — use java.text.Normalizer if needed)
                    currentPath = java.text.Normalizer.normalize(currentPath, java.text.Normalizer.Form.NFC);
                } catch (Exception e) {
                    currentPath = basePath;
                }

                // Normalize repo key to lowercase for case-insensitive matching
                String repoKey = repo.toLowerCase();

                List<String> existingPaths = existingMapping.getOrDefault(repoKey, Collections.emptyList());

                if (!existingPaths.isEmpty() && existingPaths.get(0).equals(currentPath)) {
                    // Already at the front — nothing to do
                    log.debug("Path {} already tracked for repo {}", currentPath, repoKey);
                    return;
                }

                // Remove if present elsewhere (to promote to front), then prepend
                final String finalCurrentPath = currentPath;
                List<String> withoutCurrent = existingPaths.stream()
                        .filter(p -> !p.equals(finalCurrentPath))
                        .collect(Collectors.toList());
                List<String> updatedPaths = new ArrayList<>();
                updatedPaths.add(currentPath);
                updatedPaths.addAll(withoutCurrent);

                Map<String, List<String>> updatedMapping = new HashMap<>(existingMapping);
                updatedMapping.put(repoKey, updatedPaths);
                saveMapping.accept(updatedMapping);

                log.debug("Added {} to tracked paths for repo {}", currentPath, repoKey);
            } catch (Exception e) {
                log.debug("Error updating repo path mapping: {}", e.getMessage());
                // Silently fail — this is non-blocking startup work
            }
        });
    }

    /**
     * Gets known local paths for a given GitHub repository.
     *
     * @param repo       the repository in "owner/repo" format
     * @param mapping    the current path mapping from config
     * @return list of known absolute paths, or empty list if none
     *
     * Translated from getKnownPathsForRepo() in githubRepoPathMapping.ts
     */
    public static List<String> getKnownPathsForRepo(String repo, Map<String, List<String>> mapping) {
        if (repo == null || mapping == null) return Collections.emptyList();
        String repoKey = repo.toLowerCase();
        return mapping.getOrDefault(repoKey, Collections.emptyList());
    }

    /**
     * Filters paths to only those that exist on the filesystem.
     *
     * @param paths list of absolute paths to check
     * @return future resolving to the subset of paths that exist
     *
     * Translated from filterExistingPaths() in githubRepoPathMapping.ts
     */
    public static CompletableFuture<List<String>> filterExistingPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return CompletableFuture.supplyAsync(() ->
                paths.stream()
                        .filter(p -> Files.exists(Paths.get(p)))
                        .collect(Collectors.toList())
        );
    }

    /**
     * Validates that a path contains the expected GitHub repository by inspecting
     * its git remote URL.
     *
     * @param path         absolute path to check
     * @param expectedRepo expected repository in "owner/repo" format
     * @return future resolving to true if the path contains the expected repo
     *
     * Translated from validateRepoAtPath() in githubRepoPathMapping.ts
     */
    public static CompletableFuture<Boolean> validateRepoAtPath(String path, String expectedRepo) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Run `git remote get-url origin` in the target directory
                ProcessBuilder pb = new ProcessBuilder("git", "remote", "get-url", "origin");
                pb.directory(new java.io.File(path));
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String remoteUrl = new String(process.getInputStream().readAllBytes()).trim();
                int exitCode = process.waitFor();
                if (exitCode != 0 || remoteUrl.isBlank()) {
                    return false;
                }

                String actualRepo = parseGitHubRepository(remoteUrl);
                if (actualRepo == null) {
                    return false;
                }

                // Case-insensitive comparison
                return actualRepo.equalsIgnoreCase(expectedRepo);
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Removes a path from the tracked paths for a given repository.
     * Used when a path is found to be invalid during selection.
     *
     * @param repo          the repository in "owner/repo" format
     * @param pathToRemove  the path to remove from tracking
     * @param existingMapping the current path mapping
     * @param saveMapping   consumer to persist the updated mapping
     *
     * Translated from removePathFromRepo() in githubRepoPathMapping.ts
     */
    public static void removePathFromRepo(
            String repo,
            String pathToRemove,
            Map<String, List<String>> existingMapping,
            java.util.function.Consumer<Map<String, List<String>>> saveMapping) {

        String repoKey = repo.toLowerCase();
        List<String> existingPaths = existingMapping.getOrDefault(repoKey, Collections.emptyList());

        List<String> updatedPaths = existingPaths.stream()
                .filter(p -> !p.equals(pathToRemove))
                .collect(Collectors.toList());

        if (updatedPaths.size() == existingPaths.size()) {
            // Path wasn't in the list, nothing to do
            return;
        }

        Map<String, List<String>> updatedMapping = new HashMap<>(existingMapping);

        if (updatedPaths.isEmpty()) {
            // Remove the repo key entirely if no paths remain
            updatedMapping.remove(repoKey);
        } else {
            updatedMapping.put(repoKey, updatedPaths);
        }

        saveMapping.accept(updatedMapping);
        log.debug("Removed {} from tracked paths for repo {}", pathToRemove, repoKey);
    }

    /**
     * Parses a GitHub repository identifier ("owner/repo") from a git remote URL.
     * Supports both HTTPS and SSH remote formats.
     *
     * @param remoteUrl git remote URL (https or ssh)
     * @return "owner/repo" string (lowercased), or null if not a GitHub URL
     */
    public static String parseGitHubRepository(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;

        // HTTPS: https://github.com/owner/repo(.git)?
        java.util.regex.Matcher httpsMatcher = java.util.regex.Pattern
                .compile("https://github\\.com/([^/]+/[^/]+?)(?:\\.git)?$")
                .matcher(remoteUrl.trim());
        if (httpsMatcher.find()) {
            return httpsMatcher.group(1).toLowerCase();
        }

        // SSH: git@github.com:owner/repo(.git)?
        java.util.regex.Matcher sshMatcher = java.util.regex.Pattern
                .compile("git@github\\.com:([^/]+/[^/]+?)(?:\\.git)?$")
                .matcher(remoteUrl.trim());
        if (sshMatcher.find()) {
            return sshMatcher.group(1).toLowerCase();
        }

        return null;
    }

    private GitHubRepoPathMapping() {}
}
