package com.anthropic.claudecode.util;

import java.util.regex.Pattern;

/**
 * Display tag utilities for stripping XML-like tags from text.
 * Translated from src/utils/displayTags.ts
 *
 * Matches any XML-like {@code <tag>...</tag>} block (lowercase tag names, optional
 * attributes, multi-line content). Used to strip system-injected wrapper tags
 * from display titles.
 */
public class DisplayTagUtils {

    /**
     * Matches any XML-like tag block with lowercase tag names.
     * Equivalent to the XML_TAG_BLOCK_PATTERN constant in displayTags.ts.
     * Only matches lowercase tag names ([a-z][\w-]*) so user prose mentioning
     * JSX/HTML components passes through.
     */
    private static final Pattern XML_TAG_BLOCK_PATTERN =
        Pattern.compile("<([a-z][\\w-]*)(?:\\s[^>]*)?>(?:[\\s\\S]*?)</\\1>\\n?", Pattern.DOTALL);

    /**
     * Matches only IDE-injected context tags (ide_opened_file, ide_selection).
     * Equivalent to the IDE_CONTEXT_TAGS_PATTERN constant in displayTags.ts.
     */
    private static final Pattern IDE_CONTEXT_TAGS_PATTERN =
        Pattern.compile("<(ide_opened_file|ide_selection)(?:\\s[^>]*)?>(?:[\\s\\S]*?)</\\1>\\n?",
            Pattern.DOTALL);

    /**
     * Strip XML-like tag blocks from text for use in UI titles.
     * If stripping would result in empty text, returns the original unchanged.
     * Translated from stripDisplayTags() in displayTags.ts
     */
    public static String stripDisplayTags(String text) {
        if (text == null) return "";
        String result = XML_TAG_BLOCK_PATTERN.matcher(text).replaceAll("").trim();
        return result.isEmpty() ? text : result;
    }

    /**
     * Like stripDisplayTags but returns empty string when all content is tags.
     * Used to detect command-only prompts (e.g. /clear) so they can fall through
     * to the next title fallback.
     * Translated from stripDisplayTagsAllowEmpty() in displayTags.ts
     */
    public static String stripDisplayTagsAllowEmpty(String text) {
        if (text == null) return "";
        return XML_TAG_BLOCK_PATTERN.matcher(text).replaceAll("").trim();
    }

    /**
     * Strip only IDE-injected context tags (ide_opened_file, ide_selection).
     * Used by text resubmit so UP-arrow resubmit preserves user-typed content
     * including lowercase HTML while dropping IDE noise.
     * Translated from stripIdeContextTags() in displayTags.ts
     */
    public static String stripIdeContextTags(String text) {
        if (text == null) return "";
        return IDE_CONTEXT_TAGS_PATTERN.matcher(text).replaceAll("").trim();
    }

    private DisplayTagUtils() {}
}
