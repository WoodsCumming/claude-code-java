package com.anthropic.claudecode.util;

import java.util.Random;

/**
 * Random word slug generator.
 * Translated from src/utils/words.ts
 *
 * Generates human-readable slugs for plan IDs and session names.
 */
public class WordSlugUtils {

    private static final String[] ADJECTIVES = {
        "abundant", "ancient", "bright", "calm", "cheerful", "clever",
        "cozy", "curious", "dapper", "dazzling", "deep", "delightful",
        "eager", "elegant", "enchanted", "fancy", "fluffy", "gentle",
        "gleaming", "golden", "graceful", "happy", "hidden", "humble",
        "jolly", "joyful", "keen", "kind", "lively", "lovely",
        "lucky", "magical", "merry", "mighty", "nimble", "noble",
        "peaceful", "playful", "proud", "quiet", "radiant", "serene",
        "shining", "silver", "sleek", "smart", "smooth", "snappy",
        "sparkling", "stellar", "swift", "tender", "thoughtful", "tidy",
        "tranquil", "vibrant", "warm", "whimsical", "wise", "witty",
        "zesty", "zealous"
    };

    private static final String[] NOUNS = {
        "algorithm", "aurora", "beacon", "cascade", "cipher", "cloud",
        "compass", "crystal", "dawn", "delta", "ember", "epoch",
        "forest", "galaxy", "garden", "harbor", "horizon", "island",
        "journey", "kernel", "lantern", "library", "meadow", "melody",
        "mountain", "nebula", "ocean", "oracle", "pathway", "phoenix",
        "pixel", "prism", "quest", "river", "rocket", "sage",
        "signal", "snowflake", "solstice", "spark", "spectrum", "star",
        "stream", "summit", "sunrise", "tempest", "token", "trail",
        "twilight", "valley", "vertex", "voyage", "wave", "whisper",
        "willow", "zenith"
    };

    private static final Random RANDOM = new Random();

    /**
     * Generate a random word slug.
     * Translated from generateWordSlug() in words.ts
     */
    public static String generateWordSlug() {
        String adj = ADJECTIVES[RANDOM.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[RANDOM.nextInt(NOUNS.length)];
        return adj + "-" + noun;
    }

    /**
     * Generate a word slug with a number suffix.
     */
    public static String generateWordSlugWithNumber() {
        return generateWordSlug() + "-" + (RANDOM.nextInt(900) + 100);
    }

    private WordSlugUtils() {}
}
