package com.anthropic.claudecode.util;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * File patterns that should be excluded from attribution.
 * Based on GitHub Linguist vendored patterns and common generated file patterns.
 *
 * Translated from src/utils/generatedFiles.ts
 */
public class GeneratedFileUtils {

    // -------------------------------------------------------------------------
    // Exact filename matches (case-insensitive)
    // -------------------------------------------------------------------------
    private static final Set<String> EXCLUDED_FILENAMES = Set.of(
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "bun.lockb",
            "bun.lock",
            "composer.lock",
            "gemfile.lock",
            "cargo.lock",
            "poetry.lock",
            "pipfile.lock",
            "shrinkwrap.json",
            "npm-shrinkwrap.json"
    );

    // -------------------------------------------------------------------------
    // File extension patterns (case-insensitive)
    // -------------------------------------------------------------------------
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".lock",
            ".min.js",
            ".min.css",
            ".min.html",
            ".bundle.js",
            ".bundle.css",
            ".generated.ts",
            ".generated.js",
            ".d.ts"           // TypeScript declaration files
    );

    // -------------------------------------------------------------------------
    // Directory patterns that indicate generated/vendored content (posix paths)
    // -------------------------------------------------------------------------
    private static final List<String> EXCLUDED_DIRECTORIES = List.of(
            "/dist/",
            "/build/",
            "/out/",
            "/output/",
            "/node_modules/",
            "/vendor/",
            "/vendored/",
            "/third_party/",
            "/third-party/",
            "/external/",
            "/.next/",
            "/.nuxt/",
            "/.svelte-kit/",
            "/coverage/",
            "/__pycache__/",
            "/.tox/",
            "/venv/",
            "/.venv/",
            "/target/release/",
            "/target/debug/"
    );

    // -------------------------------------------------------------------------
    // Filename patterns using regex for more complex matching
    // -------------------------------------------------------------------------
    private static final List<Pattern> EXCLUDED_FILENAME_PATTERNS = List.of(
            Pattern.compile("^.*\\.min\\.[a-z]+$", Pattern.CASE_INSENSITIVE),      // *.min.*
            Pattern.compile("^.*-min\\.[a-z]+$", Pattern.CASE_INSENSITIVE),        // *-min.*
            Pattern.compile("^.*\\.bundle\\.[a-z]+$", Pattern.CASE_INSENSITIVE),   // *.bundle.*
            Pattern.compile("^.*\\.generated\\.[a-z]+$", Pattern.CASE_INSENSITIVE),// *.generated.*
            Pattern.compile("^.*\\.gen\\.[a-z]+$", Pattern.CASE_INSENSITIVE),      // *.gen.*
            Pattern.compile("^.*\\.auto\\.[a-z]+$", Pattern.CASE_INSENSITIVE),     // *.auto.*
            Pattern.compile("^.*_generated\\.[a-z]+$", Pattern.CASE_INSENSITIVE),  // *_generated.*
            Pattern.compile("^.*_gen\\.[a-z]+$", Pattern.CASE_INSENSITIVE),        // *_gen.*
            Pattern.compile("^.*\\.pb\\.(go|js|ts|py|rb)$", Pattern.CASE_INSENSITIVE), // protobuf generated
            Pattern.compile("^.*_pb2?\\.py$", Pattern.CASE_INSENSITIVE),            // Python protobuf
            Pattern.compile("^.*\\.pb\\.h$", Pattern.CASE_INSENSITIVE),             // C++ protobuf headers
            Pattern.compile("^.*\\.grpc\\.[a-z]+$", Pattern.CASE_INSENSITIVE),     // gRPC generated
            Pattern.compile("^.*\\.swagger\\.[a-z]+$", Pattern.CASE_INSENSITIVE),  // Swagger generated
            Pattern.compile("^.*\\.openapi\\.[a-z]+$", Pattern.CASE_INSENSITIVE)   // OpenAPI generated
    );

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Check if a file should be excluded from attribution based on Linguist-style rules.
     *
     * Translated from isGeneratedFile() in generatedFiles.ts
     *
     * @param filePath relative file path from repository root
     * @return true if the file should be excluded from attribution
     */
    public static boolean isGeneratedFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) return false;

        // Normalize path separators for consistent pattern matching (patterns use posix-style /)
        String normalizedPath = "/" + filePath.replace(File.separatorChar, '/').replaceAll("^/+", "");
        String fileName = getBasename(filePath).toLowerCase();

        // 1. Check exact filename matches
        if (EXCLUDED_FILENAMES.contains(fileName)) {
            return true;
        }

        // 2. Check simple extension matches
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }

        // 3. Check for compound extensions like .min.js
        String[] parts = fileName.split("\\.");
        if (parts.length > 2) {
            String compoundExt = "." + parts[parts.length - 2] + "." + parts[parts.length - 1];
            if (EXCLUDED_EXTENSIONS.contains(compoundExt)) {
                return true;
            }
        }

        // 4. Check directory patterns
        for (String dir : EXCLUDED_DIRECTORIES) {
            if (normalizedPath.contains(dir)) {
                return true;
            }
        }

        // 5. Check filename regex patterns
        for (Pattern pattern : EXCLUDED_FILENAME_PATTERNS) {
            if (pattern.matcher(fileName).matches()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Filter a list of files to exclude generated files.
     *
     * Translated from filterGeneratedFiles() in generatedFiles.ts
     *
     * @param files array of file paths
     * @return list of files that are not generated
     */
    public static List<String> filterGeneratedFiles(List<String> files) {
        if (files == null) return List.of();
        return files.stream()
                .filter(f -> !isGeneratedFile(f))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String getBasename(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(File.separatorChar));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private GeneratedFileUtils() {}
}
