package com.anthropic.claudecode.model;

import java.util.Set;

/**
 * File-related constants.
 * Translated from src/constants/files.ts and src/constants/messages.ts
 */
public class FileConstants {

    public static final String NO_CONTENT_MESSAGE = "(no content)";

    /**
     * Binary file extensions to skip for text-based operations.
     * Translated from BINARY_EXTENSIONS in files.ts
     */
    public static final Set<String> BINARY_EXTENSIONS = Set.of(
        // Images
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".tiff", ".tif",
        // Videos
        ".mp4", ".mov", ".avi", ".mkv", ".webm", ".wmv", ".flv", ".m4v", ".mpeg", ".mpg",
        // Audio
        ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a", ".wma", ".aiff", ".opus",
        // Archives
        ".zip", ".tar", ".gz", ".bz2", ".xz", ".7z", ".rar", ".tar.gz", ".tar.bz2",
        // Compiled
        ".class", ".pyc", ".pyo", ".o", ".obj", ".exe", ".dll", ".so", ".dylib",
        // Other binary
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".db", ".sqlite", ".sqlite3",
        ".bin", ".dat", ".iso"
    );

    /**
     * Check if a file has a binary extension.
     * Translated from hasBinaryExtension() in files.ts
     */
    public static boolean hasBinaryExtension(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return BINARY_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private FileConstants() {}
}
