package com.anthropic.claudecode.util;

import java.util.*;

/**
 * CLI syntax highlighting utilities.
 * Translated from src/utils/cliHighlight.ts
 *
 * Provides syntax highlighting for code blocks in terminal output.
 * This is a simplified stub - full implementation would use a Java syntax highlighting library.
 */
public class CliHighlightUtils {

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
        Map.entry(".java", "java"),
        Map.entry(".ts", "typescript"),
        Map.entry(".tsx", "typescript"),
        Map.entry(".js", "javascript"),
        Map.entry(".jsx", "javascript"),
        Map.entry(".py", "python"),
        Map.entry(".rb", "ruby"),
        Map.entry(".go", "go"),
        Map.entry(".rs", "rust"),
        Map.entry(".cpp", "cpp"),
        Map.entry(".c", "c"),
        Map.entry(".cs", "csharp"),
        Map.entry(".sh", "bash"),
        Map.entry(".bash", "bash"),
        Map.entry(".zsh", "bash"),
        Map.entry(".json", "json"),
        Map.entry(".yaml", "yaml"),
        Map.entry(".yml", "yaml"),
        Map.entry(".xml", "xml"),
        Map.entry(".html", "html"),
        Map.entry(".css", "css"),
        Map.entry(".md", "markdown"),
        Map.entry(".sql", "sql"),
        Map.entry(".kt", "kotlin"),
        Map.entry(".swift", "swift"),
        Map.entry(".php", "php"),
        Map.entry(".r", "r"),
        Map.entry(".scala", "scala")
    );

    /**
     * Get the language for a file extension.
     * Translated from getLanguageName() in cliHighlight.ts
     */
    public static String getLanguageForExtension(String extension) {
        if (extension == null) return null;
        return EXTENSION_TO_LANGUAGE.get(extension.toLowerCase());
    }

    /**
     * Get the language for a file path.
     */
    public static String getLanguageForFile(String filePath) {
        if (filePath == null) return null;
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex < 0) return null;
        String extension = filePath.substring(dotIndex).toLowerCase();
        return EXTENSION_TO_LANGUAGE.get(extension);
    }

    /**
     * Check if a language is supported for highlighting.
     * Translated from supportsLanguage() in cliHighlight.ts
     */
    public static boolean supportsLanguage(String language) {
        return language != null && EXTENSION_TO_LANGUAGE.containsValue(language.toLowerCase());
    }

    private CliHighlightUtils() {}
}
