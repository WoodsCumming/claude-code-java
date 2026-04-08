package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Glob pattern matching utilities backed by ripgrep (or Java NIO fallback).
 * Translated from src/utils/glob.ts
 */
@Slf4j
public class GlobUtils {



    // -------------------------------------------------------------------------
    // GlobBaseDir record
    // -------------------------------------------------------------------------

    /**
     * Result of extracting the static base directory from a glob pattern.
     * Translated from the return type of extractGlobBaseDirectory() in glob.ts
     */
    public record GlobBaseDir(String baseDir, String relativePattern) {}

    // -------------------------------------------------------------------------
    // extractGlobBaseDirectory
    // -------------------------------------------------------------------------

    /**
     * Extracts the static base directory from a glob pattern.
     * The base directory is everything before the first glob special character (* ? [ {).
     * Returns the directory portion and the remaining relative pattern.
     *
     * Translated from extractGlobBaseDirectory() in glob.ts
     */
    public static GlobBaseDir extractGlobBaseDirectory(String pattern) {
        if (pattern == null || pattern.isEmpty()) return new GlobBaseDir("", "");

        // Find the first glob special character: *, ?, [, {
        int firstGlob = -1;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') {
                firstGlob = i;
                break;
            }
        }

        if (firstGlob == -1) {
            // No glob characters — literal path
            File f = new File(pattern);
            String parent = f.getParent();
            return new GlobBaseDir(parent != null ? parent : "", f.getName());
        }

        // Get everything before the first glob character
        String staticPrefix = pattern.substring(0, firstGlob);

        // Find the last path separator in the static prefix
        int lastSep = Math.max(
                staticPrefix.lastIndexOf('/'),
                staticPrefix.lastIndexOf(File.separatorChar));

        if (lastSep == -1) {
            // No path separator before the glob — pattern is relative to cwd
            return new GlobBaseDir("", pattern);
        }

        String baseDir = staticPrefix.substring(0, lastSep);
        String relativePattern = pattern.substring(lastSep + 1);

        // Handle root directory patterns (e.g., /*.txt)
        if (baseDir.isEmpty() && lastSep == 0) {
            baseDir = "/";
        }

        // Handle Windows drive root paths (e.g., C:/*.txt)
        if (System.getProperty("os.name", "").toLowerCase().contains("win")
                && baseDir.matches("[A-Za-z]:")) {
            baseDir = baseDir + File.separator;
        }

        return new GlobBaseDir(baseDir, relativePattern);
    }

    // -------------------------------------------------------------------------
    // GlobOptions
    // -------------------------------------------------------------------------

    /**
     * Options for the {@link #glob} method.
     */
    public record GlobOptions(int limit, int offset) {
        public static GlobOptions of(int limit, int offset) {
            return new GlobOptions(limit, offset);
        }
    }

    // -------------------------------------------------------------------------
    // GlobResult
    // -------------------------------------------------------------------------

    /**
     * Result of a glob search.
     */
    public record GlobResult(List<String> files, boolean truncated) {}

    // -------------------------------------------------------------------------
    // glob
    // -------------------------------------------------------------------------

    /**
     * Runs a glob pattern search and returns matching absolute file paths.
     *
     * Translated from glob() in glob.ts
     *
     * In the TypeScript implementation this delegates to ripgrep for performance.
     * Here we use Java NIO {@code PathMatcher} as the default implementation.
     *
     * @param filePattern           glob pattern (absolute or relative)
     * @param cwd                   working directory to search from
     * @param options               pagination options (limit / offset)
     * @param ignorePatterns        patterns to exclude (mirrors ripgrep --glob !pattern)
     * @return CompletableFuture resolving to matching file paths and a truncated flag
     */
    public static CompletableFuture<GlobResult> glob(
            String filePattern,
            String cwd,
            GlobOptions options,
            List<String> ignorePatterns) {

        return CompletableFuture.supplyAsync(() -> {
            String searchDir  = cwd != null ? cwd : System.getProperty("user.dir");
            String searchPattern = filePattern;

            // Handle absolute paths by extracting the base directory
            if (Paths.get(filePattern).isAbsolute()) {
                GlobBaseDir extracted = extractGlobBaseDirectory(filePattern);
                if (extracted.baseDir() != null && !extracted.baseDir().isEmpty()) {
                    searchDir    = extracted.baseDir();
                    searchPattern = extracted.relativePattern();
                }
            }

            Path base = Paths.get(searchDir);
            if (!Files.exists(base)) {
                return new GlobResult(List.of(), false);
            }

            // Build NIO glob matcher
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + searchPattern);

            // Build ignore matchers
            final List<PathMatcher> ignoreMatchers = new ArrayList<>();
            if (ignorePatterns != null) {
                for (String ip : ignorePatterns) {
                    try {
                        ignoreMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + ip));
                    } catch (Exception e) {
                        log.debug("Ignoring invalid ignore pattern: {}", ip);
                    }
                }
            }

            // Default directories to skip (mirrors TS noIgnore / hidden defaults)
            final Set<String> SKIP_DIRS = Set.of("node_modules", ".git", "target", "build",
                    "__pycache__", ".tox", "venv", ".venv", "dist", ".next", ".nuxt");

            final Path finalBase = base;
            List<String> allPaths = new ArrayList<>();

            try {
                Files.walkFileTree(base, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(finalBase)) return FileVisitResult.CONTINUE;
                        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path relative = finalBase.relativize(file);
                        if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                            // Check ignore patterns
                            for (PathMatcher ig : ignoreMatchers) {
                                if (ig.matches(relative) || ig.matches(file.getFileName())) {
                                    return FileVisitResult.CONTINUE;
                                }
                            }
                            String abs = file.isAbsolute()
                                    ? file.toString()
                                    : finalBase.resolve(file).toAbsolutePath().toString();
                            allPaths.add(abs);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("Glob walk failed: " + e.getMessage(), e);
            }

            int limit  = options != null ? options.limit()  : Integer.MAX_VALUE;
            int offset = options != null ? options.offset() : 0;

            boolean truncated = allPaths.size() > offset + limit;
            List<String> files = allPaths.subList(
                    Math.min(offset, allPaths.size()),
                    Math.min(offset + limit, allPaths.size()));

            return new GlobResult(new ArrayList<>(files), truncated);
        });
    }

    private GlobUtils() {}
}
