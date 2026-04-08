package com.anthropic.claudecode.util;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bash comment label extraction utilities.
 * Translated from src/tools/BashTool/commentLabel.ts
 *
 * Extracts the first-line comment from a bash command for display purposes.
 */
public class BashCommentLabel {

    private static final Pattern COMMENT_STRIP_PATTERN = Pattern.compile("^#+\\s*");

    /**
     * Extract the comment label from a bash command.
     * Translated from extractBashCommentLabel() in commentLabel.ts
     *
     * If the first line is a `# comment` (not a `#!` shebang), returns the comment text.
     */
    public static Optional<String> extractBashCommentLabel(String command) {
        if (command == null || command.isBlank()) return Optional.empty();

        int nl = command.indexOf('\n');
        String firstLine = (nl == -1 ? command : command.substring(0, nl)).trim();

        if (!firstLine.startsWith("#") || firstLine.startsWith("#!")) {
            return Optional.empty();
        }

        String label = COMMENT_STRIP_PATTERN.matcher(firstLine).replaceFirst("").trim();
        return label.isEmpty() ? Optional.empty() : Optional.of(label);
    }

    private BashCommentLabel() {}
}
